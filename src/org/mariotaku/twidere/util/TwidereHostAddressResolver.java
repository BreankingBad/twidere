/*
 *				Twidere - Twitter client for Android
 * 
 * Copyright (C) 2012 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.util;

import static android.text.TextUtils.isEmpty;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;

import org.mariotaku.twidere.Constants;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import twitter4j.http.HostAddressResolver;
import android.content.Context;
import android.content.SharedPreferences;
import org.apache.http.conn.util.InetAddressUtils;

public class TwidereHostAddressResolver implements Constants, HostAddressResolver {

	private static final String DEFAULT_DNS_SERVER_ADDRESS = "8.8.8.8";

	private final SharedPreferences mHostMapping, mPreferences;
	private final LinkedHashMap<String, String> mHostCache = new LinkedHashMap<String, String>(512, 0.75f, false);
	private final boolean mLocalMappingOnly;
	private final String mDNSAddress;

	private Resolver mResolver;
	
	public TwidereHostAddressResolver(final Context context) {
		this(context, false);
	}

	public TwidereHostAddressResolver(final Context context, final boolean local_only) {
		mHostMapping = context.getSharedPreferences(HOST_MAPPING_PREFERENCES_NAME, Context.MODE_PRIVATE);
		mPreferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
		final String address = mPreferences.getString(PREFERENCE_KEY_DNS_SERVER, DEFAULT_DNS_SERVER_ADDRESS);
		mDNSAddress = isValidIpAddress(address) ? address : DEFAULT_DNS_SERVER_ADDRESS;
		mLocalMappingOnly = local_only;
	}
	
	void init() throws UnknownHostException {
		mResolver = !mLocalMappingOnly ? new SimpleResolver(mDNSAddress) : null;
		if (mResolver != null) {
			mResolver.setTCP(true);
		}
	}

	static boolean isValidIpAddress(final String address) {
		return !isEmpty(address) || InetAddressUtils.isIPv4Address(address) || InetAddressUtils.isIPv6Address(address) 
				|| InetAddressUtils.isIPv6HexCompressedAddress(address) || InetAddressUtils.isIPv6StdAddress(address);
	}
	
	@Override
	public String resolve(final String host) throws IOException {
		if (host == null) return null;
		// First, I'll try to load address cached.
		if (mHostCache.containsKey(host)) return mHostCache.get(host);
		// Then I'll try to load from custom host mapping.
		// Stupid way to find top domain, but really fast.
		final String[] host_segments = host.split("\\.");
		final int host_segments_length = host_segments.length;
		if (host_segments_length > 2) {
			final String top_domain = host_segments[host_segments_length - 2] + "."
					+ host_segments[host_segments_length - 1];
			if (mHostMapping.contains(top_domain)) {
				final String host_addr = mHostMapping.getString(top_domain, null);
				mHostCache.put(top_domain, host_addr);
				return host_addr;
			}
		} else {
			if (mHostMapping.contains(host)) {
				final String host_addr = mHostMapping.getString(host, null);
				mHostCache.put(host, host_addr);
				return host_addr;
			}
		}
		if (!mLocalMappingOnly) {
			init();
		}
		// Use TCP DNS Query if enabled.
		if (mResolver != null && mPreferences.getBoolean(PREFERENCE_KEY_TCP_DNS_QUERY, false)) {
			final Name name = new Name(host);
			final Record query = Record.newRecord(name, Type.A, DClass.IN);
			final Message response = mResolver.send(Message.newQuery(query));
			final Record[] records = response.getSectionArray(Section.ANSWER);
			if (records == null || records.length < 1) throw new IOException("Could not find " + host);
			String host_addr = null;
			// Test each IP address resolved.
			for (final Record record : records) {
				if (record instanceof ARecord) {
					final InetAddress ipv4_addr = ((ARecord) record).getAddress();
					if (ipv4_addr.isReachable(300)) {
						host_addr = ipv4_addr.getHostAddress();
						mHostCache.put(host, host_addr);
						break;
					}
				} else if (record instanceof AAAARecord) {
					final InetAddress ipv6_addr = ((AAAARecord) record).getAddress();
					if (ipv6_addr.isReachable(300)) {
						host_addr = ipv6_addr.getHostAddress();
						mHostCache.put(host, host_addr);
						break;
					}
				}
			}
			// No address is reachable, but I believe the IP is correct.
			if (host_addr == null) {
				final Record record = records[0];
				if (record instanceof ARecord) {
					final InetAddress ipv4_addr = ((ARecord) record).getAddress();
					host_addr = ipv4_addr.getHostAddress();
					mHostCache.put(host, host_addr);
				} else if (record instanceof AAAARecord) {
					final InetAddress ipv6_addr = ((AAAARecord) record).getAddress();
					host_addr = ipv6_addr.getHostAddress();
					mHostCache.put(host, host_addr);
				}
			}
			return host_addr;
		}
		return host;
	}

}
