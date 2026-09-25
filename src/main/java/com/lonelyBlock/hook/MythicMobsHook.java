package com.lonelyBlock.hook;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * MythicMobs 物品库 Hook
 * 通过反射兼容各个版本的 MythicMobs API (v4/v5/v6+)
 *
 * 支持的API:
 *   v4: io.lumine.xikage.mythicmobs.MythicMobs.inst().getItemManager().getItem(name) -> Optional<ConfigurableItem>
 *   v5+: io.lumine.mythic.MythicPlugin / MythicBukkit.inst().getItemManager().getItem(name)
 *
 * 物品提取流程:
 *   1. 获取 MythicMobs 插件实例
 *   2. 获取 ItemManager
 *   3. 调用 getItem(name) 获取结果 (可能是 Optional 或直接对象)
 *   4. 转换为 ItemStack (调用 build()/generate()/toItemStack() 等方法)
 */
public final class MythicMobsHook {

    private static boolean enabled = false;
    private static String version = "unknown";

    // 缓存的反射对象
    private static Object itemManagerInstance;
    private static Method getItemMethod;

    private MythicMobsHook() {
    }

    /**
     * 初始化 MythicMobs Hook
     *
     * @return 是否成功加载
     */
    public static boolean setup() {
        Plugin mmPlugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mmPlugin == null) {
            return false;
        }
        version = mmPlugin.getDescription().getVersion();

        // 尝试不同的API路径
        if (tryV5API()) {
            enabled = true;
            return true;
        }
        if (tryV4API()) {
            enabled = true;
            return true;
        }
        // 通用fallback: 通过插件实例查找
        if (tryGenericAPI(mmPlugin)) {
            enabled = true;
            return true;
        }
        return false;
    }

    /**
     * 尝试 v5+ API: io.lumine.mythic 包路径
     */
    private static boolean tryV5API() {
        try {
            // v5+: io.lumine.mythic.bukkit.MythicBukkit (or similar)
            Class<?> mythicClass;
            try {
                mythicClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            } catch (ClassNotFoundException e) {
                try {
                    mythicClass = Class.forName("io.lumine.mythic.MythicPlugin");
                } catch (ClassNotFoundException e2) {
                    return false;
                }
            }

            Method instMethod = mythicClass.getMethod("inst");
            Object instance = instMethod.invoke(null);

            return extractItemManager(instance);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 尝试 v4 API: io.lumine.xikage.mythicmobs.MythicMobs
     */
    private static boolean tryV4API() {
        try {
            Class<?> mythicClass = Class.forName("io.lumine.xikage.mythicmobs.MythicMobs");
            Method instMethod = mythicClass.getMethod("inst");
            Object instance = instMethod.invoke(null);

            return extractItemManager(instance);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 通用 fallback: 通过插件实例直接查找 getItemManager 方法
     */
    private static boolean tryGenericAPI(Plugin plugin) {
        try {
            // 尝试在插件对象上调用 getItemManager / getItem 等
            return extractItemManager(plugin);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 从给定对象中提取 ItemManager
     */
    private static boolean extractItemManager(Object instance) {
        try {
            Method getItemManager = null;
            // 查找返回 ItemManager 的方法
            for (Method m : instance.getClass().getMethods()) {
                if (m.getName().equals("getItemManager") && m.getParameterCount() == 0) {
                    getItemManager = m;
                    break;
                }
            }
            if (getItemManager == null) {
                return false;
            }
            Object itemManager = getItemManager.invoke(instance);
            if (itemManager == null) {
                return false;
            }
            // 查找 getItem(String) 方法
            for (Method m : itemManager.getClass().getMethods()) {
                if (m.getName().equals("getItem") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                    getItemMethod = m;
                    break;
                }
            }
            if (getItemMethod == null) {
                return false;
            }
            itemManagerInstance = itemManager;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 检查 MythicMobs 是否可用
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取 MythicMobs 版本
     */
    public static String getVersion() {
        return version;
    }

    /**
     * 从 MythicMobs 物品库获取物品
     *
     * @param itemId 物品ID
     * @return 物品 ItemStack, 如果未找到返回 null
     */
    public static ItemStack getItem(String itemId) {
        if (!enabled || getItemMethod == null || itemId == null) {
            return null;
        }
        try {
            Object result = getItemMethod.invoke(itemManagerInstance, itemId);
            if (result == null) {
                return null;
            }
            // 处理 Optional 结果
            Object mythicItem = unwrapOptional(result);
            if (mythicItem == null) {
                return null;
            }
            return convertToItemStack(mythicItem);
        } catch (Throwable e) {
            if (com.lonelyBlock.LonelyBlock.isDebug()) {
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                        "获取MythicMobs物品 [" + itemId + "] 时出错: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * 检查物品是否存在
     */
    public static boolean containsItem(String itemId) {
        return getItem(itemId) != null;
    }

    /**
     * 解包 Optional 结果
     */
    private static Object unwrapOptional(Object result) {
        if (result == null) {
            return null;
        }
        String className = result.getClass().getSimpleName();
        if (className.equals("Optional")) {
            try {
                Method isPresent = result.getClass().getMethod("isPresent");
                boolean present = (Boolean) isPresent.invoke(result);
                if (!present) {
                    return null;
                }
                Method get = result.getClass().getMethod("get");
                return get.invoke(result);
            } catch (Throwable e) {
                return null;
            }
        }
        return result;
    }

    /**
     * 将 MythicMobs 内部物品对象转换为 ItemStack
     * 支持多种方法: build(), generate(), toItemStack(), getItemStack()
     */
    private static ItemStack convertToItemStack(Object mythicItem) {
        if (mythicItem == null) {
            return null;
        }
        // 已经是 ItemStack
        if (mythicItem instanceof ItemStack) {
            return (ItemStack) mythicItem;
        }

        // 尝试多个方法
        String[] methods = {"build", "generate", "toItemStack", "getItemStack", "toBukkit"};
        for (String methodName : methods) {
            try {
                Method method = mythicItem.getClass().getMethod(methodName);
                Object item = method.invoke(mythicItem);
                if (item instanceof ItemStack) {
                    return (ItemStack) item;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }

        // 尝试无参和带数量参数的 generate(int)
        try {
            Method generate = mythicItem.getClass().getMethod("generate", int.class);
            Object item = generate.invoke(mythicItem, 1);
            if (item instanceof ItemStack) {
                return (ItemStack) item;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }
}
