package com.lonelyBlock.util;

import org.bukkit.ChatColor;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 颜色代码工具类 - 适应不同版本的颜色代码转换
 * 支持 1.8 的传统 & 代码 和 1.16+ 的 HEX 颜色代码
 *
 * 通过反射兼容不同版本的 ChatColor.of() 方法 (1.16+ 才有此方法)
 */
public final class ColorUtil {

    // HEX 颜色正则, 如 &#FFAA00 或 #FFAA00
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final boolean SUPPORTS_HEX;
    private static final Class<?> HEX_COLOR_CLASS;
    private static final Method HEX_COLOR_METHOD;

    static {
        Class<?> hexClass = null;
        Method hexMethod = null;
        try {
            // 检测是否支持 1.16+ 的 ChatColor.of(String) 方法
            hexClass = Class.forName("net.md_5.bungee.api.ChatColor");
            hexMethod = hexClass.getMethod("of", String.class);
        } catch (Throwable e) {
            // 不支持 HEX 颜色
            hexClass = null;
            hexMethod = null;
        }
        HEX_COLOR_CLASS = hexClass;
        HEX_COLOR_METHOD = hexMethod;
        SUPPORTS_HEX = hexMethod != null;
    }

    private ColorUtil() {
    }

    /**
     * 将字符串中的颜色代码转换为彩色字符串
     * 兼容 1.8 (& 代码) 和 1.16+ (HEX 代码)
     */
    public static String colorize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        // 处理 HEX 颜色 (1.16+)
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
                    // 忽略转换失败, 保留原始内容
                }
            }
            if (found) {
                matcher.appendTail(sb);
                message = sb.toString();
            }
        }
        // 处理传统颜色代码
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * 去除颜色代码 (用于比较物品名称)
     */
    public static String stripColor(String message) {
        if (message == null) {
            return "";
        }
        return ChatColor.stripColor(message);
    }

    /**
     * 比较两个物品显示名是否相等
     * 先尝试精确比较 (颜色代码已转换), 失败则使用去除颜色代码的弱比较
     */
    public static boolean nameEquals(String name1, String name2) {
        if (name1 == null || name2 == null) {
            return false;
        }
        String c1 = colorize(name1);
        String c2 = colorize(name2);
        if (c1.equalsIgnoreCase(c2)) {
            return true;
        }
        // 弱比较: 去除颜色代码后比较 (允许 "name:木斧" 匹配 "&c木斧" 物品)
        return stripColor(c1).equalsIgnoreCase(stripColor(c2));
    }
}
