package com.lonelyBlock.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

 








public final class PlaceholderAPIHook {

    private static boolean enabled = false;
    private static Method setPlaceholdersMethod;  

    private PlaceholderAPIHook() {
    }

     


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

     


    public static boolean isPlaceholder(String s) {
        return s != null && s.startsWith("%") && s.endsWith("%") && s.length() > 2;
    }
}
