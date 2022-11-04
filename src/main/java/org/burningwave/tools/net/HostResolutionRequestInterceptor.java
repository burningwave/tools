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
package org.burningwave.tools.net;

import static org.burningwave.core.assembler.StaticComponentContainer.Fields;
import static org.burningwave.core.assembler.StaticComponentContainer.Methods;
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
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

public class HostResolutionRequestInterceptor {
	public static final HostResolutionRequestInterceptor INSTANCE;
	private static final Function<HostResolutionRequestInterceptor, Object> proxySupplier;
	private static final Function<Collection<InetAddress>, Object> getAllAddressesForHostNameResultConverter;
	private static Object cacheOne;
	private static Object cacheTwo;

	Collection<HostResolver> resolvers;


	static {
		proxySupplier = Collection.class.isAssignableFrom(DefaultHostResolver.nameServiceFieldClass) ?
			HostResolutionRequestInterceptor::buildProxies:
			HostResolutionRequestInterceptor::buildProxy;
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
		INSTANCE = new HostResolutionRequestInterceptor();
	}

	private HostResolutionRequestInterceptor() {}

	public HostResolutionRequestInterceptor install(HostResolver... resolvers) {
		return install(-1, 250, resolvers);
	}

	public HostResolutionRequestInterceptor install(long timeout, long sleepingTime, HostResolver... resolvers) {
		this.resolvers = checkResolvers(resolvers);
		synchronized (DefaultHostResolver.nameServices) {
	        Fields.setStaticDirect(
	    		DefaultHostResolver.nameServiceField,
	    		proxySupplier.apply(this)
			);
		}
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

	public HostResolutionRequestInterceptor uninstall() {
        Object nameServices;
		if (Collection.class.isAssignableFrom(DefaultHostResolver.nameServiceFieldClass)) {
			nameServices = DefaultHostResolver.nameServices;
        } else {
        	nameServices = DefaultHostResolver.nameServices.iterator().next();
        }
		synchronized (DefaultHostResolver.nameServices) {
			Fields.setStaticDirect(DefaultHostResolver.nameServiceField, nameServices);
			clearCache();
		}
        return this;
	}

	public void clearCache() {
		synchronized (DefaultHostResolver.nameServices) {
			Methods.invokeDirect(cacheOne, "clear");
			Methods.invokeDirect(cacheTwo, "clear");
		}
	}

	private Collection<HostResolver> checkResolvers(HostResolver[] resolvers) {
		if (resolvers == null || resolvers.length < 1) {
			throw new IllegalArgumentException("Resolvers are required");
		}
		Collection<HostResolver> resolverList = new ArrayList<>();
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
		for (HostResolver resolver : resolvers) {
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

	public InvocationHandler buildOneToOneInvocationHandler(HostResolver resolver, Object nameService) {
		Function<Object[], Map<String, Object>> argumentMapBuilder =
			nameService != null ?
				arguments -> {
					Map<String, Object> argumentMap = buildArgumentMap(arguments);
					argumentMap.put("nameServices", Arrays.asList(nameService));
					return argumentMap;
				}:
				this::buildArgumentMap;

		return (proxy, method, arguments) -> {
			String methodName = method.getName();
			if (methodName.equals(DefaultHostResolver.getAllAddressesForHostNameMethod.getName())) {
				return getAllAddressesForHostNameResultConverter.apply(
					resolver.checkAndGetAllAddressesForHostName(argumentMapBuilder.apply(arguments))
				);
		    } else if (methodName.equals(DefaultHostResolver.getAllHostNamesForHostAddressMethod.getName())) {
		    	return resolver.checkAndGetAllHostNamesForHostAddress(argumentMapBuilder.apply(arguments)).iterator().next();
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
	    		for (HostResolver resolver : resolvers) {
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
		Map<String, Object> argumentMap = buildArgumentMap(args);
		for (HostResolver resolver : resolvers) {
			try {
				addresses.addAll(resolver.checkAndGetAllAddressesForHostName(argumentMap));
			} catch (UnknownHostException exc) {

			}
		}
		if (addresses.isEmpty()) {
			throw new UnknownHostException((String)args[0]);
		}
		return getAllAddressesForHostNameResultConverter.apply(addresses);
	}

	private Map<String, Object> buildArgumentMap(Object[] args) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("methodArguments", args);
		return arguments;
	}


	private Collection<String> getAllHostNamesForHostAddress(
		Object... args
	) throws Throwable {
		Collection<String> hostNames = new ArrayList<>();
		Map<String, Object> argumentMap = buildArgumentMap(args);
		for (HostResolver resolver : resolvers) {
			try {
				hostNames.addAll(resolver.checkAndGetAllHostNamesForHostAddress(argumentMap));
			} catch (UnknownHostException exc) {

			}
		}
		if (hostNames.isEmpty()) {
			throw new UnknownHostException(IPAddressUtil.INSTANCE.numericToTextFormat((byte[])args[0]));
		}
		return hostNames;
	}

}