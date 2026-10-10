package com.lonelyBlock.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

 



















public final class NeigeItemsHook {

    private static boolean enabled = false;
    private static String version = "unknown";

    private static Object itemsManagerInstance;
     
    private static Method getItemMethod;
     
    private static Class<?>[] getItemParamTypes;

    
    
    
    
    
    
     
    private static Method isNiItemMethod;
     
    private static boolean isNiItemIsStatic = false;
     
    private static Object isNiItemInstance;

     
    private static Method addCustomDurabilityMethod;
     
    private static boolean addCustomDurabilityIsStatic = false;
     
    private static Object addCustomDurabilityInstance;
     
    private static int addCustomDurabilityParamCount = 0;

    
    
    
    
    
    
    
     
    private static Method damageMethod;
     
    private static Object itemDurabilityInstance;
     
    private static boolean damageIsStatic = false;
     
    private static int damageParamCount = 0;

     
    private static String durabilityApiType = "none";
     
    private static boolean durabilityApiAvailable = false;
     
    private static volatile int diagnoseLogCount = 0;

    
    
    
    
    
    
    private static volatile boolean nbtReflectionInit = false;
    private static volatile boolean nbtReflectionOk = false;
    private static Class<?> craftItemStackClass;
    private static Method asNMSCopyMethod;     
    private static Method nmsGetTagMethod;     
    private static Method nmsGetOrCreateTagMethod; 
    private static Method nbtHasKeyMethod;     
    
    private static Method nbtGetCompoundMethod; 
    private static Method nbtGetStringMethod;   

    private NeigeItemsHook() {
    }

     




