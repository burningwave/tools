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
import static org.burningwave.core.assembler.StaticComponentContainer.Methods;
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.burningwave.core.classes.FieldCriteria;

public class HostResolverService {
	public static final HostResolverService INSTANCE;
	private static final Function<HostResolverService, Object> proxySupplier;
	private static final Function<Collection<InetAddress>, Object> getAllAddressesForHostNameResultConverter;
	private static Object cacheOne;
	private static Object cacheTwo;

	Collection<Resolver> resolvers;


	static {
		proxySupplier = Collection.class.isAssignableFrom(DefaultHostResolver.nameServiceFieldClass) ?
			HostResolverService::buildProxies:
			HostResolverService::buildProxy;
		Field cacheOneField = Fields.findOne(FieldCriteria.withoutConsideringParentClasses().name(fieldName -> {
			return fieldName.equals("cache") || fieldName.equals("addressCache");
		}), DefaultHostResolver.inetAddressClass);
		if (cacheOneField.getName().equals("addressCache")) {
			cacheOne = Fields.getDirect(Fields.getStaticDirect(cacheOneField), "cache");
			cacheTwo = Fields.getDirect(Fields.getStaticDirect(DefaultHostResolver.inetAddressClass, "negativeCache"), "cache");
		} else {
			cacheOne = Fields.getStaticDirect(DefaultHostResolver.inetAddressClass, "cache");
			cacheTwo = Fields.getStaticDirect(DefaultHostResolver.inetAddressClass, "expirySet");
		}
		getAllAddressesForHostNameResultConverter = DefaultHostResolver.getAllAddressesForHostNameMethod.getReturnType().equals(InetAddress[].class) ?
			addresses ->
				addresses.toArray(new InetAddress[addresses.size()]) :
			addresses ->
				addresses.stream();
		INSTANCE = new HostResolverService();
	}

	private HostResolverService() {}

	public HostResolverService install(Resolver... resolvers) {
		return install(-1, 250, resolvers);
	}

	public synchronized HostResolverService install(long timeout, long sleepingTime, Resolver... resolvers) {
		this.resolvers = checkResolvers(resolvers);
        Fields.setStaticDirect(
    		DefaultHostResolver.nameServiceField,
    		proxySupplier.apply(this)
		);
        this.resolvers.stream().filter(MappedHostResolver.class::isInstance).findFirst()
        .map(MappedHostResolver.class::cast).ifPresent(hostResolver -> {
    		Long startTime = System.currentTimeMillis();
    		Long expirationTime = startTime + timeout;
    		while (!hostResolver.isReady(this) && (timeout < 0 || expirationTime > System.currentTimeMillis())) {
    			try {
    				Thread.sleep(sleepingTime);
    			} catch (InterruptedException exc) {}
    		}
        });
        return this;
    }

	public HostResolverService reset() {
        Object nameServices;
		if (Collection.class.isAssignableFrom(DefaultHostResolver.nameServiceFieldClass)) {
			nameServices = DefaultHostResolver.nameServices;
        } else {
        	nameServices = DefaultHostResolver.nameServices.iterator().next();
        }
        Fields.setStaticDirect(DefaultHostResolver.nameServiceField, nameServices);
	    clearCache();
        return this;
	}

