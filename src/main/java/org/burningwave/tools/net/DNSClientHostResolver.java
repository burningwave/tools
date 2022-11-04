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
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.function.Supplier;

import org.burningwave.core.function.ThrowingBiFunction;


@SuppressWarnings("unchecked")
public class DNSClientHostResolver implements HostResolver {
	public final static int DEFAULT_PORT;

	private static final String IPV6_DOMAIN;
	private static final String IPV4_DOMAIN;
	private static final short RECORD_TYPE_A;
	private static final short RECORD_TYPE_PTR;
	private static final short RECORD_TYPE_AAAA;

	public static final ThrowingBiFunction<DNSClientHostResolver, String, byte[], IOException> IPV4_RETRIEVER;
	public static final ThrowingBiFunction<DNSClientHostResolver, String, byte[], IOException> IPV6_RETRIEVER;

	static {
		DEFAULT_PORT = 53;
		IPV6_DOMAIN = "ip6.arpa.";
		IPV4_DOMAIN = "in-addr.arpa.";
		RECORD_TYPE_A = 1;
		RECORD_TYPE_PTR = 12;
		RECORD_TYPE_AAAA = 28;
		IPV4_RETRIEVER = (dNSServerHostResolver, hostName) ->
			dNSServerHostResolver.sendRequest(hostName, RECORD_TYPE_A);
		IPV6_RETRIEVER = (dNSServerHostResolver, hostName) ->
			dNSServerHostResolver.sendRequest(hostName, RECORD_TYPE_AAAA);
	}

	private ThrowingBiFunction<DNSClientHostResolver, String, byte[], IOException>[] resolveHostForNameRequestSenders;

	private static Random requestIdGenerator;

	static {
		requestIdGenerator = new Random();
	}

	private InetAddress dNSServerIP;
	private int dNSServerPort;

	public DNSClientHostResolver(String dNSServerIP) {
		this(dNSServerIP, DEFAULT_PORT, IPV4_RETRIEVER, IPV6_RETRIEVER);
	}

	public DNSClientHostResolver(String dNSServerIP, int dNSServerPort) {
		this(dNSServerIP, dNSServerPort, IPV4_RETRIEVER, IPV6_RETRIEVER);
	}

	public DNSClientHostResolver(String dNSServerIP, ThrowingBiFunction<DNSClientHostResolver, String, byte[], IOException>... resolveHostForNameRequestSenders) {
		this(dNSServerIP, DEFAULT_PORT, resolveHostForNameRequestSenders);
	}

	public DNSClientHostResolver(String dNSServerIP, int dNSServerPort, ThrowingBiFunction<DNSClientHostResolver, String, byte[], IOException>... resolveHostForNameRequestSenders) {
		try {
			this.dNSServerIP = InetAddress.getByName(dNSServerIP);
		} catch (UnknownHostException exc) {
			Driver.throwException(exc);
		}
		this.dNSServerPort = dNSServerPort;
		this.resolveHostForNameRequestSenders = resolveHostForNameRequestSenders != null && resolveHostForNameRequestSenders.length > 0 ?
			resolveHostForNameRequestSenders :
			new ThrowingBiFunction[] {IPV4_RETRIEVER, IPV6_RETRIEVER};
	}

	public static Collection<DNSClientHostResolver> newInstances(Supplier<Collection<Map<String, Object>>> configuration) {
		Collection<DNSClientHostResolver> dNSClientHostResolvers = new ArrayList<>();
		configuration.get().stream().forEach(serverMap ->
			dNSClientHostResolvers.add(
	            new DNSClientHostResolver(
	                (String)serverMap.get("ip"),
	                (Integer)serverMap.getOrDefault("port", DEFAULT_PORT),
					((List<String>)serverMap.get("ipTypeToSearchFor")).stream()
					.map(ipType -> Fields.getStaticDirect(DNSClientHostResolver.class, Strings.compile("{}_RETRIEVER", ipType.toUpperCase())))
					.map(ThrowingBiFunction.class::cast).toArray(size -> new ThrowingBiFunction[size])
	            )
	        )
	    );
		return dNSClientHostResolvers;
	}

	@Override
	public Collection<InetAddress> getAllAddressesForHostName(Map<String, Object> argumentMap) {
		return resolveHostForName((String)getMethodArguments(argumentMap)[0]);
	}

