package org.burningwave.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.burningwave.tools.dns.DefaultHostsResolver;
import org.burningwave.tools.dns.HostsResolverService;
import org.burningwave.tools.dns.IPAddressUtil;
import org.burningwave.tools.dns.MappedHostsResolver;
import org.junit.jupiter.api.Test;

public class HostsResolverServiceTest extends BaseTest {

	@Test
	public void resolveTestOne() throws UnknownHostException {
		testDoesNotThrow(() -> {
			List<Map<String, Object>> hostAliases = new ArrayList<>();
			Map<String, Object> hostNamesForIp = new LinkedHashMap<>();
			hostAliases.add(hostNamesForIp);
			hostNamesForIp.put("ip", "123.123.123.123");
			hostNamesForIp.put("hostnames", Arrays.asList("hello.world.one", "hello.world.two"));
			HostsResolverService.INSTANCE.install(
				new MappedHostsResolver(() -> hostAliases),
				DefaultHostsResolver.INSTANCE
			);
			InetAddress inetAddress = InetAddress.getByName("hello.world.one");
			assertNotNull(inetAddress);
			assertTrue("123.123.123.123".equals(inetAddress.getHostAddress()));
			inetAddress = InetAddress.getByName("hello.world.two");
			assertNotNull(inetAddress);
			assertTrue("123.123.123.123".equals(inetAddress.getHostAddress()));
			inetAddress = InetAddress.getByName("localhost");
			assertNotNull(inetAddress);
			assertTrue("127.0.0.1".equals(inetAddress.getHostAddress()));
			inetAddress = InetAddress.getByAddress(IPAddressUtil.INSTANCE.textToNumericFormat("127.0.0.1"));
			assertNotNull(inetAddress);
		});
	}

}
