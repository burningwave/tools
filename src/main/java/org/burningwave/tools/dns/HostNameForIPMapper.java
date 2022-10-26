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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
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
				fieldName.equals("nameService") || fieldName.equals("nameServices")  || fieldName.equals("resolver")
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

	public HostNameForIPMapper install(List<Map<String, Object>> hostAliases) {
		hostAliases = cloneHostAliasesConfig(hostAliases);
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

	private List<Map<String, Object>> cloneHostAliasesConfig(List<Map<String, Object>> hostAliases) {
		List<Map<String, Object>> replacement = new CopyOnWriteArrayList<>();
		for (Map<String, Object> addressesForIp : hostAliases) {
			Map<String, Object> addressesForIpClone = new ConcurrentHashMap<>();
			replacement.add(addressesForIpClone);
			for (Map.Entry<String, Object> valuesEntry : addressesForIp.entrySet()) {
				Object value = valuesEntry.getValue();
				if (value instanceof Collection) {
					value = new CopyOnWriteArrayList<>((Collection<?>)value);
				}
				addressesForIpClone.put(valuesEntry.getKey(), value);
			}
		}
		return replacement;
	}

	private Object buildProxy(List<Map<String, Object>> replacement, Class<?> nameServiceClass,
			Collection<Object> targets) {
		return Proxy.newProxyInstance(
			nameServiceClass.getClassLoader(),
			new Class<?>[] { nameServiceClass },
			buildInvocationHandler(replacement, targets)
		);
	}

	private InvocationHandler buildInvocationHandler(List<Map<String, Object>> replacement, Collection<Object> targets) {
		return (prx, method, args) -> {
    		String methodName = method.getName();
    		if (methodName.equals("lookupAllHostAddr") || methodName.equals("lookupByName")) {
    			return getAllAddressesForHostName(replacement, targets, method, args);
            } else if (method.getName().equals("getHostByAddr") || method.getName().equals("lookupByAddress")) {
            	return getAllAddressesForHostAddress(replacement, targets, method, args).iterator().next();
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
		List<Map<String, Object>> hostAliases,
		Collection<?> targets,
		Method method,
		Object... args
	) throws Throwable {
		String hostName = (String)args[0];
		Collection<InetAddress> addresses = new ArrayList<>();
		for (Map<String, Object> addressesForIp : hostAliases) {
			if (((Collection<String>)addressesForIp.get("hostnames")).contains(hostName)) {
				addresses.add(InetAddress.getByAddress(hostName, IPAddressUtil.INSTANCE.textToNumericFormat((String)addressesForIp.get("ip"))));
			}
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
		List<Map<String, Object>> hostAliases,
		Collection<?> nameServices,
		Method method,
		Object... args
	) throws Throwable {
		byte[] address = (byte[])args[0];
		Collection<String> hostNames = new ArrayList<>();
		String ipAddress = IPAddressUtil.INSTANCE.numericToTextFormat(address);
		for (Map<String, Object> addressesForIp : hostAliases) {
			if (ipAddress.equals(addressesForIp.get("ip"))) {
				for (String hostName : (Collection<String>)addressesForIp.get("hostnames")) {
					hostNames.add(hostName);
				}
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