	public Collection<InetAddress> resolveHostForName(String hostName) {
		try {
			Collection<InetAddress> addresses = new ArrayList<>();
			byte[][] responses = new byte[resolveHostForNameRequestSenders.length][];
			for (int i = 0; i < resolveHostForNameRequestSenders.length; i++) {
				responses[i] = resolveHostForNameRequestSenders[i].apply(this, hostName);
			}
	        Map<byte[], String> iPToDomainMap = new LinkedHashMap<>();
	        for (byte[] response : responses) {
	        	iPToDomainMap.putAll(parseResponse(response));
	        }
	        for (Entry<byte[], String> iPToDomain : iPToDomainMap.entrySet()) {
	        	addresses.add(InetAddress.getByAddress(iPToDomain.getValue(), iPToDomain.getKey()));
	        }
	        return addresses;
		} catch (Throwable exc) {
			return Driver.throwException(exc);
		}
	}

	private byte[] sendRequest(String hostName, int recordType) throws IOException {
		short ID = (short)requestIdGenerator.nextInt(32767);
		try (
			ByteArrayOutputStream requestContentStream = new ByteArrayOutputStream();
			DataOutputStream requestWrapper = new DataOutputStream(requestContentStream);
		) {
			short requestFlags = Short.parseShort("0000000100000000", 2);
			ByteBuffer byteBuffer = ByteBuffer.allocate(2).putShort(requestFlags);
			byte[] flagsByteArray = byteBuffer.array();

			short QDCOUNT = 1;
			short ANCOUNT = 0;
			short NSCOUNT = 0;
			short ARCOUNT = 0;

			requestWrapper.writeShort(ID);
			requestWrapper.write(flagsByteArray);
			requestWrapper.writeShort(QDCOUNT);
			requestWrapper.writeShort(ANCOUNT);
			requestWrapper.writeShort(NSCOUNT);
			requestWrapper.writeShort(ARCOUNT);

			String[] domainParts = hostName.split("\\.");

			for (int i = 0; i < domainParts.length; i++) {
			    byte[] domainBytes = domainParts[i].getBytes(StandardCharsets.UTF_8);
			    requestWrapper.writeByte(domainBytes.length);
			    requestWrapper.write(domainBytes);
			}
			requestWrapper.writeByte(0);
			requestWrapper.writeShort(recordType);
			requestWrapper.writeShort(1);
			byte[] dnsFrame = requestContentStream.toByteArray();
			DatagramPacket packet;
			byte[] response;
			try (DatagramSocket socket = new DatagramSocket()){
			    DatagramPacket dnsReqPacket = new DatagramPacket(dnsFrame, dnsFrame.length, dNSServerIP, dNSServerPort);
			    socket.send(dnsReqPacket);
			    response = new byte[1024];
			    packet = new DatagramPacket(response, response.length);
			    socket.receive(packet);
			}
			return response;
		}
	}

	private Map<byte[], String> parseResponse(byte[] responseContent) throws IOException {
		try (InputStream responseContentStream = new ByteArrayInputStream(responseContent);
			DataInputStream responseWrapper = new DataInputStream(responseContentStream)
		) {
			responseWrapper.skip(6);
			short ANCOUNT = responseWrapper.readShort();
			responseWrapper.skip(4);
			int recLen;
			while ((recLen = responseWrapper.readByte()) > 0) {
			    byte[] record = new byte[recLen];
			    for (int i = 0; i < recLen; i++) {
			        record[i] = responseWrapper.readByte();
			    }
			}
			responseWrapper.skip(4);
			byte firstBytes = responseWrapper.readByte();
			int firstTwoBits = (firstBytes & 0b11000000) >>> 6;
			Map<byte[], String> valueToDomainMap = new LinkedHashMap<>();
			try (ByteArrayOutputStream label = new ByteArrayOutputStream();) {
				for(int i = 0; i < ANCOUNT; i++) {
				    if(firstTwoBits == 3) {
				        byte currentByte = responseWrapper.readByte();
				        boolean stop = false;
				        byte[] newArray = Arrays.copyOfRange(responseContent, currentByte, responseContent.length);
				        try (InputStream responseSectionContentStream = new ByteArrayInputStream(newArray);
			        		DataInputStream responseSectionWrapper = new DataInputStream(responseSectionContentStream);
		        		) {
					        List<Byte> RDATA = new ArrayList<>();
					        List<String> DOMAINS = new ArrayList<>();
					        List<byte[]> labels = new ArrayList<>();
					        while(!stop) {
					            byte nextByte = responseSectionWrapper.readByte();
					            if(nextByte != 0) {
					                byte[] currentLabel = new byte[nextByte];
					                for(int j = 0; j < nextByte; j++) {
					                    currentLabel[j] = responseSectionWrapper.readByte();
					                }
					                label.write(currentLabel);
					                labels.add(currentLabel);
					            } else {
					                stop = true;
					                responseWrapper.skip(8);
					                int RDLENGTH = responseWrapper.readShort();
					                for(int s = 0; s < RDLENGTH; s++) {
					                	RDATA.add(responseWrapper.readByte());
					                }
					            }
					            DOMAINS.add(new String( label.toByteArray(), StandardCharsets.UTF_8));
					            label.reset();
					        }

					        StringBuilder domainSb = new StringBuilder();
					        byte[] address = new byte[RDATA.size()];
					        for(int j = 0; j < RDATA.size(); j++) {
					        	address[j] = RDATA.get(j);
					        }

					        for(String domainPart:DOMAINS) {
					            if(!domainPart.equals("")) {
					                domainSb.append(domainPart).append(".");
					            }
					        }
					        String domainFinal = domainSb.toString();
					        valueToDomainMap.put(
				        		address,
				        		domainFinal.substring(0, domainFinal.length()-1)
			        		);
				        }
				    }
				    firstBytes = responseWrapper.readByte();
				    firstTwoBits = (firstBytes & 0b11000000) >>> 6;
				}
			}
			return valueToDomainMap;
		}
	}


