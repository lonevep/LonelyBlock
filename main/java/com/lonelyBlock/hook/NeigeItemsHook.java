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

/**
 * NeigeItems 物品库 Hook
 * 通过反射兼容各个版本的 NeigeItems API
 *
 * NeigeItems 是 Kotlin 编写的插件, 反射时需考虑:
 *   - Kotlin object 通过 INSTANCE 静态字段暴露实例
 *   - JavaPlugin 子类通过 Bukkit.getPluginManager().getPlugin() 获取
 *   - Kotlin 方法带默认参数时会生成多个重载 (String) / (String, Player) / (String, Player, boolean) 等
 *
 * 支持的API路径:
 *   v2: pers.neige.neigeitems.NeigeItems (Kotlin, 继承 JavaPlugin)
 *       Bukkit.getPluginManager().getPlugin("NeigeItems").getItemsManager().getItemStack(id, player, ...)
 *   v3+: ink.neokomi.light.application.NeigeItems / 其他包路径
 *
 * 物品提取流程:
 *   1. 获取 NeigeItems 插件实例
 *   2. 通过 INSTANCE 字段或直接使用插件实例
 *   3. 调用 getItemsManager() 获取物品管理器
 *   4. 调用 getItemStack(id, ...) 获取物品 (兼容多种参数签名)
 */
public final class NeigeItemsHook {

    private static boolean enabled = false;
    private static String version = "unknown";

    private static Object itemsManagerInstance;
    /** 获取物品的方法 (可能含多个参数, 调用时按签名填充默认值) */
    private static Method getItemMethod;
    /** 方法参数类型列表 (用于调用时构造参数) */
    private static Class<?>[] getItemParamTypes;

    // ================================================================
    // NeigeItems 自定义耐久 API (反射调用)
    // NeigeItems 物品有独立耐久系统, 存储在 NBT 路径 NeigeItems.durability (Int)
    // 原版 ItemStack.setDurability() 改的只是显示镜像, 会被 NI 反向覆盖
    // 正确做法: 调用 ItemManager.addCustomDurability(item, amount) 增减耐久
    // ================================================================
    /** isNiItem(ItemStack) 方法 (判断是否为 NI 物品, 返回 ItemInfo 或 null) */
    private static Method isNiItemMethod;
    /** isNiItem 是否为静态方法 (true: invoke(null); false: invoke(receiver)) */
    private static boolean isNiItemIsStatic = false;
    /** isNiItem 非 @JvmStatic 时的接收者实例 (ItemUtils.INSTANCE 或 ItemManager.INSTANCE) */
    private static Object isNiItemInstance;

    /** addCustomDurability 方法 (Kotlin @JvmStatic 默认参数生成 3 参版本: ItemStack,int,boolean) */
    private static Method addCustomDurabilityMethod;
    /** addCustomDurability 是否为静态方法 */
    private static boolean addCustomDurabilityIsStatic = false;
    /** addCustomDurability 非静态时的接收者实例 (ItemManager INSTANCE / itemsManagerInstance) */
    private static Object addCustomDurabilityInstance;
    /** addCustomDurability 参数个数 (2 或 3), 决定调用时是否传 itemBreak=true */
    private static int addCustomDurabilityParamCount = 0;

    // ================================================================
    // 新版 NeigeItems-Kotlin 耐久 API (pers.neige.neigeitems.item.ItemDurability.damage)
    // NeigeItems-Kotlin (ankhorg fork) 将耐久逻辑迁移到独立的 ItemDurability Kotlin object:
    //   @JvmStatic fun damage(player, itemStack, damage=1, breakItem=true, damageEvent=null): DamageResult
    // 返回 DamageResult 枚举: VANILLA / BREAK / BROKEN_ITEM / ZERO_DAMAGE / SUCCESS / INVALID_DAMAGE
    // 该方法内部已处理 Unbreaking 附魔概率减免, 调用方无需重复处理.
    // ================================================================
    /** 新版 ItemDurability.damage 方法 */
    private static Method damageMethod;
    /** ItemDurability.INSTANCE (Kotlin object 单例), damage 为静态时为 null */
    private static Object itemDurabilityInstance;
    /** damage 方法是否为静态 */
    private static boolean damageIsStatic = false;
    /** damage 方法参数个数 (4 或 5, 取决于是否含 damageEvent 参数) */
    private static int damageParamCount = 0;

    /** 耐久 API 类型: "new" (ItemDurability.damage) / "old" (addCustomDurability) / "none" */
    private static String durabilityApiType = "none";
    /** NI 自定义耐久 API 是否可用 */
    private static boolean durabilityApiAvailable = false;
    /** isNeigeItemsItem 诊断日志计数器 (前 5 次调用输出详细中间结果, 便于定位 NI 物品识别失败) */
    private static volatile int diagnoseLogCount = 0;

    // ================================================================
    // NMS NBT 反射缓存 (用于 isNiItemMethod 不可用时, 直接读 NBT 判断是否 NI 物品)
    // NeigeItems 物品的根 NBT 含 "NeigeItems" 复合标签, 是 NI 物品的可靠标识.
    // 跨版本兼容策略: 不依赖具体方法名 (1.17+ Spigot 反混淆名多变),
    //   而是通过返回类型/参数类型匹配, 兼容 1.8 - 1.20.4 (Paper/Spigot).
    // ================================================================
    private static volatile boolean nbtReflectionInit = false;
    private static volatile boolean nbtReflectionOk = false;
    private static Class<?> craftItemStackClass;
    private static Method asNMSCopyMethod;     // CraftItemStack.asNMSCopy(ItemStack) -> NMS ItemStack
    private static Method nmsGetTagMethod;     // NMS ItemStack.getTag() -> NBTTagCompound/CompoundTag (可能返回 null)
    private static Method nmsGetOrCreateTagMethod; // NMS ItemStack.getOrCreateTag() (永不返回 null, getTag 不可用时的兜底)
    private static Method nbtHasKeyMethod;     // NBTTagCompound.hasKey(String)/contains(String) -> boolean
    // 读取 NI 物品 ID 所需的 NBT 方法 (NeigeItems.id 路径)
    private static Method nbtGetCompoundMethod; // NBTTagCompound.getCompound(String) -> NBTTagCompound
    private static Method nbtGetStringMethod;   // NBTTagCompound.getString(String) -> String

