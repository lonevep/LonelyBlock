package com.lonelyBlock.hook;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

 













public final class MythicMobsHook {

    private static boolean enabled = false;
    private static String version = "unknown";

    
    private static Object itemManagerInstance;
    private static Method getItemMethod;

    private MythicMobsHook() {
    }

     




    public static boolean setup() {
        Plugin mmPlugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mmPlugin == null) {
            return false;
        }
        version = mmPlugin.getDescription().getVersion();

        
        if (tryV5API()) {
            enabled = true;
            return true;
        }
        if (tryV4API()) {
            enabled = true;
            return true;
        }
        
        if (tryGenericAPI(mmPlugin)) {
            enabled = true;
            return true;
        }
        return false;
    }

     


    private static boolean tryV5API() {
        try {
            
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

     


    private static boolean tryGenericAPI(Plugin plugin) {
        try {
            
            return extractItemManager(plugin);
        } catch (Throwable e) {
            return false;
        }
    }

     


    private static boolean extractItemManager(Object instance) {
        try {
            Method getItemManager = null;
            
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

     


    public static boolean isEnabled() {
        return enabled;
    }

     


    public static String getVersion() {
        return version;
    }

     





    public static ItemStack getItem(String itemId) {
        if (!enabled || getItemMethod == null || itemId == null) {
            return null;
        }
        try {
            Object result = getItemMethod.invoke(itemManagerInstance, itemId);
            if (result == null) {
                return null;
            }
            
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

     


    public static boolean containsItem(String itemId) {
        return getItem(itemId) != null;
    }

     


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

     



    private static ItemStack convertToItemStack(Object mythicItem) {
        if (mythicItem == null) {
            return null;
        }
        
        if (mythicItem instanceof ItemStack) {
            return (ItemStack) mythicItem;
        }

        
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
