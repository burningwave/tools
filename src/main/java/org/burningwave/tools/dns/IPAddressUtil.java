package org.burningwave.tools.dns;

public class IPAddressUtil {
	public static final IPAddressUtil INSTANCE;

    private static final int INADDR4SZ = 4;
    private static final int INADDR16SZ = 16;
    private static final int INT16SZ = 2;

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
    	if (isIPv4MappedAddress(address)) {
    		return numericToTextFormatV4(address);
    	}
    	return numericToTextFormatV6(address);
    }

    private String numericToTextFormatV4(byte[] src) {
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

    private String numericToTextFormatV6(byte[] src) {
		StringBuilder sb = new StringBuilder(39);
	    for (int i = 0; i < (INADDR16SZ / INT16SZ); i++) {
	        sb.append(Integer.toHexString(((src[i<<1]<<8) & 0xff00)
	                                      | (src[(i<<1)+1] & 0xff)));
	        if (i < (INADDR16SZ / INT16SZ) -1 ) {
	           sb.append(":");
	        }
	    }
	    return sb.toString();
    }

    private byte[] textToNumericFormatV6(String src) {
        // Shortest valid string is "::", hence at least 2 chars
        if (src.length() < 2) {
            return null;
        }

        int colonp;
        char ch;
        boolean sawXDigit;
        int val;
        char[] srcb = src.toCharArray();
        byte[] dst = new byte[INADDR16SZ];

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
        /* Leading :: requires some special handling. */
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
                if (j + INT16SZ > INADDR16SZ) {
                    return null;
                }
                dst[j++] = (byte) ((val >> 8) & 0xff);
                dst[j++] = (byte) (val & 0xff);
                sawXDigit = false;
                val = 0;
                continue;
            }
            if (ch == '.' && ((j + INADDR4SZ) <= INADDR16SZ)) {
                String ia4 = src.substring(curtok, srcbLength);
                /* check this IPv4 address has 3 dots, ie. A.B.C.D */
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
                for (int k = 0; k < INADDR4SZ; k++) {
                    dst[j++] = v4addr[k];
                }
                sawXDigit = false;
                break; /* '\0' was seen by inet_pton4(). */
            }
            return null;
        }
        if (sawXDigit) {
            if (j + INT16SZ > INADDR16SZ) {
                return null;
            }
            dst[j++] = (byte) ((val >> 8) & 0xff);
            dst[j++] = (byte) (val & 0xff);
        }

        if (colonp != -1) {
            int n = j - colonp;
            if (j == INADDR16SZ) {
                return null;
            }
            for (i = 1; i <= n; i++) {
                dst[INADDR16SZ - i] = dst[colonp + n - i];
                dst[colonp + n - i] = 0;
            }
            j = INADDR16SZ;
        }
        if (j != INADDR16SZ) {
            return null;
        }
        byte[] newdst = convertFromIPv4MappedAddress(dst);
        if (newdst != null) {
            return newdst;
        } else {
            return dst;
        }
    }

    private byte[] textToNumericFormatV4(String src) {
        if (src.length() == 0) {
            return null;
        }

        byte[] res = new byte[INADDR4SZ];
        String[] s = src.split("\\.", -1);
        long val;
        try {
            switch (s.length) {
            case 1:
                /*
                 * When only one part is given, the value is stored directly in
                 * the network address without any byte rearrangement.
                 */

                val = Long.parseLong(s[0]);
                if (val < 0 || val > 0xffffffffL)
                    return null;
                res[0] = (byte) ((val >> 24) & 0xff);
                res[1] = (byte) (((val & 0xffffff) >> 16) & 0xff);
                res[2] = (byte) (((val & 0xffff) >> 8) & 0xff);
                res[3] = (byte) (val & 0xff);
                break;
            case 2:
                /*
                 * When a two part address is supplied, the last part is
                 * interpreted as a 24-bit quantity and placed in the right
                 * most three bytes of the network address. This makes the
                 * two part address format convenient for specifying Class A
                 * network addresses as net.host.
                 */

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
                /*
                 * When a three part address is specified, the last part is
                 * interpreted as a 16-bit quantity and placed in the right
                 * most two bytes of the network address. This makes the
                 * three part address format convenient for specifying
                 * Class B net- work addresses as 128.net.host.
                 */
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
                /*
                 * When four parts are specified, each is interpreted as a
                 * byte of data and assigned, from left to right, to the
                 * four bytes of an IPv4 address.
                 */
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

    public byte[] convertFromIPv4MappedAddress(byte[] addr) {
        if (isIPv4MappedAddress(addr)) {
            byte[] newAddr = new byte[INADDR4SZ];
            System.arraycopy(addr, 12, newAddr, 0, INADDR4SZ);
            return newAddr;
        }
        return null;
    }

    /**
     * Utility routine to check if the InetAddress is an
     * IPv4 mapped IPv6 address.
     *
     * @return a <code>boolean</code> indicating if the InetAddress is
     * an IPv4 mapped IPv6 address; or false if address is IPv4 address.
     */
    private boolean isIPv4MappedAddress(byte[] addr) {
        if (addr.length < INADDR16SZ) {
            return false;
        }
        return ((addr[0] == 0x00) && (addr[1] == 0x00) && (addr[2] == 0x00) && (addr[3] == 0x00) && (addr[4] == 0x00)
                && (addr[5] == 0x00) && (addr[6] == 0x00) && (addr[7] == 0x00) && (addr[8] == 0x00)
                && (addr[9] == 0x00) && (addr[10] == (byte) 0xff) && (addr[11] == (byte) 0xff));
    }

}