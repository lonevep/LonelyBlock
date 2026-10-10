package com.lonelyBlock.util;

import org.bukkit.ChatColor;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final boolean SUPPORTS_HEX;
    private static final Class<?> HEX_COLOR_CLASS;
    private static final Method HEX_COLOR_METHOD;

    static {
        Class<?> hexClass = null;
        Method hexMethod = null;
        try {
            hexClass = Class.forName("net.md_5.bungee.api.ChatColor");
            hexMethod = hexClass.getMethod("of", String.class);
        } catch (Throwable e) {
            hexClass = null;
            hexMethod = null;
        }
        HEX_COLOR_CLASS = hexClass;
        HEX_COLOR_METHOD = hexMethod;
        SUPPORTS_HEX = hexMethod != null;
    }

    private ColorUtil() {
    }

    public static String colorize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        if (message.indexOf('&') < 0) {
            return message;
        }
        if (SUPPORTS_HEX && HEX_COLOR_METHOD != null) {
            Matcher matcher = HEX_PATTERN.matcher(message);
            StringBuffer sb = new StringBuffer();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                String hex = matcher.group(1);
                try {
                    Object color = HEX_COLOR_METHOD.invoke(HEX_COLOR_CLASS, "#" + hex);
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(color.toString()));
                } catch (Throwable ignored) {
                }
            }
            if (found) {
                matcher.appendTail(sb);
                message = sb.toString();
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public static String stripColor(String message) {
        if (message == null) {
            return "";
        }
        return ChatColor.stripColor(message);
    }

    public static boolean nameEquals(String name1, String name2) {
        if (name1 == null || name2 == null) {
            return false;
        }
        String c1 = colorize(name1);
        String c2 = colorize(name2);
        if (c1.equalsIgnoreCase(c2)) {
            return true;
        }
        return stripColor(c1).equalsIgnoreCase(stripColor(c2));
    }
}
