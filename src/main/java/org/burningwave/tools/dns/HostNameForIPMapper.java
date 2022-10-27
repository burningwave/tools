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

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.burningwave.core.classes.FieldCriteria;

@SuppressWarnings("unchecked")
public class HostNameForIPMapper {
	public static final HostNameForIPMapper INSTANCE;
	private static final Field nameServiceField;
	private static final Class<?> nameServiceFieldClass;
	private static final Class<?> nameServiceClass;
	private static final Class<?> inetAddressClass;
	private static final Collection<Object> nameServices;

	static {
		inetAddressClass = InetAddress.class;
		nameServiceField = Fields.findFirst(
			FieldCriteria.withoutConsideringParentClasses().name(fieldName ->
				fieldName.equals("nameService") || fieldName.equals("nameServices") || fieldName.equals("resolver")
			),
			inetAddressClass
		);
		Fields.setAccessible(nameServiceField, true);
        nameServiceFieldClass = nameServiceField.getType();
        nameServiceClass = getNameServiceFieldClass(nameServiceField);
        nameServices = getNameServices();
		INSTANCE = new HostNameForIPMapper();
	}

	private HostNameForIPMapper() {}

	private static Collection<Object> getNameServices() {
		if (nameServiceField.getName().equals("resolver")) {
			Methods.invokeStaticDirect(inetAddressClass, "resolver");
		}
		Collection<Object> nameServices = new CopyOnWriteArrayList<>();
        if (Collection.class.isAssignableFrom(nameServiceFieldClass)) {
        	nameServices.addAll(Fields.getStatic(nameServiceField));
        } else {
        	nameServices.add(Fields.getStatic(nameServiceField));
        }
        return nameServices;
	}

	private static Class<?> getNameServiceFieldClass(Field nameServiceField) {
        if (Collection.class.isAssignableFrom(nameServiceField.getType())) {
        	ParameterizedType stringListType = (ParameterizedType) nameServiceField.getGenericType();
        	return (Class<?>) stringListType.getActualTypeArguments()[0];
        } else {
        	return nameServiceField.getType();
        }
	}

	@SafeVarargs
	public final HostNameForIPMapper install(Supplier<List<Map<String, Object>>>... hostAliasesYAMLFormatSuppliers) {
		return install(Arrays.asList(hostAliasesYAMLFormatSuppliers));
	}

	public HostNameForIPMapper install(Collection<Supplier<List<Map<String, Object>>>> hostAliasesYAMLFormatSuppliers) {
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
		return install(hostAliases);
	}

	public HostNameForIPMapper install(Map<String, String> hostAliases) {
		hostAliases = new LinkedHashMap<>(hostAliases);
        Object proxy;
        if (Collection.class.isAssignableFrom(nameServiceFieldClass)) {
        	proxy = Arrays.asList(
    			buildProxy(hostAliases, nameServiceClass, nameServices)
			);
        } else {
        	proxy = buildProxy(hostAliases, nameServiceClass, nameServices);
        }
        Fields.setStaticDirect(inetAddressClass, nameServiceField.getName(), proxy);
        return this;
    }

	private Object buildProxy(Map<String, String> hostAliases, Class<?> nameServiceClass,
			Collection<Object> targets) {
		return Proxy.newProxyInstance(
			nameServiceClass.getClassLoader(),
			new Class<?>[] { nameServiceClass },
			buildInvocationHandler(hostAliases, targets)
		);
	}

	private InvocationHandler buildInvocationHandler(Map<String, String> hostAliases, Collection<Object> targets) {
		return (prx, method, args) -> {
    		String methodName = method.getName();
    		if (methodName.equals("lookupAllHostAddr") || methodName.equals("lookupByName")) {
    			return getAllAddressesForHostName(hostAliases, targets, method, args);
            } else if (method.getName().equals("getHostByAddr") || method.getName().equals("lookupByAddress")) {
            	return getAllAddressesForHostAddress(hostAliases, targets, method, args).iterator().next();
            }
    		Object toRet = null;
    		for (Object object : targets) {
    			if (object != null) {
    				toRet = MethodHandles.lookup().unreflect(method).bindTo(object).invokeWithArguments(args);
    				if (toRet != null) {
    					return toRet;
    				}
    			}
    		}
    		return null;
        };
	}

	private Object getAllAddressesForHostName(
		Map<String, String> hostAliases,
		Collection<?> targets,
		Method method,
		Object... args
	) throws Throwable {
		String hostName = (String)args[0];
		Collection<InetAddress> addresses = new ArrayList<>();
		String iPAddress = hostAliases.get(hostName);
		if (iPAddress != null) {
			addresses.add(InetAddress.getByAddress(hostName, IPAddressUtil.INSTANCE.textToNumericFormat(iPAddress)));
		}
		Function<Object, Stream<InetAddress>> inetAddressSupplier = method.getReturnType().equals(InetAddress[].class) ?
			obj ->
				Stream.of((InetAddress[])obj) :
			obj ->
				(Stream<InetAddress>)obj;
		for (Object nameService : targets) {
			if (nameService != null) {
				try {
					Object inetAddresses = Methods.invokeDirect(nameService, method.getName(), args);
					if (inetAddresses != null) {
						inetAddressSupplier.apply(inetAddresses).forEach(addresses::add);
					}
				} catch (Throwable exc) {
					if (!(exc instanceof UnknownHostException)) {
						throw exc;
					}
				}
			}
		}
		if (addresses.isEmpty()) {
			throw new UnknownHostException(hostName);
		}
		return method.getReturnType().equals(InetAddress[].class) ?
			addresses.toArray(new InetAddress[addresses.size()]) :
			addresses.stream();
	}


	private Collection<String> getAllAddressesForHostAddress(
		Map<String, String> hostAliases,
		Collection<?> nameServices,
		Method method,
		Object... args
	) throws Throwable {
		byte[] address = (byte[])args[0];
		Collection<String> hostNames = new ArrayList<>();
		String iPAddress = IPAddressUtil.INSTANCE.numericToTextFormat(address);
		for (Map.Entry<String, String> addressForIp : hostAliases.entrySet()) {
			if (addressForIp.getValue().equals(iPAddress)) {
				hostNames.add(addressForIp.getKey());
			}
		}
		for (Object nameService : nameServices) {
			if (nameService != null) {
				try {
					String hostName = Methods.invokeDirect(nameService, method.getName(), args);
					if (hostName != null) {
						hostNames.add(hostName);
					}
				} catch (Throwable exc) {
					if (!(exc instanceof UnknownHostException)) {
						throw exc;
					}
				}
			}
		}
		if (hostNames.isEmpty()) {
			throw new UnknownHostException(IPAddressUtil.INSTANCE.numericToTextFormat(address));
		}
		return hostNames;
	}

}
