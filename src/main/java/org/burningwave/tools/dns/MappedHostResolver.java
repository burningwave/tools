/*
 * This file is part of Burningwave Tools.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/tools
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2022 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.tools.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("unchecked")
public class MappedHostResolver implements HostResolverService.Resolver {
	protected Map<String, String> hostAliases;

	@SafeVarargs
	public MappedHostResolver(Supplier<List<Map<String, Object>>>... hostAliasesYAMLFormatSuppliers) {
		this(Arrays.asList(hostAliasesYAMLFormatSuppliers));
	}

	public MappedHostResolver(Collection<Supplier<List<Map<String, Object>>>> hostAliasesYAMLFormatSuppliers) {
		Map<String, String> hostAliases = new LinkedHashMap<>();
		for (Supplier<List<Map<String, Object>>> hostAliasesYAMLFormatSupplier : hostAliasesYAMLFormatSuppliers) {
			for (Map<String, Object> addressesForIp : hostAliasesYAMLFormatSupplier.get()) {
				String iPAddress = (String)addressesForIp.get("ip");
				Collection<String> hostNames = (Collection<String>)addressesForIp.get("hostnames");
				for (String hostName : hostNames) {
					hostAliases.put(hostName, iPAddress);
				}
			}
		}
		this.hostAliases = hostAliases;
	}

	public MappedHostResolver(Map<String, String> hostAliases) {
		this.hostAliases = new LinkedHashMap<>(hostAliases);
    }


	@Override
	public Collection<InetAddress> getAllAddressesForHostName(Object... arguments) {
		String hostName = (String)arguments[0];
		Collection<InetAddress> addresses = new ArrayList<>();
		String iPAddress = hostAliases.get(hostName);
		if (iPAddress != null) {
			try {
				addresses.add(InetAddress.getByAddress(hostName, IPAddressUtil.INSTANCE.textToNumericFormat(iPAddress)));
			} catch (UnknownHostException e) {

			}
		}
		return addresses;
	}

	@Override
	public Collection<String> getAllHostNamesForHostAddress(Object... arguments) {
		byte[] address = (byte[])arguments[0];
		Collection<String> hostNames = new ArrayList<>();
		String iPAddress = IPAddressUtil.INSTANCE.numericToTextFormat(address);
		for (Map.Entry<String, String> addressForIp : hostAliases.entrySet()) {
			if (addressForIp.getValue().equals(iPAddress)) {
				hostNames.add(addressForIp.getKey());
			}
		}
		return hostNames;
	}

	public synchronized MappedHostResolver putHost(String hostname, String iP) {
		Map<String, String> hostAliases = new LinkedHashMap<>(this.hostAliases);
		hostAliases.put(hostname, iP);
		this.hostAliases = hostAliases;
		return this;
	}

	public synchronized MappedHostResolver removeHost(String hostname) {
		Map<String, String> hostAliases = new LinkedHashMap<>(this.hostAliases);
		hostAliases.remove(hostname);
		this.hostAliases = hostAliases;
		return this;
	}

	public synchronized MappedHostResolver removeHostForIP(String iP) {
		Map<String, String> hostAliases = new LinkedHashMap<>(this.hostAliases);
		Iterator<Map.Entry<String, String>> hostAliasesIterator = hostAliases.entrySet().iterator();
		while (hostAliasesIterator.hasNext()) {
			Map.Entry<String, String> host = hostAliasesIterator.next();
			if (host.getValue().equals(iP)) {
				hostAliasesIterator.remove();
			}
		}
		this.hostAliases = hostAliases;
		return this;
	}

	@Override
	public boolean isReady(HostResolverService hostResolverService) {
		return HostResolverService.Resolver.super.isReady(hostResolverService) && obtainsResponseForMappedHost();
	}

	protected synchronized boolean obtainsResponseForMappedHost() {
		String hostNameForTest = null;
		if (hostAliases.isEmpty()) {
			putHost(hostNameForTest = UUID.randomUUID().toString(), "127.0.0.1");
		}
		try {
			for (String hostname : hostAliases.keySet()) {
				InetAddress.getByName(hostname);
			}
			return true;
		} catch (UnknownHostException exc) {
			return false;
		} finally {
			if (hostNameForTest != null) {
				removeHost(hostNameForTest);
			}
		}
	}

}
