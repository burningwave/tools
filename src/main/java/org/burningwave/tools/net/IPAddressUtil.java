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

import static org.burningwave.core.assembler.StaticComponentContainer.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class IPAddressUtil {
	public static final IPAddressUtil INSTANCE;

    private static final int IPV4_SIZE = 4;
    private static final int IPV6_SIZE = 16;
    private static final int INT_16_SIZE = 2;

    static {
    	INSTANCE = new IPAddressUtil();
    }

    private IPAddressUtil() {}

    public byte[] textToNumericFormat(String ip) {
    	byte[] address = textToNumericFormatV4(ip);
    	if (address != null) {
    		return address;
    	}
    	return textToNumericFormatV6(ip);
    }

    public String numericToTextFormat(byte[] address) {
    	if (address.length == IPV4_SIZE) {
    		return numericToTextFormatV4(address);
    	} else if (address.length == IPV6_SIZE) {
    		return numericToTextFormatV6(address);
    	}
    	throw new IllegalArgumentException(Strings.compile("[{}] is not a valid ip address", String.join(",", convertToList(address).stream().map(value -> value.toString()).collect(Collectors.toList()))));
    }

    String numericToTextFormatV4(byte[] src) {
    	int i = src.length;
    	StringBuilder ipAddress = new StringBuilder();
        for (byte raw : src) {
            ipAddress.append(raw & 0xFF);
            if (--i > 0) {
                ipAddress.append(".");
            }
        }
        return ipAddress.toString();
    }

    String numericToTextFormatV6(byte[] src) {
		StringBuilder sb = new StringBuilder(39);
	    for (int i = 0; i < (IPV6_SIZE / INT_16_SIZE); i++) {
	        sb.append(Integer.toHexString(((src[i<<1]<<8) & 0xff00)
	                                      | (src[(i<<1)+1] & 0xff)));
	        if (i < (IPV6_SIZE / INT_16_SIZE) -1 ) {
	           sb.append(":");
	        }
	    }
	    return sb.toString();
    }

    byte[] textToNumericFormatV6(String src) {
        if (src.length() < 2) {
            return null;
        }

        int colonp;
        char ch;
        boolean sawXDigit;
        int val;
        char[] srcb = src.toCharArray();
        byte[] dst = new byte[IPV6_SIZE];

        int srcbLength = srcb.length;
        int pc = src.indexOf("%");
        if (pc == srcbLength - 1) {
            return null;
        }
        if (pc != -1) {
            srcbLength = pc;
        }
        colonp = -1;
        int i = 0;
        int j = 0;
        if (srcb[i] == ':' && srcb[++i] != ':') {
        	return null;
        }
        int curtok = i;
        sawXDigit = false;
        val = 0;
        while (i < srcbLength) {
            ch = srcb[i++];
            int chval = Character.digit(ch, 16);
            if (chval != -1) {
                val <<= 4;
                val |= chval;
                if (val > 0xffff) {
                    return null;
                }
                sawXDigit = true;
                continue;
            }
            if (ch == ':') {
                curtok = i;
                if (!sawXDigit) {
                    if (colonp != -1) {
                        return null;
                    }
                    colonp = j;
                    continue;
                } else if (i == srcbLength) {
                    return null;
                }
                if (j + INT_16_SIZE > IPV6_SIZE) {
                    return null;
                }
                dst[j++] = (byte) ((val >> 8) & 0xff);
                dst[j++] = (byte) (val & 0xff);
                sawXDigit = false;
                val = 0;
                continue;
            }
            if (ch == '.' && ((j + IPV4_SIZE) <= IPV6_SIZE)) {
                String ia4 = src.substring(curtok, srcbLength);
                int dotCount = 0;
                int index = 0;
                while ((index = ia4.indexOf('.', index)) != -1) {
                    dotCount++;
                    index++;
                }
                if (dotCount != 3) {
                    return null;
                }
                byte[] v4addr = textToNumericFormatV4(ia4);
                if (v4addr == null) {
                    return null;
                }
                for (int k = 0; k < IPV4_SIZE; k++) {
                    dst[j++] = v4addr[k];
                }
                sawXDigit = false;
                break;
            }
            return null;
        }
        if (sawXDigit) {
            if (j + INT_16_SIZE > IPV6_SIZE) {
                return null;
            }
            dst[j++] = (byte) ((val >> 8) & 0xff);
            dst[j++] = (byte) (val & 0xff);
        }

        if (colonp != -1) {
            int n = j - colonp;
            if (j == IPV6_SIZE) {
                return null;
            }
            for (i = 1; i <= n; i++) {
                dst[IPV6_SIZE - i] = dst[colonp + n - i];
                dst[colonp + n - i] = 0;
            }
            j = IPV6_SIZE;
        }
        if (j != IPV6_SIZE) {
            return null;
        }
        byte[] newdst = convertFromIPv4MappedAddress(dst);
        if (newdst != null) {
            return newdst;
        } else {
            return dst;
        }
    }

    byte[] textToNumericFormatV4(String src) {
        if (src.length() == 0) {
            return null;
        }

        byte[] res = new byte[IPV4_SIZE];
        String[] s = src.split("\\.", -1);
        long val;
        try {
            switch (s.length) {
            case 1:
                val = Long.parseLong(s[0]);
                if (val < 0 || val > 0xffffffffL)
                    return null;
                res[0] = (byte) ((val >> 24) & 0xff);
                res[1] = (byte) (((val & 0xffffff) >> 16) & 0xff);
                res[2] = (byte) (((val & 0xffff) >> 8) & 0xff);
                res[3] = (byte) (val & 0xff);
                break;
            case 2:
                val = Integer.parseInt(s[0]);
                if (val < 0 || val > 0xff)
                    return null;
                res[0] = (byte) (val & 0xff);
                val = Integer.parseInt(s[1]);
                if (val < 0 || val > 0xffffff)
                    return null;
                res[1] = (byte) ((val >> 16) & 0xff);
                res[2] = (byte) (((val & 0xffff) >> 8) & 0xff);
                res[3] = (byte) (val & 0xff);
                break;
            case 3:
                for (int i = 0; i < 2; i++) {
                    val = Integer.parseInt(s[i]);
                    if (val < 0 || val > 0xff)
                        return null;
                    res[i] = (byte) (val & 0xff);
                }
                val = Integer.parseInt(s[2]);
                if (val < 0 || val > 0xffff)
                    return null;
                res[2] = (byte) ((val >> 8) & 0xff);
                res[3] = (byte) (val & 0xff);
                break;
            case 4:
                for (int i = 0; i < 4; i++) {
                    val = Integer.parseInt(s[i]);
                    if (val < 0 || val > 0xff)
                        return null;
                    res[i] = (byte) (val & 0xff);
                }
                break;
            default:
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return res;
    }

    byte[] convertFromIPv4MappedAddress(byte[] addr) {
        if (isIPv4MappedAddress(addr)) {
            byte[] newAddr = new byte[IPV4_SIZE];
            System.arraycopy(addr, 12, newAddr, 0, IPV4_SIZE);
            return newAddr;
        }
        return null;
    }

    boolean isIPv4MappedAddress(byte[] addr) {
        if (addr.length < IPV6_SIZE) {
            return false;
        }
        return ((addr[0] == 0x00) && (addr[1] == 0x00) && (addr[2] == 0x00) && (addr[3] == 0x00) && (addr[4] == 0x00)
                && (addr[5] == 0x00) && (addr[6] == 0x00) && (addr[7] == 0x00) && (addr[8] == 0x00)
                && (addr[9] == 0x00) && (addr[10] == (byte) 0xff) && (addr[11] == (byte) 0xff));
    }

    private List<Byte> convertToList(byte[] bytes) {
        final List<Byte> list = new ArrayList<>();
        for (byte b : bytes) {
            list.add(b);
        }
        return list;
    }

}