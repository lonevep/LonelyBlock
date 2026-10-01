package com.lonelyBlock.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * PlaceholderAPI Hook (反射方式, 避免运行时硬依赖)
 *
 * 用于:
 *   - 解析条件中的 %placeholder% 占位符 (如 %player_points%)
 *   - 设置命令/消息中的占位符
 *
 * PlaceholderAPI 未安装时, 所有占位符原样返回 (不解析)
 */
public final class PlaceholderAPIHook {

    private static boolean enabled = false;
    private static Method setPlaceholdersMethod;  // PlaceholderAPI.setPlaceholders(Player, String)

    private PlaceholderAPIHook() {
    }

    /**
     * 初始化 PlaceholderAPI Hook
     */
    public static boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return false;
        }
        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = papiClass.getMethod("setPlaceholders", Player.class, String.class);
            enabled = true;
            return true;
        } catch (Throwable e) {
            enabled = false;
            return false;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 替换文本中的 PlaceholderAPI 占位符
     * PlaceholderAPI 未安装时原样返回
     */
    public static String setPlaceholders(Player player, String text) {
        if (!enabled || setPlaceholdersMethod == null || text == null) {
            return text;
        }
        try {
            Object result = setPlaceholdersMethod.invoke(null, player, text);
            return result != null ? result.toString() : text;
        } catch (Throwable e) {
            return text;
        }
    }

    /**
     * 判断字符串是否为 PAPI 占位符格式 (%...%)
     */
    public static boolean isPlaceholder(String s) {
        return s != null && s.startsWith("%") && s.endsWith("%") && s.length() > 2;
    }
}