    private NeigeItemsHook() {
    }

    /**
     * 初始化 NeigeItems Hook
     *
     * @return 是否成功加载
     */
    public static boolean setup() {
        Plugin niPlugin = Bukkit.getPluginManager().getPlugin("NeigeItems");
        if (niPlugin == null) {
            // 兼容可能的别名 (大小写/带空格)
            niPlugin = findPluginByNameVariants("NeigeItems", "neigeitems", "Neige Items", "NeigeItemsPlugin");
        }
        if (niPlugin == null) {
            return false;
        }
        version = niPlugin.getDescription().getVersion();

        // 尝试多个可能的类路径 (适配不同版本的主类)
        String[] classNames = {
                "pers.neige.neigeitems.NeigeItems",           // v2.x
                "ink.neokomi.light.application.NeigeItems",   // v3.x+
                "ink.neokomi.light.NeigeItems",               // 备用
                "pers.neige.neigeitems.NeigeItemsPlugin"      // 备用
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
                // 尝试下一个类名
            } catch (Throwable ignored) {
            }
        }

        // Fallback 1: 直接通过插件实例查找方法 (JavaPlugin 单例, NeigeItems v2 实际走这条路径)
        if (extractItemManager(niPlugin)) {
            enabled = true;
            initDurabilityAPI(itemsManagerInstance);
            return true;
        }

        // Fallback 2: 新版 NeigeItems-Kotlin, ItemManager 是独立 Kotlin object 单例,
        // 主类没有 getItemsManager() 方法. 直接加载 ItemManager object 类用 INSTANCE.
        if (loadItemManagerObject()) {
            enabled = true;
            initDurabilityAPI(itemsManagerInstance);
            return true;
        }

