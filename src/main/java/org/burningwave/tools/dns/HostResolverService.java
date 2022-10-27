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
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

public class HostResolverService {
	public static final HostResolverService INSTANCE;
	private Collection<Resolver> resolvers;

	static {
		INSTANCE = new HostResolverService();
	}

	private HostResolverService() {}

	public HostResolverService install(Resolver... resolvers) {
		this.resolvers = checkResolvers(resolvers);
        Object proxy;
        if (Collection.class.isAssignableFrom(DefaultHostResolver.nameServiceFieldClass)) {
        	proxy = new ArrayList<>(
    			Arrays.asList(
    				buildProxy()
				)
			);
        } else {
        	proxy = buildProxy();
        }
        Fields.setStaticDirect(DefaultHostResolver.inetAddressClass, DefaultHostResolver.nameServiceField.getName(), proxy);
        return this;
    }

	public HostResolverService reset() {
        Object nameServices;
		if (Collection.class.isAssignableFrom(DefaultHostResolver.nameServiceFieldClass)) {
			nameServices = DefaultHostResolver.nameServices;
        } else {
        	nameServices = DefaultHostResolver.nameServices.iterator().next();
        }
        Fields.setStaticDirect(DefaultHostResolver.inetAddressClass, DefaultHostResolver.nameServiceField.getName(), nameServices);
        return this;
	}

	private Collection<Resolver> checkResolvers(Resolver[] resolvers) {
		if (resolvers == null || resolvers.length < 1) {
			throw new IllegalArgumentException("Resolvers are required");
		}
		Collection<Resolver> resolverList = new ArrayList<>();
		for (int index = 0; index < resolvers.length; index++) {
			if (resolvers[index] == null) {
				throw new IllegalArgumentException(Strings.compile("Resolver at index [{}] is null", index));
			}
			resolverList.add(resolvers[index]);
		}
		return resolverList;
	}

	private Object buildProxy() {
		return Proxy.newProxyInstance(
			DefaultHostResolver.nameServiceClass.getClassLoader(),
			new Class<?>[] { DefaultHostResolver.nameServiceClass },
			buildInvocationHandler()
		);
	}

	private InvocationHandler buildInvocationHandler() {
		return (prx, method, args) -> {
    		String methodName = method.getName();
    		if (methodName.equals(DefaultHostResolver.getAllAddressesForHostNameMethod.getName())) {
    			return getAllAddressesForHostName(args);
            } else if (methodName.equals(DefaultHostResolver.getAllHostNamesForHostAddress.getName())) {
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
		return DefaultHostResolver.getAllAddressesForHostNameMethod.getReturnType().equals(InetAddress[].class) ?
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
