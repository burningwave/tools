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

import static org.burningwave.core.assembler.StaticComponentContainer.Fields;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

public class HostsResolverService {
	public static final HostsResolverService INSTANCE;
	private Collection<Resolver> resolvers;

	static {
		INSTANCE = new HostsResolverService();
	}

	private HostsResolverService() {}

	public HostsResolverService install(Resolver... resolvers) {
		this.resolvers = Arrays.asList(resolvers);
        Object proxy;
        if (Collection.class.isAssignableFrom(DefaultHostsResolver.nameServiceFieldClass)) {
        	proxy = Arrays.asList(
    			buildProxy()
			);
        } else {
        	proxy = buildProxy();
        }
        Fields.setStaticDirect(DefaultHostsResolver.inetAddressClass, DefaultHostsResolver.nameServiceField.getName(), proxy);
        return this;
    }

	private Object buildProxy() {
		return Proxy.newProxyInstance(
			DefaultHostsResolver.nameServiceClass.getClassLoader(),
			new Class<?>[] { DefaultHostsResolver.nameServiceClass },
			buildInvocationHandler()
		);
	}

	private InvocationHandler buildInvocationHandler() {
		return (prx, method, args) -> {
    		String methodName = method.getName();
    		if (methodName.equals(DefaultHostsResolver.getAllAddressesForHostNameMethod.getName())) {
    			return getAllAddressesForHostName(args);
            } else if (methodName.equals(DefaultHostsResolver.getAllHostNamesForHostAddress.getName())) {
            	return getAllAddressesForHostAddress(args).iterator().next();
            }
    		for (Resolver resolver : resolvers) {
    			Object toRet = resolver.handle(method, args);
    			if (toRet != null) {
    				return toRet;
    			}
    		}
    		throw new UnsupportedOperationException(method.getName() + " is not supported");
        };
	}

	private Object getAllAddressesForHostName(
		Object... args
	) throws Throwable {
		Collection<InetAddress> addresses = new ArrayList<>();
		for (Resolver resolver : resolvers) {
			addresses.addAll(resolver.getAllAddressesForHostName(args));
		}
		if (addresses.isEmpty()) {
			throw new UnknownHostException((String)args[0]);
		}
		return DefaultHostsResolver.getAllAddressesForHostNameMethod.getReturnType().equals(InetAddress[].class) ?
			addresses.toArray(new InetAddress[addresses.size()]) :
			addresses.stream();
	}


	private Collection<String> getAllAddressesForHostAddress(
		Object... args
	) throws Throwable {
		Collection<String> hostNames = new ArrayList<>();
		for (Resolver resolver : resolvers) {
			hostNames.addAll(resolver.getAllHostNamesForHostAddress(args));
		}
		if (hostNames.isEmpty()) {
			throw new UnknownHostException(IPAddressUtil.INSTANCE.numericToTextFormat((byte[])args[0]));
		}
		return hostNames;
	}

	public static interface Resolver {

		public Collection<InetAddress> getAllAddressesForHostName(Object... arguments);

		public Collection<String> getAllHostNamesForHostAddress(Object... arguments);

		public default Object handle(Method method, Object... arguments) throws Throwable {
			throw new UnsupportedOperationException(method.getName() + " is not supported");
		}
	}

}
