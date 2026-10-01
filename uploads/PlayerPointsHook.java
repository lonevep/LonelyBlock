package com.lonelyBlock.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * PlayerPoints 点券插件 Hook
 * 通过反射兼容不同版本的 PlayerPoints API, 避免硬依赖
 *
 * PlayerPoints API 调用流程:
 *   PlayerPoints plugin = (PlayerPoints) Bukkit.getPluginManager().getPlugin("PlayerPoints");
 *   PlayerPointsAPI api = plugin.getAPI();
 *   api.give(uuid, amount);   // 给予
 *   api.take(uuid, amount);   // 扣除
 *   api.look(uuid);           // 查询余额
 */
public final class PlayerPointsHook {

    private static boolean enabled = false;
    private static String version = "unknown";

    // 缓存的反射对象
    private static Object apiInstance;
    private static Method giveMethod;     // give(UUID, int)
    private static Method takeMethod;     // take(UUID, int)
    private static Method lookMethod;     // look(UUID) -> int

    private PlayerPointsHook() {
    }

    /**
     * 初始化 PlayerPoints Hook
     *
     * @return 是否成功加载
     */
    public static boolean setup() {
        Plugin ppPlugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (ppPlugin == null) {
            return false;
        }
        version = ppPlugin.getDescription().getVersion();
        try {
            // 通过插件实例获取 API
            Method getApiMethod = null;
            for (Method m : ppPlugin.getClass().getMethods()) {
                if (m.getName().equals("getAPI") && m.getParameterCount() == 0) {
                    getApiMethod = m;
                    break;
                }
            }
            if (getApiMethod == null) {
                // 兼容大小写
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

            // 查找 give/take/look 方法 (参数可能是 UUID 或 OfflinePlayer, 数量可能是 int)
            giveMethod = findMethod(apiInstance, "give");
            takeMethod = findMethod(apiInstance, "take");
            // 查询余额: look / lookAsync / balance / getPoints
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

    /**
     * 查找指定名称的方法 (兼容 UUID / OfflinePlayer 第一个参数, int / double 第二个参数)
     */
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
            // 第一参数: UUID 或 OfflinePlayer 或 String
            Class<?> p1 = params[0];
            boolean firstOk = p1 == java.util.UUID.class
                    || p1 == OfflinePlayer.class
                    || p1 == String.class;
            // 第二参数: int / Integer / double / Double / long / Long
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

    /**
     * 检查 PlayerPoints 是否可用
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取 PlayerPoints 版本
     */
    public static String getVersion() {
        return version;
    }

    /**
     * 给予点券
     */
    public static boolean deposit(OfflinePlayer player, int amount) {
        if (!isEnabled() || player == null || amount <= 0) {
            return false;
        }
        return invokeAmountOp(giveMethod, player, amount);
    }

    /**
     * 扣除点券 (不足则返回 false)
     */
    public static boolean withdraw(OfflinePlayer player, int amount) {
        if (!isEnabled() || player == null || amount <= 0) {
            return false;
        }
        return invokeAmountOp(takeMethod, player, amount);
    }

    /**
     * 查询点券余额
     */
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

    /**
     * 调用 give/take 这类 (player, amount) -> boolean 的方法
     */
    private static boolean invokeAmountOp(Method method, OfflinePlayer player, int amount) {
        if (method == null) {
            return false;
        }
        try {
            Object result = invokeWithAmount(method, player, amount);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            // 没有返回值或返回非布尔, 视为成功
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 根据方法参数类型构造参数并调用 (带 amount)
     */
    private static Object invokeWithAmount(Method method, OfflinePlayer player, int amount) throws Exception {
        Class<?>[] params = method.getParameterTypes();
        Object arg1 = convertPlayerArg(params[0], player);
        Object arg2 = convertAmountArg(params[1], amount);
        return method.invoke(apiInstance, arg1, arg2);
    }

    /**
     * 根据方法参数类型构造参数并调用 (无 amount)
     */
    private static Object invokeWithPlayer(Method method, OfflinePlayer player) throws Exception {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            return method.invoke(apiInstance);
        }
        Object arg1 = convertPlayerArg(params[0], player);
        if (params.length == 1) {
            return method.invoke(apiInstance, arg1);
        }
        // 可能 look(uuid, ...) 形式, 尝试只传一个
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
