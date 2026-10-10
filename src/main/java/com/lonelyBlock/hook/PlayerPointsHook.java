package com.lonelyBlock.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

 










public final class PlayerPointsHook {

    private static boolean enabled = false;
    private static String version = "unknown";

    
    private static Object apiInstance;
    private static Method giveMethod;     
    private static Method takeMethod;     
    private static Method lookMethod;     

    private PlayerPointsHook() {
    }

     




    public static boolean setup() {
        Plugin ppPlugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (ppPlugin == null) {
            return false;
        }
        version = ppPlugin.getDescription().getVersion();
        try {
            
            Method getApiMethod = null;
            for (Method m : ppPlugin.getClass().getMethods()) {
                if (m.getName().equals("getAPI") && m.getParameterCount() == 0) {
                    getApiMethod = m;
                    break;
                }
            }
            if (getApiMethod == null) {
                
                for (Method m : ppPlugin.getClass().getMethods()) {
                    String n = m.getName();
                    if ((n.equals("getApi") || n.equals("getAPI")) && m.getParameterCount() == 0) {
                        getApiMethod = m;
                        break;
                    }
                }
            }
            if (getApiMethod == null) {
                return false;
            }
            apiInstance = getApiMethod.invoke(ppPlugin);
            if (apiInstance == null) {
                return false;
            }

            
            giveMethod = findMethod(apiInstance, "give");
            takeMethod = findMethod(apiInstance, "take");
            
            lookMethod = findMethod(apiInstance, "look");
            if (lookMethod == null) {
                lookMethod = findMethod(apiInstance, "balance");
            }
            if (lookMethod == null) {
                lookMethod = findMethod(apiInstance, "getPoints");
            }

            if (giveMethod == null || takeMethod == null) {
                return false;
            }
            enabled = true;
            return true;
        } catch (Throwable e) {
            enabled = false;
            return false;
        }
    }

     


    private static Method findMethod(Object instance, String name) {
        if (instance == null || name == null) {
            return null;
        }
        for (Method m : instance.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 2) {
                continue;
            }
            
            Class<?> p1 = params[0];
            boolean firstOk = p1 == java.util.UUID.class
                    || p1 == OfflinePlayer.class
                    || p1 == String.class;
            
            Class<?> p2 = params[1];
            boolean secondOk = p2 == int.class || p2 == Integer.class
                    || p2 == double.class || p2 == Double.class
                    || p2 == long.class || p2 == Long.class;
            if (firstOk && secondOk) {
                return m;
            }
        }
        return null;
    }

     


    public static boolean isEnabled() {
        return enabled;
    }

     


    public static String getVersion() {
        return version;
    }

     


    public static boolean deposit(OfflinePlayer player, int amount) {
        if (!isEnabled() || player == null || amount <= 0) {
            return false;
        }
        return invokeAmountOp(giveMethod, player, amount);
    }

     


    public static boolean withdraw(OfflinePlayer player, int amount) {
        if (!isEnabled() || player == null || amount <= 0) {
            return false;
        }
        return invokeAmountOp(takeMethod, player, amount);
    }

     


    public static int getBalance(OfflinePlayer player) {
        if (!isEnabled() || player == null || lookMethod == null) {
            return 0;
        }
        try {
            Object result = invokeWithPlayer(lookMethod, player);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
            if (result != null) {
                try {
                    return Integer.parseInt(result.toString());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        } catch (Throwable e) {
            return 0;
        }
    }

     


    private static boolean invokeAmountOp(Method method, OfflinePlayer player, int amount) {
        if (method == null) {
            return false;
        }
        try {
            Object result = invokeWithAmount(method, player, amount);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

     


    private static Object invokeWithAmount(Method method, OfflinePlayer player, int amount) throws Exception {
        Class<?>[] params = method.getParameterTypes();
        Object arg1 = convertPlayerArg(params[0], player);
        Object arg2 = convertAmountArg(params[1], amount);
        return method.invoke(apiInstance, arg1, arg2);
    }

     


    private static Object invokeWithPlayer(Method method, OfflinePlayer player) throws Exception {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            return method.invoke(apiInstance);
        }
        Object arg1 = convertPlayerArg(params[0], player);
        if (params.length == 1) {
            return method.invoke(apiInstance, arg1);
        }
        
        return method.invoke(apiInstance, arg1);
    }

    private static Object convertPlayerArg(Class<?> type, OfflinePlayer player) {
        if (type == java.util.UUID.class) {
            return player.getUniqueId();
        }
        if (type == OfflinePlayer.class) {
            return player;
        }
        if (type == String.class) {
            return player.getUniqueId().toString();
        }
        return player;
    }

    private static Object convertAmountArg(Class<?> type, int amount) {
        if (type == int.class || type == Integer.class) {
            return amount;
        }
        if (type == double.class || type == Double.class) {
            return (double) amount;
        }
        if (type == long.class || type == Long.class) {
            return (long) amount;
        }
        return amount;
    }
}
