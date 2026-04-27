package com.kk.util;

import java.math.BigInteger;
import java.util.UUID;

public final class Base62Util {
    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = CHARS.length();
    private static final BigInteger BASE_BI = BigInteger.valueOf(BASE);

    private Base62Util() {}

    public static String encode(UUID uuid) {
        BigInteger value = new BigInteger(uuid.toString().replace("-", ""), 16);
        StringBuilder sb = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] div = value.divideAndRemainder(BASE_BI);
            sb.append(CHARS.charAt(div[1].intValue()));
            value = div[0];
        }
        while (sb.length() < 22) {
            sb.append('0');
        }
        return sb.reverse().toString();
    }
}
