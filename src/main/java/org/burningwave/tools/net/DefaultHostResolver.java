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

import static org.burningwave.core.assembler.StaticComponentContainer.Driver;
import static org.burningwave.core.assembler.StaticComponentContainer.Fields;
import static org.burningwave.core.assembler.StaticComponentContainer.Methods;
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

import org.burningwave.core.classes.FieldCriteria;
import org.burningwave.core.classes.MethodCriteria;

import io.github.toolfactory.jvm.function.InitializeException;

@SuppressWarnings("unchecked")
public class DefaultHostResolver implements HostResolver {
	public final static HostResolver INSTANCE;
	static final Class<?> inetAddressClass;
	static final Field nameServiceField;
	static final Class<?> nameServiceFieldClass;
	static final Class<?> nameServiceClass;
	static final Method getAllAddressesForHostNameMethod;
	static final Method getAllHostNamesForHostAddressMethod;
	static final List<Object> nameServices;
	private static final Function<Object, Stream<InetAddress>> inetAddressSupplier;

	static {
		inetAddressClass = InetAddress.class;
		nameServiceField = Fields.findFirst(
			FieldCriteria.withoutConsideringParentClasses().name(fieldName ->
				fieldName.equals("nameService") || fieldName.equals("nameServices") || fieldName.equals("resolver")
			),
			inetAddressClass
		);
		nameServiceFieldClass = nameServiceField.getType();
		nameServiceClass = getNameServiceFieldClass(nameServiceField);
		getAllAddressesForHostNameMethod = Methods.findFirst(
			MethodCriteria.forEntireClassHierarchy().name(methodName ->
				methodName.equals("lookupAllHostAddr") || methodName.equals("lookupByName")
			),
			nameServiceClass
		);
		inetAddressSupplier = getAllAddressesForHostNameMethod.getReturnType().equals(InetAddress[].class) ?
			obj ->
				Stream.of((InetAddress[])obj) :
			obj ->
				(Stream<InetAddress>)obj;
		getAllHostNamesForHostAddressMethod = Methods.findFirst(
			MethodCriteria.forEntireClassHierarchy().name(methodName ->
				methodName.equals("getHostByAddr") || methodName.equals("lookupByAddress")
			),
			nameServiceClass
		);
		nameServices = getNameServices();
		if (nameServices.isEmpty()) {
			Driver.throwException(
				new InitializeException(
					Strings.compile(
						"No items found for field {}.{}",
						nameServiceField.getDeclaringClass(),
						nameServiceField.getName()
					)
				)
			);
		}
		INSTANCE = new DefaultHostResolver();
	}

	public DefaultHostResolver() {}

	private static Class<?> getNameServiceFieldClass(Field nameServiceField) {
        if (Collection.class.isAssignableFrom(nameServiceField.getType())) {
        	ParameterizedType stringListType = (ParameterizedType) nameServiceField.getGenericType();
        	return (Class<?>) stringListType.getActualTypeArguments()[0];
        } else {
        	return nameServiceField.getType();
        }
	}

	private static List<Object> getNameServices() {
		try {
			//Initializing the nameServiceField
			InetAddress.getAllByName("localhost");
		} catch (UnknownHostException ecc) {}
		List<Object> nameServices = new CopyOnWriteArrayList<>();
        if (Collection.class.isAssignableFrom(nameServiceFieldClass)) {
        	nameServices.addAll(Fields.getStaticDirect(nameServiceField));
        } else {
        	Object nameService = Fields.getStaticDirect(nameServiceField);
        	if (nameService != null) {
        		nameServices.add(nameService);
        	}
        }
        return nameServices;
	}

	@Override
	public Collection<InetAddress> getAllAddressesForHostName(Map<String, Object> argumentsMap) {
		Object[] arguments = getMethodArguments(argumentsMap);
		List<Object> nameServices = (List<Object>)argumentsMap.get("nameServices");
		if (nameServices == null) {
			nameServices = DefaultHostResolver.nameServices;
		}
		Collection<InetAddress> addresses = new ArrayList<>();
		for (Object nameService : nameServices) {
			if (nameService != null) {
				try {
					Object inetAddresses = Methods.invokeDirect(nameService, getAllAddressesForHostNameMethod.getName(), arguments);
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
		return addresses;
	}

	@Override
	public Collection<String> getAllHostNamesForHostAddress(Map<String, Object> argumentsMap) {
		Object[] arguments = getMethodArguments(argumentsMap);
		List<Object> nameServices = (List<Object>)argumentsMap.get("nameServices");
		if (nameServices == null) {
			nameServices = DefaultHostResolver.nameServices;
		}
		Collection<String> hostNames = new ArrayList<>();
		for (Object nameService : nameServices) {
			if (nameService != null) {
				try {
					String hostName = Methods.invokeDirect(nameService, getAllHostNamesForHostAddressMethod.getName(), arguments);
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
		return hostNames;
	}

	@Override
	public Object handle(Method method, Object... arguments) throws Throwable {
		for (Object nameService : nameServices) {
			if (nameService != null) {
				Object toRet = Methods.invokeDirect(nameService, method.getName(), arguments);
				if (toRet != null) {
					return toRet;
				}
			}
		}
		return null;
	}
}
