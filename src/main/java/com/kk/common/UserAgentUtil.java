package com.kk.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UserAgentUtil {
    private static final Pattern RE_CHROME = Pattern.compile("(Edg|Edge|OPR|Chrome|Chromium)/([\\d.]+)");
    private static final Pattern RE_FIREFOX = Pattern.compile("Firefox/([\\d.]+)");
    private static final Pattern RE_SAFARI = Pattern.compile("Version/([\\d.]+).*Safari");
    private static final Pattern RE_IE = Pattern.compile("MSIE ([\\d.]+)|rv:([\\d.]+)");

    public static ParsedUA parse(String ua) {
        if (ua == null) ua = "";
        String osName = detectOsName(ua);
        String osVersion = detectOsVersion(ua, osName);
        String[] browser = detectBrowser(ua);
        String deviceType = decideType(ua);
        return new ParsedUA(osName, osVersion, browser[0], browser[1], deviceType);
    }

    private static String detectOsName(String ua) {
        String l = ua.toLowerCase();
        if (l.contains("windows nt")) return "Windows";
        if (l.contains("mac os x") || l.contains("macintosh")) return "macOS";
        if (l.contains("android")) return "Android";
        if (l.contains("iphone") || l.contains("ipad") || l.contains("cpu iphone os")) return "iOS";
        if (l.contains("linux")) return "Linux";
        return "";
    }

    private static String detectOsVersion(String ua, String osName) {
        try {
            if ("Windows".equals(osName)) {
                Matcher m = Pattern.compile("Windows NT ([\\d.]+)").matcher(ua);
                if (m.find()) return m.group(1);
            } else if ("macOS".equals(osName)) {
                Matcher m = Pattern.compile("Mac OS X ([\\d_]+)").matcher(ua);
                if (m.find()) return m.group(1).replace('_', '.');
            } else if ("Android".equals(osName)) {
                Matcher m = Pattern.compile("Android ([\\d.]+)").matcher(ua);
                if (m.find()) return m.group(1);
            } else if ("iOS".equals(osName)) {
                Matcher m = Pattern.compile("(?:CPU iPhone OS|iPhone OS|CPU OS) ([\\d_]+)").matcher(ua);
                if (m.find()) return m.group(1).replace('_', '.');
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String[] detectBrowser(String ua) {
        try {
            Matcher m;
            m = RE_CHROME.matcher(ua);
            if (m.find()) {
                String n = m.group(1);
                if ("OPR".equals(n)) n = "Opera";
                if ("Edg".equals(n)) n = "Edge";
                if ("Chromium".equals(n)) n = "Chromium";
                if ("Chrome".equals(n)) n = "Chrome";
                return new String[]{n, m.group(2)};
            }
            m = RE_FIREFOX.matcher(ua);
            if (m.find()) return new String[]{"Firefox", m.group(1)};
            m = RE_SAFARI.matcher(ua);
            if (m.find()) return new String[]{"Safari", m.group(1)};
            m = RE_IE.matcher(ua);
            if (m.find()) return new String[]{"IE", m.group(1) != null ? m.group(1) : m.group(2)};
        } catch (Exception ignored) {}
        return new String[]{"", ""};
    }

    private static String decideType(String ua) {
        String l = ua.toLowerCase();
        if (l.contains("ipad") || l.contains("tablet")) return "Tablet";
        if (l.contains("iphone") || (l.contains("android") && l.contains("mobile")) || l.contains("mobile")) return "Mobile";
        if (l.contains("windows") || l.contains("macintosh") || l.contains("linux")) return "Desktop";
        return "Other";
    }

    public record ParsedUA(String osName, String osVersion, String browserName, String browserVersion, String deviceType) {}
}