	public void clearCache() {
		Methods.invokeDirect(cacheOne, "clear");
		Methods.invokeDirect(cacheTwo, "clear");
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

	private List<Object> buildProxies() {
		List<Object> proxies = new ArrayList<>();
		for (Resolver resolver : resolvers) {
			if (resolver instanceof DefaultHostResolver) {
				for (Object nameService : DefaultHostResolver.nameServices) {
					proxies.add(
						Proxy.newProxyInstance(
							DefaultHostResolver.nameServiceClass.getClassLoader(),
							new Class<?>[] { DefaultHostResolver.nameServiceClass },
							buildOneToOneInvocationHandler(resolver, nameService)
						)
					);
				}
			} else {
				proxies.add(
					Proxy.newProxyInstance(
						DefaultHostResolver.nameServiceClass.getClassLoader(),
						new Class<?>[] { DefaultHostResolver.nameServiceClass },
						buildOneToOneInvocationHandler(resolver, null)
					)
				);
			}
		}
		return proxies;
	}

	public InvocationHandler buildOneToOneInvocationHandler(Resolver resolver, Object nameService) {
		Function<Object[], Map<String, Object>> argumentsMapBuilder =
			nameService != null ?
				arguments -> {
					Map<String, Object> argumentsMap = buildArgumentsMap(arguments);
					argumentsMap.put("nameServices", Arrays.asList(nameService));
					return argumentsMap;
				}:
				this::buildArgumentsMap;

		return (proxy, method, arguments) -> {
			String methodName = method.getName();
			if (methodName.equals(DefaultHostResolver.getAllAddressesForHostNameMethod.getName())) {
				return getAllAddressesForHostNameResultConverter.apply(
					resolver.getAllAddressesForHostName(argumentsMapBuilder.apply(arguments))
				);
		    } else if (methodName.equals(DefaultHostResolver.getAllHostNamesForHostAddressMethod.getName())) {
		    	return resolver.getAllHostNamesForHostAddress(argumentsMapBuilder.apply(arguments)).iterator().next();
		    }
			Object toRet = resolver.handle(method, arguments);
			if (toRet != null) {
				return toRet;
			}
			throw new UnsupportedOperationException(method.getName() + " is not supported");
		};
	}

	private Object buildProxy() {
		return Proxy.newProxyInstance(
			DefaultHostResolver.nameServiceClass.getClassLoader(),
			new Class<?>[] { DefaultHostResolver.nameServiceClass },
			(proxy, method, arguments) -> {
	    		String methodName = method.getName();
	    		if (methodName.equals(DefaultHostResolver.getAllAddressesForHostNameMethod.getName())) {
	    			return getAllAddressesForHostName(arguments);
	            } else if (methodName.equals(DefaultHostResolver.getAllHostNamesForHostAddressMethod.getName())) {
	            	return getAllHostNamesForHostAddress(arguments).iterator().next();
	            }
	    		for (Resolver resolver : resolvers) {
	    			Object toRet = resolver.handle(method, arguments);
	    			if (toRet != null) {
	    				return toRet;
	    			}
	    		}
	    		throw new UnsupportedOperationException(method.getName() + " is not supported");
	        }
		);
	}

	private Object getAllAddressesForHostName(
		Object... args
	) throws Throwable {
		Collection<InetAddress> addresses = new ArrayList<>();
		Map<String, Object> argumentsMap = buildArgumentsMap(args);
		for (Resolver resolver : resolvers) {
			try {
				addresses.addAll(resolver.getAllAddressesForHostName(argumentsMap));
			} catch (UnknownHostException exc) {

			}
		}
		if (addresses.isEmpty()) {
			throw new UnknownHostException((String)args[0]);
		}
		return getAllAddressesForHostNameResultConverter.apply(addresses);
	}

	public Map<String, Object> buildArgumentsMap(Object[] args) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("methodArguments", args);
		return arguments;
	}


	private Collection<String> getAllHostNamesForHostAddress(
		Object... args
	) throws Throwable {
		Collection<String> hostNames = new ArrayList<>();
		Map<String, Object> argumentsMap = buildArgumentsMap(args);
		for (Resolver resolver : resolvers) {
			try {
				hostNames.addAll(resolver.getAllHostNamesForHostAddress(argumentsMap));
			} catch (UnknownHostException exc) {

			}
		}
		if (hostNames.isEmpty()) {
			throw new UnknownHostException(IPAddressUtil.INSTANCE.numericToTextFormat((byte[])args[0]));
		}
		return hostNames;
	}

	public static interface Resolver {

		public Collection<InetAddress> getAllAddressesForHostName(Map<String, Object> arguments) throws UnknownHostException;

		public Collection<String> getAllHostNamesForHostAddress(Map<String, Object> arguments) throws UnknownHostException;

		public default boolean isReady(HostResolverService hostResolverService) {
			return hostResolverService.resolvers.contains(this);
		}

		public default Object handle(Method method, Object... arguments) throws Throwable {
			throw new UnsupportedOperationException(method.getName() + " is not supported");
		}

		public default Object[] getMethodArguments(Map<String, Object> arguments) {
			return (Object[])arguments.get("methodArguments");
		}
	}

}