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
 * Copyright (c) 2021 Roberto Gentili
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
package org.burningwave.tools.net;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Map;

public interface HostResolver {

	public default Collection<InetAddress> checkAndGetAllAddressesForHostName(Map<String, Object> argumentMap) throws UnknownHostException {
		String hostName = (String)getMethodArguments(argumentMap)[0];
		Collection<InetAddress> addresses = getAllAddressesForHostName(argumentMap);
		if (addresses.isEmpty()) {
			throw new UnknownHostException(hostName);
		}
		return addresses;
	}

	public default  Collection<String> checkAndGetAllHostNamesForHostAddress(Map<String, Object> argumentMap) throws UnknownHostException {
		byte[] address = (byte[])getMethodArguments(argumentMap)[0];
		Collection<String> hostNames = getAllHostNamesForHostAddress(argumentMap);
		if (hostNames.isEmpty()) {
			throw new UnknownHostException(IPAddressUtil.INSTANCE.numericToTextFormat(address));
		}
		return hostNames;
	}

	public Collection<InetAddress> getAllAddressesForHostName(Map<String, Object> arguments);

	public Collection<String> getAllHostNamesForHostAddress(Map<String, Object> arguments);

	public default boolean isReady(HostResolutionRequestInterceptor hostResolverService) {
		return hostResolverService.resolvers.contains(this);
	}

	public default Object handle(Method method, Object... arguments) throws Throwable {
		throw new UnsupportedOperationException(method.getName() + " is not supported");
	}

	public default Object[] getMethodArguments(Map<String, Object> arguments) {
		return (Object[])arguments.get("methodArguments");
	}
}