	@Override
	public Collection<String> getAllHostNamesForHostAddress(Map<String, Object> argumentMap) {
		return resolveHostForAddress((byte[])getMethodArguments(argumentMap)[0]);
	}

	public Collection<String> resolveHostForAddress(String iPAddress) {
		return resolveHostForAddress(IPAddressUtil.INSTANCE.textToNumericFormat(iPAddress));
	}

	public Collection<String> resolveHostForAddress(byte[] iPAddressAsBytes) {
		Map<byte[], String> iPToDomainMap = new LinkedHashMap<>();
		try {
			iPToDomainMap.putAll(parseResponse(sendRequest(iPAddressAsBytesToReversedString(iPAddressAsBytes), RECORD_TYPE_PTR)));
		} catch (IOException exc) {
			Driver.throwException(exc);
		}
		ArrayList<String> domains = new ArrayList<>();
		iPToDomainMap.forEach((key, value) -> {
			domains.add(hostNameAsBytesToString(key));
		});
		return domains;
	}

	private String iPAddressAsBytesToReversedString(byte[] iPAddressAsByte) {
		if (iPAddressAsByte.length != 4 && iPAddressAsByte.length != 16) {
			throw new IllegalArgumentException("array must contain 4 or 16 elements");
		}

		StringBuilder sb = new StringBuilder();
		if (iPAddressAsByte.length == 4) {
			for (int i = iPAddressAsByte.length - 1; i >= 0; i--) {
				sb.append(iPAddressAsByte[i] & 0xFF);
				if (i > 0) {
					sb.append(".");
				}
			}
		} else {
			int[] pieces = new int[2];
			for (int i = iPAddressAsByte.length - 1; i >= 0; i--) {
				pieces[0] = (iPAddressAsByte[i] & 0xFF) >> 4;
				pieces[1] = iPAddressAsByte[i] & 0xF;
				for (int j = pieces.length - 1; j >= 0; j--) {
					sb.append(Integer.toHexString(pieces[j]));
					if (i > 0 || j > 0) {
						sb.append(".");
					}
				}
			}
		}
		if (iPAddressAsByte.length == 4) {
			return sb.toString() + "." + IPV4_DOMAIN;
		} else {
			return sb.toString() + "." + IPV6_DOMAIN;
		}
	}

	private String hostNameAsBytesToString(byte[] hostNameAsBytes) {
		StringBuilder sb = new StringBuilder();
		int position = 0;
		while (true) {
			int len = hostNameAsBytes[position++];
			for (int i = position; i < position + len; i++) {
				int b = hostNameAsBytes[i] & 0xFF;
				if (b <= 0x20 || b >= 0x7f) {
					sb.append('\\');
					if (b < 10) {
						sb.append("00");
					} else if (b < 100) {
						sb.append('0');
					}
					sb.append(b);
				} else if (b == '"' || b == '(' || b == ')' || b == '.' || b == ';' || b == '\\' || b == '@' || b == '$') {
					sb.append('\\');
					sb.append((char) b);
				} else {
					sb.append((char) b);
				}
			}
			position += len;
			if (position < hostNameAsBytes.length && hostNameAsBytes[position] != 0) {
				sb.append(".");
			} else {
				break;
			}
		}
		return sb.toString();
	}
}