        // 所有路径均失败, 输出诊断信息便于定位
        com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                "NeigeItems 已加载 (版本=" + version + ") 但无法定位 ItemManager: "
                        + "主类无 getItemsManager() 方法, 且 pers.neige.neigeitems.manager.ItemManager 加载失败. "
                        + "请反馈 NeigeItems 版本与主类名给插件作者.");

        return false;
    }

    /**
     * 初始化 NeigeItems 自定义耐久 API 反射
     *
     * 加载以下方法 (优先从实际加载的 ItemManager 实例类查找, 适配任意 NI 版本/包路径):
     *   - isNiItem(ItemStack) -> ItemInfo (null 表示非 NI 物品)
     *     优先从 ItemUtils 查找 (@JvmStatic 静态方法);
     *     若不存在则从 ItemManager 查找 (非 @JvmStatic, 需通过 INSTANCE 调用)
     *   - ItemManager#addCustomDurability(ItemStack, int[, boolean])
     *     Kotlin @JvmStatic + 默认参数 (itemBreak=true) 生成 3 参真实方法 (ItemStack,int,boolean);
     *     某些老版本可能是 2 参 (ItemStack,int). 自动识别参数个数.
     *
     * 失败时静默降级 (durabilityApiAvailable 保持 false, 调用方回退原版耐久逻辑)
     */
    private static void initDurabilityAPI(Object itemsManager) {
        try {
            Class<?> itemManagerClass = itemsManager != null ? itemsManager.getClass() : null;

            // 1. 加载 isNiItem 方法 (优先 ItemUtils, 兜底 ItemManager)
            loadIsNiItemMethod(itemManagerClass);

            // 2. 优先搜索新版 ItemDurability.damage (NeigeItems-Kotlin ankhorg fork)
            loadDamageMethod();

            // 3. 新版不可用时, 搜索旧版 addCustomDurability (NeigeItems v2 旧版)
            if (damageMethod == null && itemManagerClass != null) {
                loadAddCustomDurability(itemManagerClass, itemsManager);
            }

            // 4. 确定耐久 API 类型与可用性
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

            // 预初始化 NMS NBT 反射 (用于 isNiItemMethod 不可用时的 NI 物品判断)
            initNbtReflection();

            // 启动时明确输出 NI 自定义耐久支持状态 (非 debug 级别, 便于用户确认功能是否生效)
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

            // 启动时输出 NI hook 完整状态 (INFO 级别, 便于用户确认功能是否生效)
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

    /**
     * 加载新版 NeigeItems-Kotlin 的 ItemDurability.damage 方法
     *
     * 目标类: pers.neige.neigeitems.item.ItemDurability (Kotlin object)
     * 目标方法: @JvmStatic fun damage(player, itemStack, damage=1, breakItem=true, damageEvent=null): DamageResult
     *
     * Kotlin 默认参数编译生成:
     *   - 真实方法 damage(Player, ItemStack, int, boolean, PlayerItemDamageEvent) — 5 参
     *   - 合成方法 damage$default(..., int bitmask, Object) — 跳过
     *
     * 兼容性: 同时支持 4 参 (无 damageEvent) 和 5 参版本
     */
    private static void loadDamageMethod() {
        String[] candidateClassNames = {
                "pers.neige.neigeitems.item.ItemDurability",       // NeigeItems-Kotlin ankhorg fork
                "pers.neige.neigeitems.manager.ItemDurability",    // 备用路径
        };
        for (String className : candidateClassNames) {
            try {
                Class<?> itemDurabilityClass = Class.forName(className);
                // 遍历所有 public 方法, 查找 damage
                Method best = null;
                for (Method m : itemDurabilityClass.getMethods()) {
                    if (!m.getName().equals("damage")) continue;
                    if (m.isSynthetic() || m.getName().contains("$default")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length < 4) continue;
                    // 前 4 个参数: Player, ItemStack, int, boolean
                    if (p[0] != Player.class) continue;
                    if (p[1] != ItemStack.class) continue;
                    if (!typesCompatible(p[2], int.class)) continue;
                    if (!typesCompatible(p[3], boolean.class)) continue;
                    // 优先 4 参 (无 damageEvent), 次选 5 参
                    if (p.length == 4) {
                        best = m;
                        break; // 4 参最优, 直接选定
                    } else if (p.length == 5 && best == null) {
                        best = m; // 5 参作为备选
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
                // 尝试下一个类名
            }
        }
    }

    /**
     * 加载 isNiItem(ItemStack) 方法
     *
     * 搜索多个候选类和方法名, 兼容不同 NeigeItems 版本:
     *   - pers.neige.neigeitems.utils.ItemUtils (Kotlin object, @JvmStatic)
     *   - pers.neige.neigeitems.manager.ItemManager (Kotlin object)
     *   - pers.neige.neigeitems.item.ItemUtils (备用路径)
     *   - ink.neokomi.light.application.utils.ItemUtils (v3.x)
     *
     * 方法名候选: isNiItem, isNIItem, isNeigeItem
     * 若精确名未找到, 兜底搜索单参 ItemStack 返回非 void 的方法 (可能是 getNiItemInfo 等)
     */
    private static void loadIsNiItemMethod(Class<?> itemManagerClass) {
        // 候选搜索类列表 (按优先级)
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

        // 候选方法名
        String[] methodNames = {"isNiItem", "isNIItem", "isNeigeItem"};

        // 第一轮: 按候选方法名精确匹配
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

        // 第二轮: 兜底搜索单参 ItemStack 返回非 void/非 primitive 的方法
        // (可能是 getNiItemInfo, getItemInfo 等, 返回 ItemInfo 对象, null 表示非 NI 物品)
        for (Class<?> clazz : searchClasses) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != ItemStack.class) continue;
                Class<?> rt = m.getReturnType();
                if (rt == void.class || rt.isPrimitive()) continue;
                String mn = m.getName();
                // 跳过 getItemStack (获取物品, 非判定) 和耐久相关方法
                if (mn.contains("getItemStack") || mn.contains("Durability") || mn.contains("damage")) continue;
                // 候选: 含 "Ni" 或 "Item" 且返回对象类型的方法
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

    /**
     * 加载 addCustomDurability 方法
     *
     * Kotlin @JvmStatic fun addCustomDurability(item, amount, itemBreak=true) 编译生成:
     *   - 真实 3 参静态方法 addCustomDurability(ItemStack, int, boolean)
     *   - 合成 $default 方法 addCustomDurability$default(ItemStack, int, boolean, int, Object)
     * 老版本可能是 2 参 addCustomDurability(ItemStack, int).
     *
     * 搜索顺序 (适配不同 NI 版本中该方法所在位置):
     *   1. ItemManager 类 (itemsManager.getClass()) — 最常见位置, NI v2 主力 API
     *   2. pers.neige.neigeitems.utils.ItemUtils — 部分版本将其作为 @JvmStatic 工具方法
     *   3. itemsManager 的所有父类/接口 — 兜底
     *
     * 本方法遍历所有 public 方法, 跳过 $default 合成方法, 优先选 2 参, 次选 3 参.
     */
    private static void loadAddCustomDurability(Class<?> itemManagerClass, Object itemsManager) {
        // 候选搜索类列表: ItemManager 实际类 + ItemUtils (部分版本耐久 API 在此)
        java.util.List<Class<?>> searchClasses = new java.util.ArrayList<>();
        if (itemManagerClass != null) {
            searchClasses.add(itemManagerClass);
        }
        // ItemUtils 兜底: 部分版本 addCustomDurability 是 ItemUtils 的 @JvmStatic 方法
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
            // 该类的接收者实例: ItemManager 用 itemsManager; ItemUtils (Kotlin object) 用 INSTANCE; 静态方法用 null
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

    /**
     * 解析某个搜索类的方法接收者实例
     *   - ItemManager 实际类: 用 itemsManager (getItemsManager() 返回值)
     *   - 其它类 (ItemUtils 等 Kotlin object): 通过 INSTANCE 字段获取单例
     */
    private static Object resolveReceiver(Class<?> searchClass, Class<?> itemManagerClass, Object itemsManager) {
        if (searchClass == itemManagerClass) {
            return itemsManager;
        }
        return getKotlinInstance(searchClass);
    }

    /**
     * 在指定类中扫描 addCustomDurability 方法 (跳过 $default 合成方法, 优先 2 参次选 3 参)
     */
    private static Method scanForAddCustomDurability(Class<?> clazz) {
        Method twoArg = null;
        Method threeArg = null;
        for (Method m : clazz.getMethods()) {
            String name = m.getName();
            if (!name.equals("addCustomDurability")) {
                continue;
            }
            // 跳过 Kotlin $default 合成方法 (含 $default 后缀或 isSynthetic)
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

    /**
     * 获取 Kotlin object 的 INSTANCE 单例 (静态字段)
     */
    private static Object getKotlinInstance(Class<?> clazz) {
        try {
            Field f = clazz.getField("INSTANCE");
            return f.get(null);
        } catch (Throwable ignored) {
        }
        // 兜底: 查找 INSTANCE$ 字段
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

    /**
     * 在类中查找指定名称和参数类型的方法 (含 public 方法)
     *
     * 注意: Kotlin 的 @JvmStatic 扩展函数 (如 addCustomDurability) 在 Dokka 文档中
     * 可能显示为包装类型 (Integer/Boolean), 但编译后可能是基本类型 (int/boolean),
     * 反之亦然. 因此本方法对基本类型与包装类型做兼容匹配.
     */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        // 1. 精确匹配 (含基本类型)
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {
        }
        // 2. 兜底: 遍历所有 public 方法, 名称 + 参数个数 + 类型兼容匹配
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != paramTypes.length) {
                continue;
            }
            // 跳过 Kotlin $default 合成方法, 避免误选带 bitmask 的合成重载
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

    /**
     * 判断两个类型是否兼容 (基本类型 <-> 包装类型 视为兼容)
     * 解决 Kotlin @JvmStatic 方法参数在 int/Integer、boolean/Boolean 间的差异
     */
    private static boolean typesCompatible(Class<?> actual, Class<?> expected) {
        if (actual == expected) {
            return true;
        }
        if (actual.isPrimitive() || expected.isPrimitive()) {
            return primitiveWrapper(actual) == primitiveWrapper(expected);
        }
        return actual.isAssignableFrom(expected) || expected.isAssignableFrom(actual);
    }

    /**
     * 将基本类型映射到包装类型 (若不是基本类型则返回自身)
     */
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

    /**
     * 按名称变体查找插件 (大小写不敏感)
     */
    private static Plugin findPluginByNameVariants(String... names) {
        for (String name : names) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null) return p;
        }
        return null;
    }

    /**
     * 从类中获取实例 (Kotlin object 或 Java 单例)
     */
    private static Object getInstance(Class<?> clazz) {
        // 尝试 INSTANCE 字段 (Kotlin object)
        try {
            Field field = clazz.getField("INSTANCE");
            return field.get(null);
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable ignored) {
        }

        // 尝试 getInstance() 方法
        try {
            Method method = clazz.getMethod("getInstance");
            return method.invoke(null);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // 尝试 inst() 方法
        try {
            Method method = clazz.getMethod("inst");
            return method.invoke(null);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // 尝试 getPlugin() 方法
        try {
            Method method = clazz.getMethod("getPlugin");
            return method.invoke(null);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * 从实例中提取 ItemManager 并查找 getItem 方法
     * 兼容 Kotlin 默认参数生成的多个重载方法
     */
    private static boolean extractItemManager(Object instance) {
        try {
            // 查找返回 ItemManager 的方法
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

    /**
     * 在 itemsManager 实例上查找获取物品的方法 (getItemStack(String) 等重载).
     *
     * 兼容 Kotlin 默认参数生成的多个重载, 优先选择参数最少 (且第一参数为 String) 的版本.
     * 新版 NeigeItems-Kotlin 的 getItemStack 有 6 个重载:
     *   getItemStack(String) / (String, OfflinePlayer) / (String, String) /
     *   (String, Map) / (String, OfflinePlayer, String) / (String, OfflinePlayer, Map)
     * 选 (String) 1 参版本最简, 内部会用 null player + null data 调用完整版.
     *
     * 成功后设置 getItemMethod / getItemParamTypes / itemsManagerInstance.
     *
     * @param itemsManager ItemManager 实例 (旧版来自 getItemsManager(), 新版来自 Kotlin object INSTANCE)
     * @return true 表示找到可用方法
     */
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
                    // 跳过带 int bitmask 的 Kotlin 默认参数合成方法 (避免选到 $default 方法)
                    if (m.getName().contains("$default")) continue;
                    if (isSyntheticKotlinDefault(m, params)) continue;
                    // 选择参数最少的版本
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

    /**
     * 新版 NeigeItems-Kotlin: ItemManager 是独立 Kotlin object 单例
     * (pers.neige.neigeitems.manager.ItemManager), 主类没有 getItemsManager() 方法,
     * 通过 INSTANCE 字段暴露实例.
     *
     * 本方法直接加载 ItemManager object 类, 用 INSTANCE 作为 itemsManagerInstance,
     * 然后调用 findGetItemMethod 查找 getItemStack(String) 等方法.
     * 这是新版 NeigeItems (ankhorg fork) 的主力加载路径.
     *
     * @return true 表示成功加载 ItemManager 并找到 getItemStack 方法
     */
    private static boolean loadItemManagerObject() {
        String[] candidateClassNames = {
                "pers.neige.neigeitems.manager.ItemManager",   // NeigeItems-Kotlin (ankhorg fork)
                "pers.neige.neigeitems.ItemManager",            // 备用路径
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
                // 尝试下一个类名
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 判断是否为 Kotlin 默认参数合成的 $default 方法
     * 这类方法通常末尾参数为 int (bitmask) + Object (DefaultConstructorMarker/DefaultItemStackMarker)
     */
    private static boolean isSyntheticKotlinDefault(Method m, Class<?>[] params) {
        if (params.length < 3) return false;
        Class<?> last = params[params.length - 1];
        Class<?> secondLast = params[params.length - 2];
        // Kotlin $default 方法签名: (...原参数..., int bitmask, Object marker)
        return secondLast == int.class && last == Object.class;
    }

    /**
     * 检查 NeigeItems 是否可用
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取 NeigeItems 版本
     */
    public static String getVersion() {
        return version;
    }

    /**
     * 从 NeigeItems 物品库获取物品
     * 兼容多种方法签名:
     *   getItemStack(String)
     *   getItemStack(String, Player)
     *   getItemStack(String, Player, boolean)
     *   ...
     *
     * @param itemId 物品ID
     * @return 物品 ItemStack, 如果未找到返回 null
     */
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
            // 处理 Optional 结果
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

    /**
     * 根据方法参数类型构造调用参数
     * 第一参数为 itemId (String), 其余参数填充默认值 (null/0/false)
     */
    private static Object[] buildInvokeArgs(Class<?>[] paramTypes, String itemId) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (i == 0) {
                args[i] = itemId;
                continue;
            }
            Class<?> t = paramTypes[i];
            if (t == boolean.class) {
                args[i] = Boolean.TRUE;  // parse: true (解析物品lore/变量)
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
                args[i] = null;  // 引用类型填 null (Player/Map 等)
            }
        }
        return args;
    }

    /**
     * 检查物品是否存在
     */
    public static boolean containsItem(String itemId) {
        return getItem(itemId) != null;
    }

    // ================================================================
    // NeigeItems 自定义耐久 API (供 BlockBreakListener 调用)
    // ================================================================

    /**
     * NeigeItems 自定义耐久 API 是否可用
     * (NeigeItems 已加载且反射到 damage 或 addCustomDurability 方法)
     *
     * 注意: 新版 NeigeItems-Kotlin 将耐久 API 迁移到 ItemDurability.damage, 旧版用 addCustomDurability.
     *       二者只要反射到任一即可, 不能只判断 addCustomDurabilityMethod (否则新版 NI 永远判定为不可用).
     */
    public static boolean isDurabilityApiAvailable() {
        return enabled && durabilityApiAvailable
                && (damageMethod != null || addCustomDurabilityMethod != null);
    }

    /**
     * 当前耐久 API 是否在内部处理耐久附魔 (Unbreaking) 概率减免.
     *   - 新版 ItemDurability.damage: 内部已按原版公式处理 Unbreaking, 调用方不应再做预检 (否则双重减免)
     *   - 旧版 addCustomDurability: 不处理 Unbreaking, 调用方需自行预检
     *
     * @return true 表示调用方不应再做 Unbreaking 预检
     */
    public static boolean durabilityApiHandlesUnbreaking() {
        return "new".equals(durabilityApiType);
    }

    /**
     * 判断物品是否为 NeigeItems 物品 (含自定义耐久系统)
     *
     * 优先调用 isNiItem(item):
     *   - 若 isNiItem 在 ItemUtils 中 (@JvmStatic): 静态调用 invoke(null, item)
     *   - 若 isNiItem 在 ItemManager 中 (非 @JvmStatic): 通过 INSTANCE 调用 invoke(INSTANCE, item)
     *
     * 重要: isNiItem 返回 null 时不直接判定为非 NI 物品, 而是继续走 NBT 兜底.
     *   原因: 耐久变更后 (ItemDurability.damage / addCustomDurability), 物品的 NBT 已被修改,
     *   isNiItem 可能因物品不再与注册模板完全匹配而返回 null.
     *   但 "NeigeItems" 复合标签始终保留 (是 NI 物品的持久标识),
     *   因此以 NBT 标签作为最终判据, 确保二次挖掘仍能正确识别 NI 物品.
     *
     * @param item 物品
     * @return true 表示该物品受 NeigeItems 自定义耐久系统管理
     */
    public static boolean isNeigeItemsItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        // 诊断日志: 前 5 次调用记录中间结果 (便于定位 NI 物品识别失败)
        boolean doDiagnose = diagnoseLogCount < 5;
        StringBuilder diag = doDiagnose ? new StringBuilder() : null;
        if (doDiagnose) {
            diagnoseLogCount++;
            diag.append("itemType=").append(item.getType())
                    .append(" hasItemMeta=").append(item.hasItemMeta());
        }

        // 优先使用 isNiItem(item): 返回非 null 则确认为 NI 物品
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
                // isNiItem 返回 null: 可能是耐久变更后不再匹配模板, 继续走 NBT 兜底
            } catch (Throwable e) {
                if (doDiagnose) {
                    diag.append(" | isNiItem异常=").append(e.getClass().getSimpleName()).append(":").append(e.getMessage());
                }
                // 反射调用失败, 走 NBT 兜底
            }
        } else {
            if (doDiagnose) {
                diag.append(" | isNiItemMethod=null(未加载)");
            }
        }
        // NBT 兜底: 直接读 NMS NBT, 检查是否含 "NeigeItems" 复合标签 (NI 物品的可靠标识)
        boolean nbtResult = hasNeigeItemsNbtTag(item);
        if (doDiagnose) {
            diag.append(" | NBT检测结果=").append(nbtResult);
            logDiagnose(diag.toString(), nbtResult);
        }
        return nbtResult;
    }

    /**
     * 输出 NI 物品检测诊断日志 (前 5 次 isNeigeItemsItem 调用)
     */
    private static void logDiagnose(String detail, boolean result) {
        try {
            com.lonelyBlock.LonelyBlock.getInstance().getLogger().info(
                    "[NeigeItems诊断#" + diagnoseLogCount + "] 最终判定=" + result + " | " + detail);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从物品 NBT 中提取 NeigeItems 物品 ID (NeigeItems.id 路径).
     *
     * 用途: 工具匹配时按 NI 物品 ID 比对, 而非按 ItemMeta 相似性比对.
     *   原因: NI 物品挖掘后耐久值变化, ItemMeta.equals 会判定为不相似, 导致工具匹配失败.
     *   而 NBT 中的 "NeigeItems.id" 字段不受耐久变更影响, 是稳定的物品标识.
     *
     * @param item 物品
     * @return NI 物品 ID; 非 NI 物品或读取失败返回 null
     */
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
            // 1. 必须先用 hasKey 检查 "NeigeItems" 是否存在.
            //    关键陷阱: NBTTagCompound.getCompound(key) 对不存在的 key 返回一个新的空
            //    CompoundTag 而非 null, 若不预检会拿到空 compound, 后续读取返回空值.
            Object hasNi = nbtHasKeyMethod.invoke(tag, "NeigeItems");
            if (!Boolean.TRUE.equals(hasNi)) {
                return null;
            }
            // 2. 读取 NeigeItems 复合标签
            Object niSection = nbtGetCompoundMethod.invoke(tag, "NeigeItems");
            if (niSection == null) {
                return null;
            }
            // 3. 检查 id 字段是否存在
            Object hasId = nbtHasKeyMethod.invoke(niSection, "id");
            if (!Boolean.TRUE.equals(hasId)) {
                return null;
            }
            // 4. 通过 SNBT toString 解析 id 值 (不使用 getString 反射).
            //    原因: Spigot 反混淆环境下 NBTTagCompound 方法名被映射成单字母 (如 getString->s,
            //    getCompound->p), findMethodByNameFirst 找不到 "getString" 名, 回退到按返回类型
            //    匹配会误选返回 NBT 类型名的方法 (对 String 类型字段返回 "STRING" 而非实际值).
            //    而 NBT.toString() 输出标准 SNBT 格式 {id:"石镐",durability:100,...},
            //    解析此字符串可可靠提取 id 值, 完全绕过方法名反混淆问题.
            String snbt = safeNbtToString(niSection);
            String id = parseStringFromSnbt(snbt, "id");
            if (id == null || id.isEmpty()) {
                // 诊断: SNBT 中未找到 id 字段, 输出 SNBT 便于定位
                com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                        "[NeigeItems NBT警告] SNBT 解析未找到 id 字段. SNBT=" + snbt);
                return null;
            }
            return id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 从 NBT SNBT 字符串中提取指定 key 的字符串值.
     *
     * SNBT 格式示例: {data:"{}",durability:100,id:"石镐",itemBreak:0b}
     * 仅匹配顶层 key (key 前为 { 或 , 或行首), 不深入嵌套复合标签, 避免误匹配.
     *
     * 用途: 绕过 Spigot 反混淆下 NBTTagCompound.getString 方法名不可靠的问题
     *       (反混淆后 getString 被映射成单字母, 按返回类型匹配会误选返回类型名的方法).
     *
     * @param snbt NBT.toString() 输出
     * @param key  要提取的键名
     * @return 键对应的字符串值 (已反转义); 未找到返回 null
     */
    private static String parseStringFromSnbt(String snbt, String key) {
        if (snbt == null || snbt.isEmpty() || key == null || key.isEmpty()) {
            return null;
        }
        // 匹配顶层 key:"..." (key 前是行首/{/, 确保不匹配嵌套复合标签内的同名字段)
        // 字符串值用双引号包裹, 支持转义 \" \\ \n \t \r
        String regex = "(?:^|[,{])\\s*" + java.util.regex.Pattern.quote(key)
                + "\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(snbt);
        if (m.find()) {
            return unescapeSnbtString(m.group(1));
        }
        return null;
    }

    /**
     * 反转义 SNBT 字符串 (\" -> ", \\ -> \, \n -> 换行 等)
     */
    private static String unescapeSnbtString(String s) {
        if (s == null) return null;
        if (s.indexOf('\\') < 0) return s; // 无转义字符, 直接返回
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

    /**
     * 安全获取 NBT 对象的字符串表示 (用于诊断日志)
     */
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

    /**
     * 获取 NMS ItemStack 的 NBT 标签 (getTag/getOrCreateTag 双重兜底).
     *
     * 优先 getTag() (语义准确, 不修改物品); 若 getTag 返回 null 或方法不存在,
     * 回退 getOrCreateTag() (永不返回 null, 会在无 NBT 物品上创建空标签).
     *
     * 兼容性:
     *   - 1.8~1.20.4: getTag 存在, 对有 NBT 的物品返回非 null
     *   - 1.20.5+: getTag 被移除, getOrCreateTag 仍可用 (数据组件时代)
     *   - asNMSCopy 返回副本, getOrCreateTag 创建的空标签不影响原物品
     *
     * @param nmsItem NMS ItemStack (由 asNMSCopy 获得)
     * @return NBT 标签对象; 获取失败返回 null
     */
    private static Object getNmsTag(Object nmsItem) {
        if (nmsItem == null) {
            return null;
        }
        // 1. 优先 getTag (不修改物品, 对有 NBT 的物品返回非 null)
        if (nmsGetTagMethod != null) {
            try {
                Object tag = nmsGetTagMethod.invoke(nmsItem);
                if (tag != null) {
                    return tag;
                }
                // getTag 返回 null: 物品可能无 NBT, 尝试 getOrCreateTag 确认
            } catch (Throwable ignored) {
                // getTag 调用异常, 尝试 getOrCreateTag
            }
        }
        // 2. 兜底 getOrCreateTag (永不返回 null; 若物品无 NBT 会创建空标签)
        if (nmsGetOrCreateTagMethod != null) {
            try {
                return nmsGetOrCreateTagMethod.invoke(nmsItem);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 通过 NMS NBT 反射判断物品是否含 NeigeItems 标签
     *
     * NeigeItems 物品的根 NBT 含 "NeigeItems" 复合标签 (存储 id/durability 等),
     * 这是判断 NI 物品最可靠的方式 (不依赖 isNiItem 方法是否存在).
     *
     * 跨版本兼容: 通过返回类型/参数类型匹配方法, 而非依赖具体 (反混淆) 方法名:
     *   - CraftItemStack.asNMSCopy(ItemStack) -> NMS ItemStack (所有版本同名)
     *   - NMS ItemStack 无参方法返回 NBTTagCompound/CompoundTag (getTag/getOrCreateTag)
     *   - NBTTagCompound 单参 String 返回 boolean 的方法 (hasKey/contains)
     */
    private static boolean hasNeigeItemsNbtTag(ItemStack item) {
        if (!initNbtReflection()) {
            // NMS 反射不可用, 兜底用 serialize (精度有限, 但好过无)
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

    /**
     * NMS 反射不可用时的兜底 (通过 ItemMeta 序列化/toString 粗略判断, 精度有限)
     *
     * CraftItemMeta.toString() 在多数 CraftBukkit 版本中包含 internal=NBTTagCompound{...} 表示,
     * 其中含 "NeigeItems" 标签, 比 serialize() (仅含 Bukkit 元数据) 更可靠.
     */
    private static boolean hasNeigeItemsNbtTagFallback(ItemStack item) {
        try {
            if (!item.hasItemMeta()) {
                return false;
            }
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return false;
            }
            // 优先: toString (CraftItemMeta 通常含 internal NBT 表示)
            String dump = meta.toString();
            if (dump.contains("NeigeItems")) {
                return true;
            }
            // 兜底: serialize (部分版本可能在序列化数据中含 NeigeItems)
            java.util.Map<String, Object> serialized = meta.serialize();
            return serialized.toString().contains("NeigeItems");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 初始化 NMS NBT 反射 (线程安全, 仅初始化一次)
     *
     * @return true 表示反射就绪, 可用于读取 NBT
     */
    private static boolean initNbtReflection() {
        if (nbtReflectionInit) {
            return nbtReflectionOk;
        }
        synchronized (NeigeItemsHook.class) {
            if (nbtReflectionInit) {
                return nbtReflectionOk;
            }
            try {
                // CraftItemStack 类 (1.8-1.16 带版本包名, 1.17+ Paper 无版本包名)
                String cbPkg = Bukkit.getServer().getClass().getPackage().getName();
                String craftItemStackClassName = cbPkg + ".inventory.CraftItemStack";
                craftItemStackClass = Class.forName(craftItemStackClassName);

                // asNMSCopy(ItemStack) -> NMS ItemStack (所有版本同名, 静态方法)
                asNMSCopyMethod = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
                Class<?> nmsItemStackClass = asNMSCopyMethod.getReturnType();

                // 查找 getTag: NMS ItemStack 上无参方法, 返回类型简单名为 NBTTagCompound / CompoundTag
                // 优先按名 getTag 匹配 (不创建新标签, 语义更准确)
                nmsGetTagMethod = findNoArgMethodByReturnName(nmsItemStackClass,
                        new String[]{"getTag"},
                        new String[]{"NBTTagCompound", "CompoundTag"});
                // 查找 getOrCreateTag 作为兜底 (1.20.5+ getTag 被移除; getOrCreateTag 永不返回 null)
                // 注意: getOrCreateTag 会在无 NBT 的物品上创建空标签, 但 asNMSCopy 返回的是副本, 无副作用
                nmsGetOrCreateTagMethod = findNoArgMethodByReturnName(nmsItemStackClass,
                        new String[]{"getOrCreateTag"},
                        new String[]{"NBTTagCompound", "CompoundTag"});
                if (nmsGetTagMethod == null && nmsGetOrCreateTagMethod == null) {
                    // getTag 和 getOrCreateTag 均未找到 (极罕见, 可能是极新版本完全移除 NBT API)
                    nbtReflectionInit = true;
                    nbtReflectionOk = false;
                    com.lonelyBlock.LonelyBlock.getInstance().getLogger().warning(
                            "[NeigeItems NBT反射] 未找到 getTag/getOrCreateTag 方法, NI 物品 NBT 检测将使用兜底 (精度有限)");
                    return false;
                }
                // NBT 类从可用的方法返回类型获取 (getTag 和 getOrCreateTag 返回类型相同)
                Class<?> nbtClass = (nmsGetTagMethod != null ? nmsGetTagMethod : nmsGetOrCreateTagMethod).getReturnType();

                // 查找 hasKey/contains: NBT 上单参 String 返回 boolean 的方法
                nbtHasKeyMethod = findStringBooleanMethod(nbtClass);
                if (nbtHasKeyMethod == null) {
                    nbtReflectionInit = true;
                    nbtReflectionOk = false;
                    return false;
                }

                // 查找 getCompound: 优先按名 "getCompound" 查找, 避免 findStringReturnMethod 误选其他方法
                // (NBTTagCompound 上可能有多个单参String返回复合标签的方法, 按名匹配最精确)
                nbtGetCompoundMethod = findMethodByNameFirst(nbtClass,
                        new String[]{"getCompound"},
                        new String[]{"NBTTagCompound", "CompoundTag"});
                // 查找 getString: 优先按名 "getString" 查找, 避免误选返回 NBT 类型名的方法
                // (之前 findStringReturnMethod 误选了返回 "STRING" 类型名的方法, 导致 getNeigeItemsItemId 返回 "STRING" 而非物品ID)
                nbtGetStringMethod = findMethodByNameFirst(nbtClass,
                        new String[]{"getString"},
                        new String[]{"String"});

                // 记录找到的方法名 (INFO 级别, 便于确认 NBT 方法正确性)
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

    /**
     * 查找无参且返回类型简单名匹配的方法 (跨版本兼容, 不依赖反混淆方法名)
     * 优先选择方法名在 preferredNames 中的方法, 其次选择返回类型简单名匹配的方法
     *
     * @param clazz             被查找的类
     * @param preferredNames    优先匹配的方法名数组 (可为空)
     * @param returnSimpleNames 返回类型简单名候选 (NBTTagCompound / CompoundTag 等)
     */
    private static Method findNoArgMethodByReturnName(Class<?> clazz, String[] preferredNames,
                                                      String[] returnSimpleNames) {
        Class<?> c = clazz;
        // 第一轮: 按优先方法名 + 返回类型匹配
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
        // 第二轮: 仅按返回类型匹配 (兜底)
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

    /**
     * 查找单参 String 返回 boolean 的方法 (hasKey/contains), 优先 hasKey 次选 contains
     */
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

    /**
     * 查找单参 String 且返回类型简单名匹配候选的方法 (getCompound/getString 等)
     * 用于跨版本定位 NBTTagCompound.getCompound(String) 和 getString(String).
     *
     * @param clazz             被查找的类 (NBTTagCompound)
     * @param returnSimpleNames 返回类型简单名候选 (如 "NBTTagCompound", "String")
     * @return 匹配的方法; 未找到返回 null
     */
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

    /**
     * 优先按方法名查找单参 String 方法, 失败时按返回类型兜底.
     *
     * 用途: 定位 NBTTagCompound.getCompound(String) 和 getString(String).
     * 之前仅按返回类型匹配 (findStringReturnMethod), 在某些版本上会误选返回类型名
     * (如 "STRING") 的方法, 导致 getNeigeItemsItemId 返回 "STRING" 而非物品 ID.
     * 本方法优先按方法名精确匹配, 确保选到真正的 getCompound/getString.
     *
     * @param clazz             被查找的类 (NBTTagCompound)
     * @param preferredNames    优先匹配的方法名数组 (如 "getCompound" / "getString")
     * @param returnSimpleNames 返回类型简单名候选 (兜底用)
     * @return 匹配的方法; 未找到返回 null
     */
    private static Method findMethodByNameFirst(Class<?> clazz, String[] preferredNames,
                                                String[] returnSimpleNames) {
        if (preferredNames != null) {
            Class<?> c = clazz;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != String.class) continue;
                    // 跳过 Kotlin 合成方法
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
        // 兜底: 按返回类型匹配
        return findStringReturnMethod(clazz, returnSimpleNames);
    }

    /**
     * 扣除 NeigeItems 物品的自定义耐久 (统一入口, 自动适配新旧 API).
     *
     * 新版 API (NeigeItems-Kotlin ItemDurability.damage):
     *   damage(player, itemStack, damage, breakItem=true, damageEvent=null) -> DamageResult
     *   - damage 为正数, 表示要扣除的耐久值 (语义与旧版相反)
     *   - 内部已处理 Unbreaking 附魔概率减免 + 堆叠物品拆分 + 物品破碎
     *   - 物品 NBT 原地修改 (Kotlin 直接修改传入 itemStack), 返回值 DamageResult 为枚举:
     *     VANILLA(非NI物品) / BREAK(已破碎) / BROKEN_ITEM(已损坏) / ZERO_DAMAGE / SUCCESS / INVALID_DAMAGE
     *   - 物品破碎时 itemStack.amount 会被减为 0, 调用方据此判断是否清空手中
     *
     * 旧版 API (addCustomDurability):
     *   addCustomDurability(item, amount, itemBreak=true) -> ItemStack
     *   - amount 为负数表示扣除 (语义与新版相反)
     *   - 不处理 Unbreaking (调用方需自行预检)
     *   - 返回修改后的 ItemStack (新对象), 调用方需写回手中
     *
     * @param player      玩家 (新版 API 必需, 旧版可忽略)
     * @param item        物品 (必须是 NI 物品, 否则新版 API 返回 VANILLA, 旧版无副作用)
     * @param damageAmount 要扣除的耐久值 (正数, 如 1)
     * @return 修改后的 ItemStack (调用方应写回手中); API 不可用或调用异常返回 null
     */
    public static ItemStack damageNeigeItemsItem(Player player, ItemStack item, int damageAmount) {
        if (!isDurabilityApiAvailable() || item == null || damageAmount <= 0) {
            return null;
        }
        try {
            // 新版 ItemDurability.damage (5 参: Player, ItemStack, int, boolean, PlayerItemDamageEvent)
            if ("new".equals(durabilityApiType) && damageMethod != null) {
                Object[] args = new Object[damageParamCount];
                args[0] = player;
                args[1] = item;
                args[2] = Integer.valueOf(damageAmount);   // damage (正数扣除)
                args[3] = Boolean.TRUE;                     // breakItem=true
                if (damageParamCount >= 5) {
                    args[4] = null;                          // damageEvent=null
                }
                // damage 为 Kotlin object 成员方法 (非 @JvmStatic) 时需 INSTANCE 接收者
                damageMethod.invoke(damageIsStatic ? null : itemDurabilityInstance, args);
                // 物品 NBT 原地修改, 直接返回原物品 (amount==0 表示已破碎, 调用方据此清空)
                return item;
            }
            // 旧版 addCustomDurability (2 参 / 3 参), amount 传负数表示扣除
            Object[] args;
            if (addCustomDurabilityParamCount == 3) {
                args = new Object[]{item, Integer.valueOf(-damageAmount), Boolean.TRUE};
            } else {
                args = new Object[]{item, Integer.valueOf(-damageAmount)};
            }
            Object ret = addCustomDurabilityMethod.invoke(addCustomDurabilityInstance, args);
            // addCustomDurability 返回修改后的 ItemStack; 若返回 null/void 则视为原地修改, 返回原物品
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