    public static boolean setup() {
        Plugin niPlugin = Bukkit.getPluginManager().getPlugin("NeigeItems");
        if (niPlugin == null) {
            
            niPlugin = findPluginByNameVariants("NeigeItems", "neigeitems", "Neige Items", "NeigeItemsPlugin");
        }
        if (niPlugin == null) {
            return false;
        }
        version = niPlugin.getDescription().getVersion();

        
        String[] classNames = {
                "pers.neige.neigeitems.NeigeItems",           
                "ink.neokomi.light.application.NeigeItems",   
                "ink.neokomi.light.NeigeItems",               
                "pers.neige.neigeitems.NeigeItemsPlugin"      
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);
                Object instance = getInstance(clazz);
                if (instance != null && extractItemManager(instance)) {
                    enabled = true;
                    initDurabilityAPI(itemsManagerInstance);
                    return true;
                }
            } catch (ClassNotFoundException e) {
                
            } catch (Throwable ignored) {
            }
        }

        
        if (extractItemManager(niPlugin)) {
            enabled = true;
            initDurabilityAPI(itemsManagerInstance);
            return true;
        }

        
        
        if (loadItemManagerObject()) {
            enabled = true;
            initDurabilityAPI(itemsManagerInstance);
            return true;
        }

        
        com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                "NeigeItems 已加载 (版本=" + version + ") 但无法定位 ItemManager: "
                        + "主类无 getItemsManager() 方法, 且 pers.neige.neigeitems.manager.ItemManager 加载失败. "
                        + "请反馈 NeigeItems 版本与主类名给插件作者.");

        return false;
    }

     












    private static void initDurabilityAPI(Object itemsManager) {
        try {
            Class<?> itemManagerClass = itemsManager != null ? itemsManager.getClass() : null;

            
            loadIsNiItemMethod(itemManagerClass);

            
            loadDamageMethod();

            
            if (damageMethod == null && itemManagerClass != null) {
                loadAddCustomDurability(itemManagerClass, itemsManager);
            }

            
            if (damageMethod != null) {
                durabilityApiType = "new";
                durabilityApiAvailable = true;
            } else if (addCustomDurabilityMethod != null) {
                durabilityApiType = "old";
                durabilityApiAvailable = true;
            } else {
                durabilityApiType = "none";
                durabilityApiAvailable = false;
            }

            
            initNbtReflection();

            
            if (durabilityApiAvailable) {
                String apiDesc;
                if ("new".equals(durabilityApiType)) {
                    apiDesc = "ItemDurability.damage (新版, 参数数=" + damageParamCount
                            + ", 静态=" + damageIsStatic + ")";
                } else {
                    apiDesc = "addCustomDurability (旧版, 参数数=" + addCustomDurabilityParamCount
                            + ", 静态=" + addCustomDurabilityIsStatic + ")";
                }
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                        "已加载 NeigeItems 自定义耐久 API (" + apiDesc + "), NI 物品挖掘将扣除独立耐久");
            } else {
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                        "未能加载 NeigeItems 自定义耐久 API (ItemDurability.damage 和 addCustomDurability 均未找到), "
                                + "NI 物品挖掘将只扣除原版耐久 (itemManagerClass="
                                + (itemManagerClass != null ? itemManagerClass.getName() : "null") + ")");
            }

            
            com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                    "[NeigeItems] 检测状态: isNiItemMethod=" + (isNiItemMethod != null)
                            + " | NBT反射=" + (nbtReflectionOk ? "OK" : "失败")
                            + " | getTag=" + (nmsGetTagMethod != null)
                            + " | getOrCreateTag=" + (nmsGetOrCreateTagMethod != null)
                            + " | getCompound=" + (nbtGetCompoundMethod != null)
                            + " | getString=" + (nbtGetStringMethod != null)
                            + " | 耐久API=" + durabilityApiType
                            + " (" + (durabilityApiAvailable ? "可用" : "不可用") + ")");
        } catch (Throwable ignored) {
            durabilityApiAvailable = false;
        }
    }

     











    private static void loadDamageMethod() {
        String[] candidateClassNames = {
                "pers.neige.neigeitems.item.ItemDurability",       
                "pers.neige.neigeitems.manager.ItemDurability",    
        };
        for (String className : candidateClassNames) {
            try {
                Class<?> itemDurabilityClass = Class.forName(className);
                
                Method best = null;
                for (Method m : itemDurabilityClass.getMethods()) {
                    if (!m.getName().equals("damage")) continue;
                    if (m.isSynthetic() || m.getName().contains("$default")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length < 4) continue;
                    
                    if (p[0] != Player.class) continue;
                    if (p[1] != ItemStack.class) continue;
                    if (!typesCompatible(p[2], int.class)) continue;
                    if (!typesCompatible(p[3], boolean.class)) continue;
                    
                    if (p.length == 4) {
                        best = m;
                        break; 
                    } else if (p.length == 5 && best == null) {
                        best = m; 
                    }
                }
                if (best != null) {
                    damageMethod = best;
                    damageIsStatic = Modifier.isStatic(best.getModifiers());
                    damageParamCount = best.getParameterCount();
                    itemDurabilityInstance = damageIsStatic ? null : getKotlinInstance(itemDurabilityClass);
                    if (com.lonelyBlock.LonelyBlock.isDebug()) {
                        com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                                "[NeigeItems耐久API] 在 " + className + " 中找到 damage 方法 (参数数="
                                        + damageParamCount + ", 静态=" + damageIsStatic + ")");
                    }
                    return;
                }
            } catch (ClassNotFoundException ignored) {
                
            }
        }
    }

     











    private static void loadIsNiItemMethod(Class<?> itemManagerClass) {
        
        java.util.List<Class<?>> searchClasses = new java.util.ArrayList<>();
        String[] candidateClassNames = {
                "pers.neige.neigeitems.utils.ItemUtils",
                "pers.neige.neigeitems.item.ItemUtils",
                "ink.neokomi.light.application.utils.ItemUtils",
        };
        for (String cn : candidateClassNames) {
            try {
                Class<?> c = Class.forName(cn);
                if (!searchClasses.contains(c)) {
                    searchClasses.add(c);
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (itemManagerClass != null && !searchClasses.contains(itemManagerClass)) {
            searchClasses.add(itemManagerClass);
        }

        
        String[] methodNames = {"isNiItem", "isNIItem", "isNeigeItem"};

        
        for (Class<?> clazz : searchClasses) {
            for (String name : methodNames) {
                Method m = findMethod(clazz, name, ItemStack.class);
                if (m != null) {
                    isNiItemMethod = m;
                    isNiItemIsStatic = Modifier.isStatic(m.getModifiers());
                    isNiItemInstance = isNiItemIsStatic ? null : getKotlinInstance(clazz);
                    com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                            "[NeigeItems] isNiItem 方法已加载: " + clazz.getName() + "." + m.getName()
                                    + " (静态=" + isNiItemIsStatic + ")");
                    return;
                }
            }
        }

        
        
        for (Class<?> clazz : searchClasses) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != ItemStack.class) continue;
                Class<?> rt = m.getReturnType();
                if (rt == void.class || rt.isPrimitive()) continue;
                String mn = m.getName();
                
                if (mn.contains("getItemStack") || mn.contains("Durability") || mn.contains("damage")) continue;
                
                if (mn.contains("Ni") || mn.contains("niItem") || mn.contains("ItemInfo")) {
                    isNiItemMethod = m;
                    isNiItemIsStatic = Modifier.isStatic(m.getModifiers());
                    isNiItemInstance = isNiItemIsStatic ? null : getKotlinInstance(clazz);
                    com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                            "[NeigeItems] isNiItem 方法已加载 (兜底匹配): " + clazz.getName() + "." + mn
                                    + " (静态=" + isNiItemIsStatic + ")");
                    return;
                }
            }
        }

        com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                "[NeigeItems] 未找到 isNiItem 方法, NI 物品判定将依赖 NBT 标签检测 ("
                        + searchClasses.size() + " 个候选类已搜索)");
    }

     














    private static void loadAddCustomDurability(Class<?> itemManagerClass, Object itemsManager) {
        
        java.util.List<Class<?>> searchClasses = new java.util.ArrayList<>();
        if (itemManagerClass != null) {
            searchClasses.add(itemManagerClass);
        }
        
        for (String utilsClassName : new String[]{
                "pers.neige.neigeitems.utils.ItemUtils",
                "pers.neige.neigeitems.item.ItemUtils",
                "ink.neokomi.light.application.utils.ItemUtils"}) {
            try {
                Class<?> utilsClass = Class.forName(utilsClassName);
                if (!searchClasses.contains(utilsClass)) {
                    searchClasses.add(utilsClass);
                }
            } catch (ClassNotFoundException ignored) {
            }
        }

        for (Class<?> searchClass : searchClasses) {
            
            Object receiver = resolveReceiver(searchClass, itemManagerClass, itemsManager);
            Method found = scanForAddCustomDurability(searchClass);
            if (found != null) {
                addCustomDurabilityMethod = found;
                addCustomDurabilityIsStatic = Modifier.isStatic(found.getModifiers());
                addCustomDurabilityInstance = addCustomDurabilityIsStatic ? null : receiver;
                addCustomDurabilityParamCount = found.getParameterCount();
                if (com.lonelyBlock.LonelyBlock.isDebug()) {
                    com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                            "[NeigeItems耐久API] 在 " + searchClass.getName()
                                    + " 中找到 addCustomDurability (参数数=" + addCustomDurabilityParamCount
                                    + ", 静态=" + addCustomDurabilityIsStatic + ")");
                }
                return;
            }
        }
    }

     




    private static Object resolveReceiver(Class<?> searchClass, Class<?> itemManagerClass, Object itemsManager) {
        if (searchClass == itemManagerClass) {
            return itemsManager;
        }
        return getKotlinInstance(searchClass);
    }

     


    private static Method scanForAddCustomDurability(Class<?> clazz) {
        Method twoArg = null;
        Method threeArg = null;
        for (Method m : clazz.getMethods()) {
            String name = m.getName();
            if (!name.equals("addCustomDurability")) {
                continue;
            }
            
            if (name.contains("$default") || m.isSynthetic()) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 2 || p[0] != ItemStack.class || !typesCompatible(p[1], int.class)) {
                continue;
            }
            if (p.length == 2) {
                if (twoArg == null) {
                    twoArg = m;
                }
            } else if (p.length == 3 && typesCompatible(p[2], boolean.class)) {
                if (threeArg == null) {
                    threeArg = m;
                }
            }
        }
        return twoArg != null ? twoArg : threeArg;
    }

     


    private static Object getKotlinInstance(Class<?> clazz) {
        try {
            Field f = clazz.getField("INSTANCE");
            return f.get(null);
        } catch (Throwable ignored) {
        }
        
        try {
            for (Field fld : clazz.getDeclaredFields()) {
                String n = fld.getName();
                if (n.equals("INSTANCE") || n.equals("INSTANCE$")) {
                    fld.setAccessible(true);
                    return fld.get(null);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

     






    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {
        }
        
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != paramTypes.length) {
                continue;
            }
            
            if (m.isSynthetic() || m.getName().contains("$default")) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!typesCompatible(m.getParameterTypes()[i], paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return m;
            }
        }
        return null;
    }

     



    private static boolean typesCompatible(Class<?> actual, Class<?> expected) {
        if (actual == expected) {
            return true;
        }
        if (actual.isPrimitive() || expected.isPrimitive()) {
            return primitiveWrapper(actual) == primitiveWrapper(expected);
        }
        return actual.isAssignableFrom(expected) || expected.isAssignableFrom(actual);
    }

     


    private static Class<?> primitiveWrapper(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == Integer.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == Boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        if (type == Long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == Double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == Float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == Short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == Byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == Character.class) return Character.class;
        return type;
    }

     


    private static Plugin findPluginByNameVariants(String... names) {
        for (String name : names) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null) return p;
        }
        return null;
    }

     


    private static Object getInstance(Class<?> clazz) {
        
        try {
            Field field = clazz.getField("INSTANCE");
            return field.get(null);
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable ignored) {
        }

        
        try {
            Method method = clazz.getMethod("getInstance");
            return method.invoke(null);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        
        try {
            Method method = clazz.getMethod("inst");
            return method.invoke(null);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        
        try {
            Method method = clazz.getMethod("getPlugin");
            return method.invoke(null);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        return null;
    }

     



    private static boolean extractItemManager(Object instance) {
        try {
            
            Method getItemsManager = null;
            String[] methodNames = {"getItemsManager", "getItemManager", "itemsManager", "itemManager"};
            for (String name : methodNames) {
                for (Method m : instance.getClass().getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 0) {
                        getItemsManager = m;
                        break;
                    }
                }
                if (getItemsManager != null) break;
            }

            if (getItemsManager == null) {
                return false;
            }

            Object itemsManager = getItemsManager.invoke(instance);
            if (itemsManager == null) {
                return false;
            }
            return findGetItemMethod(itemsManager);
        } catch (Throwable e) {
            return false;
        }
    }

     













    private static boolean findGetItemMethod(Object itemsManager) {
        try {
            String[] itemMethodNames = {"getItemStack", "getItem", "getItemStackById", "getItemStackFromId"};
            Method best = null;
            Class<?>[] bestParams = null;
            for (String name : itemMethodNames) {
                for (Method m : itemsManager.getClass().getMethods()) {
                    if (!m.getName().equals(name)) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 0) continue;
                    if (params[0] != String.class) continue;
                    
                    if (m.getName().contains("$default")) continue;
                    if (isSyntheticKotlinDefault(m, params)) continue;
                    
                    if (best == null || params.length < bestParams.length) {
                        best = m;
                        bestParams = params;
                    }
                }
                if (best != null) break;
            }
            if (best == null) {
                return false;
            }
            getItemMethod = best;
            getItemParamTypes = bestParams;
            itemsManagerInstance = itemsManager;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

     










    private static boolean loadItemManagerObject() {
        String[] candidateClassNames = {
                "pers.neige.neigeitems.manager.ItemManager",   
                "pers.neige.neigeitems.ItemManager",            
        };
        for (String className : candidateClassNames) {
            try {
                Class<?> itemManagerClass = Class.forName(className);
                Object instance = getKotlinInstance(itemManagerClass);
                if (instance == null) {
                    continue;
                }
                if (findGetItemMethod(instance)) {
                    return true;
                }
            } catch (ClassNotFoundException ignored) {
                
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

     



    private static boolean isSyntheticKotlinDefault(Method m, Class<?>[] params) {
        if (params.length < 3) return false;
        Class<?> last = params[params.length - 1];
        Class<?> secondLast = params[params.length - 2];
        
        return secondLast == int.class && last == Object.class;
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
            Object[] args = buildInvokeArgs(getItemParamTypes, itemId);
            Object result = getItemMethod.invoke(itemsManagerInstance, args);
            if (result == null) {
                return null;
            }
            if (result instanceof ItemStack) {
                return (ItemStack) result;
            }
            
            if (result.getClass().getSimpleName().equals("Optional")) {
                try {
                    Method isPresent = result.getClass().getMethod("isPresent");
                    boolean present = (Boolean) isPresent.invoke(result);
                    if (!present) {
                        return null;
                    }
                    Method get = result.getClass().getMethod("get");
                    Object inner = get.invoke(result);
                    if (inner instanceof ItemStack) {
                        return (ItemStack) inner;
                    }
                } catch (Throwable ignored) {
                }
            }
            return null;
        } catch (Throwable e) {
            if (com.lonelyBlock.LonelyBlock.isDebug()) {
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                        "获取NeigeItems物品 [" + itemId + "] 时出错: " + e.getMessage());
            }
            return null;
        }
    }

     



    private static Object[] buildInvokeArgs(Class<?>[] paramTypes, String itemId) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (i == 0) {
                args[i] = itemId;
                continue;
            }
            Class<?> t = paramTypes[i];
            if (t == boolean.class) {
                args[i] = Boolean.TRUE;  
            } else if (t == int.class) {
                args[i] = Integer.valueOf(0);
            } else if (t == long.class) {
                args[i] = Long.valueOf(0L);
            } else if (t == double.class) {
                args[i] = Double.valueOf(0.0);
            } else if (t == float.class) {
                args[i] = Float.valueOf(0f);
            } else if (t == short.class) {
                args[i] = Short.valueOf((short) 0);
            } else if (t == byte.class) {
                args[i] = Byte.valueOf((byte) 0);
            } else if (t == char.class) {
                args[i] = Character.valueOf(' ');
            } else {
                args[i] = null;  
            }
        }
        return args;
    }

     


    public static boolean containsItem(String itemId) {
        return getItem(itemId) != null;
    }

    
    
    

     






    public static boolean isDurabilityApiAvailable() {
        return enabled && durabilityApiAvailable
                && (damageMethod != null || addCustomDurabilityMethod != null);
    }

     






    public static boolean durabilityApiHandlesUnbreaking() {
        return "new".equals(durabilityApiType);
    }

     















    public static boolean isNeigeItemsItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        
        boolean doDiagnose = diagnoseLogCount < 5;
        StringBuilder diag = doDiagnose ? new StringBuilder() : null;
        if (doDiagnose) {
            diagnoseLogCount++;
            diag.append("itemType=").append(item.getType())
                    .append(" hasItemMeta=").append(item.hasItemMeta());
        }

        
        if (isNiItemMethod != null) {
            try {
                Object result = isNiItemMethod.invoke(isNiItemInstance, item);
                if (doDiagnose) {
                    diag.append(" | isNiItem结果=").append(result != null ? "非null(NI物品)" : "null");
                }
                if (result != null) {
                    if (doDiagnose) {
                        logDiagnose(diag.toString(), true);
                    }
                    return true;
                }
                
            } catch (Throwable e) {
                if (doDiagnose) {
                    diag.append(" | isNiItem异常=").append(e.getClass().getSimpleName()).append(":").append(e.getMessage());
                }
                
            }
        } else {
            if (doDiagnose) {
                diag.append(" | isNiItemMethod=null(未加载)");
            }
        }
        
        boolean nbtResult = hasNeigeItemsNbtTag(item);
        if (doDiagnose) {
            diag.append(" | NBT检测结果=").append(nbtResult);
            logDiagnose(diag.toString(), nbtResult);
        }
        return nbtResult;
    }

     


    private static void logDiagnose(String detail, boolean result) {
        try {
            com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                    "[NeigeItems诊断#" + diagnoseLogCount + "] 最终判定=" + result + " | " + detail);
        } catch (Throwable ignored) {
        }
    }

     









    public static String getNeigeItemsItemId(ItemStack item) {
        if (item == null || !initNbtReflection()) {
            return null;
        }
        if (nbtGetCompoundMethod == null || nbtHasKeyMethod == null) {
            return null;
        }
        try {
            Object nmsItem = asNMSCopyMethod.invoke(null, item);
            if (nmsItem == null) {
                return null;
            }
            Object tag = getNmsTag(nmsItem);
            if (tag == null) {
                return null;
            }
            
            
            
            Object hasNi = nbtHasKeyMethod.invoke(tag, "NeigeItems");
            if (!Boolean.TRUE.equals(hasNi)) {
                return null;
            }
            
            Object niSection = nbtGetCompoundMethod.invoke(tag, "NeigeItems");
            if (niSection == null) {
                return null;
            }
            
            Object hasId = nbtHasKeyMethod.invoke(niSection, "id");
            if (!Boolean.TRUE.equals(hasId)) {
                return null;
            }
            
            
            
            
            
            
            String snbt = safeNbtToString(niSection);
            String id = parseStringFromSnbt(snbt, "id");
            if (id == null || id.isEmpty()) {
                
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                        "[NeigeItems NBT警告] SNBT 解析未找到 id 字段. SNBT=" + snbt);
                return null;
            }
            return id;
        } catch (Throwable ignored) {
            return null;
        }
    }

     












    private static String parseStringFromSnbt(String snbt, String key) {
        if (snbt == null || snbt.isEmpty() || key == null || key.isEmpty()) {
            return null;
        }
        
        
        String regex = "(?:^|[,{])\\s*" + java.util.regex.Pattern.quote(key)
                + "\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(snbt);
        if (m.find()) {
            return unescapeSnbtString(m.group(1));
        }
        return null;
    }

     


    private static String unescapeSnbtString(String s) {
        if (s == null) return null;
        if (s.indexOf('\\') < 0) return s; 
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    default: sb.append(c); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

     


    private static String safeNbtToString(Object nbt) {
        if (nbt == null) {
            return "null";
        }
        try {
            String s = nbt.toString();
            if (s != null && s.length() > 300) {
                return s.substring(0, 300) + "...";
            }
            return s;
        } catch (Throwable t) {
            return "<toString失败: " + t.getClass().getSimpleName() + ">";
        }
    }

     













    private static Object getNmsTag(Object nmsItem) {
        if (nmsItem == null) {
            return null;
        }
        
        if (nmsGetTagMethod != null) {
            try {
                Object tag = nmsGetTagMethod.invoke(nmsItem);
                if (tag != null) {
                    return tag;
                }
                
            } catch (Throwable ignored) {
                
            }
        }
        
        if (nmsGetOrCreateTagMethod != null) {
            try {
                return nmsGetOrCreateTagMethod.invoke(nmsItem);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

     










    private static boolean hasNeigeItemsNbtTag(ItemStack item) {
        if (!initNbtReflection()) {
            
            return hasNeigeItemsNbtTagFallback(item);
        }
        try {
            Object nmsItem = asNMSCopyMethod.invoke(null, item);
            if (nmsItem == null) {
                return false;
            }
            Object tag = getNmsTag(nmsItem);
            if (tag == null) {
                return false;
            }
            Object has = nbtHasKeyMethod.invoke(tag, "NeigeItems");
            return Boolean.TRUE.equals(has);
        } catch (Throwable ignored) {
            return false;
        }
    }

     





    private static boolean hasNeigeItemsNbtTagFallback(ItemStack item) {
        try {
            if (!item.hasItemMeta()) {
                return false;
            }
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return false;
            }
            
            String dump = meta.toString();
            if (dump.contains("NeigeItems")) {
                return true;
            }
            
            java.util.Map<String, Object> serialized = meta.serialize();
            return serialized.toString().contains("NeigeItems");
        } catch (Throwable ignored) {
            return false;
        }
    }

     




    private static boolean initNbtReflection() {
        if (nbtReflectionInit) {
            return nbtReflectionOk;
        }
        synchronized (NeigeItemsHook.class) {
            if (nbtReflectionInit) {
                return nbtReflectionOk;
            }
            try {
                
                String cbPkg = Bukkit.getServer().getClass().getPackage().getName();
                String craftItemStackClassName = cbPkg + ".inventory.CraftItemStack";
                craftItemStackClass = Class.forName(craftItemStackClassName);

                
                asNMSCopyMethod = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
                Class<?> nmsItemStackClass = asNMSCopyMethod.getReturnType();

                
                
                nmsGetTagMethod = findNoArgMethodByReturnName(nmsItemStackClass,
                        new String[]{"getTag"},
                        new String[]{"NBTTagCompound", "CompoundTag"});
                
                
                nmsGetOrCreateTagMethod = findNoArgMethodByReturnName(nmsItemStackClass,
                        new String[]{"getOrCreateTag"},
                        new String[]{"NBTTagCompound", "CompoundTag"});
                if (nmsGetTagMethod == null && nmsGetOrCreateTagMethod == null) {
                    
                    nbtReflectionInit = true;
                    nbtReflectionOk = false;
                    com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                            "[NeigeItems NBT反射] 未找到 getTag/getOrCreateTag 方法, NI 物品 NBT 检测将使用兜底 (精度有限)");
                    return false;
                }
                
                Class<?> nbtClass = (nmsGetTagMethod != null ? nmsGetTagMethod : nmsGetOrCreateTagMethod).getReturnType();

                
                nbtHasKeyMethod = findStringBooleanMethod(nbtClass);
                if (nbtHasKeyMethod == null) {
                    nbtReflectionInit = true;
                    nbtReflectionOk = false;
                    return false;
                }

                
                
                nbtGetCompoundMethod = findMethodByNameFirst(nbtClass,
                        new String[]{"getCompound"},
                        new String[]{"NBTTagCompound", "CompoundTag"});
                
                
                nbtGetStringMethod = findMethodByNameFirst(nbtClass,
                        new String[]{"getString"},
                        new String[]{"String"});

                
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                        "[NeigeItems NBT] getCompound=" + (nbtGetCompoundMethod != null ? nbtGetCompoundMethod.getName() : "未找到")
                                + " | getString=" + (nbtGetStringMethod != null ? nbtGetStringMethod.getName() : "未找到")
                                + " | hasKey=" + nbtHasKeyMethod.getName());

                nbtReflectionOk = true;
                nbtReflectionInit = true;
                if (com.lonelyBlock.LonelyBlock.isDebug()) {
                    com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                            "[NeigeItems NBT反射] 初始化成功: getTag=" + (nmsGetTagMethod != null ? nmsGetTagMethod.getName() : "null")
                                    + " getOrCreateTag=" + (nmsGetOrCreateTagMethod != null ? nmsGetOrCreateTagMethod.getName() : "null")
                                    + " hasKey=" + nbtHasKeyMethod.getName());
                }
                return true;
            } catch (Throwable t) {
                nbtReflectionInit = true;
                nbtReflectionOk = false;
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                        "[NeigeItems NBT反射] 初始化失败, NI 物品 NBT 检测将使用兜底 (精度有限): " + t.getMessage());
                return false;
            }
        }
    }

     







    private static Method findNoArgMethodByReturnName(Class<?> clazz, String[] preferredNames,
                                                      String[] returnSimpleNames) {
        Class<?> c = clazz;
        
        if (preferredNames != null) {
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) {
                        continue;
                    }
                    String retName = m.getReturnType().getSimpleName();
                    boolean retMatch = false;
                    for (String expected : returnSimpleNames) {
                        if (expected.equals(retName)) {
                            retMatch = true;
                            break;
                        }
                    }
                    if (!retMatch) {
                        continue;
                    }
                    for (String pn : preferredNames) {
                        if (pn.equals(m.getName())) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
                c = c.getSuperclass();
            }
            c = clazz;
        }
        
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                String retName = m.getReturnType().getSimpleName();
                for (String expected : returnSimpleNames) {
                    if (expected.equals(retName)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

     


    private static Method findStringBooleanMethod(Class<?> clazz) {
        Method hasKey = null;
        Method contains = null;
        Method any = null;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                String name = m.getName();
                if (hasKey == null && name.equals("hasKey")) {
                    m.setAccessible(true);
                    hasKey = m;
                } else if (contains == null && name.equals("contains")) {
                    m.setAccessible(true);
                    contains = m;
                } else if (any == null && !name.equals("hasKey") && !name.equals("contains")) {
                    m.setAccessible(true);
                    any = m;
                }
            }
            c = c.getSuperclass();
        }
        if (hasKey != null) return hasKey;
        if (contains != null) return contains;
        return any;
    }

     







    private static Method findStringReturnMethod(Class<?> clazz, String[] returnSimpleNames) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                String retName = m.getReturnType().getSimpleName();
                for (String expected : returnSimpleNames) {
                    if (expected.equals(retName)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

     












    private static Method findMethodByNameFirst(Class<?> clazz, String[] preferredNames,
                                                String[] returnSimpleNames) {
        if (preferredNames != null) {
            Class<?> c = clazz;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != String.class) continue;
                    
                    if (m.isSynthetic()) continue;
                    for (String pn : preferredNames) {
                        if (pn.equals(m.getName())) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
                c = c.getSuperclass();
            }
        }
        
        return findStringReturnMethod(clazz, returnSimpleNames);
    }

     





















    public static ItemStack damageNeigeItemsItem(Player player, ItemStack item, int damageAmount) {
        if (!isDurabilityApiAvailable() || item == null || damageAmount <= 0) {
            return null;
        }
        try {
            
            if ("new".equals(durabilityApiType) && damageMethod != null) {
                Object[] args = new Object[damageParamCount];
                args[0] = player;
                args[1] = item;
                args[2] = Integer.valueOf(damageAmount);   
                args[3] = Boolean.TRUE;                     
                if (damageParamCount >= 5) {
                    args[4] = null;                          
                }
                
                damageMethod.invoke(damageIsStatic ? null : itemDurabilityInstance, args);
                
                return item;
            }
            
            Object[] args;
            if (addCustomDurabilityParamCount == 3) {
                args = new Object[]{item, Integer.valueOf(-damageAmount), Boolean.TRUE};
            } else {
                args = new Object[]{item, Integer.valueOf(-damageAmount)};
            }
            Object ret = addCustomDurabilityMethod.invoke(addCustomDurabilityInstance, args);
            
            if (ret instanceof ItemStack) {
                return (ItemStack) ret;
            }
            return item;
        } catch (Throwable e) {
            if (com.lonelyBlock.LonelyBlock.isDebug()) {
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                        "扣除NeigeItems物品耐久时出错: " + e.getMessage());
            }
            return null;
        }
    }
}
