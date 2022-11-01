package org.burningwave.tools.dns;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Map;

public interface HostResolver {

	public default Collection<InetAddress> checkAndGetAllAddressesForHostName(Map<String, Object> argumentsMap) throws UnknownHostException {
		String hostName = (String)getMethodArguments(argumentsMap)[0];
		Collection<InetAddress> addresses = getAllAddressesForHostName(argumentsMap);
		if (addresses.isEmpty()) {
			throw new UnknownHostException(hostName);
		}
		return addresses;
	}

	public default  Collection<String> checkAndGetAllHostNamesForHostAddress(Map<String, Object> argumentsMap) throws UnknownHostException {
		byte[] address = (byte[])getMethodArguments(argumentsMap)[0];
		Collection<String> hostNames = getAllHostNamesForHostAddress(argumentsMap);
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