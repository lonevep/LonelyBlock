package com.lonelyBlock.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 版本工具类 - 处理多版本兼容性
 * 支持 1.8 - 26.x 版本的方块ID匹配、CustomModelData
 *
 * 方块ID格式说明:
 *   "minecraft:oak_log" - 1.13+ 命名空间格式
 *   "OAK_LOG"           - 1.13+ Material名 (大写)
 *   "LOG:0"             - 1.8-1.12 旧格式 (带数据值)
 *   "LOG"               - 1.8-1.12 旧格式 (不指定数据值, 匹配任意变种)
 *
 * 跨版本兼容: 用户在配置中可以同时填写多个版本ID, 插件会自动匹配当前版本可识别的格式
 *
 * 版本号解析: 兼容 "1.x" 与新的年份版本方案 (如 26.2).
 *   统一编码为 <主>.<次>.<修订> -> 主*1000000 + 次*1000 + 修订, 便于跨方案比较.
 *   LEGACY (1.12 及以下的数据值体系) 判定基于编码值 < 1.13, 避免年份版本被误判为旧版.
 *
 * 性能: resolveMaterial 结果按名称缓存; 方块ID列表在配置加载时一次性预解析为
 *   {@link BlockId}, 事件热路径不再重复做字符串解析与 Material 查找.
 *
 * 注: 旧版本曾包含自定义发包裂纹动画 (sendBlockDamage) 与 NMS 按键状态检测
 *     (isPlayerMining) 用于自定义挖掘会话. 现挖掘速度改由 急迫/挖掘疲劳 buff 控制
 *     (见 MiningBuffManager / MiningSpeedCalculator), 让原版挖掘系统工作, 原版裂纹
 *     动画天然同步, 上述旧机制及 ProtocolLib 依赖已全部移除.
 */
public final class VersionUtil {

    /** 1.9 的编码值 (主手 API 起始版本) */
    private static final int MC_1_9 = 1_009_000;
    /** 1.13 的编码值 (扁平化/取消数据值 起始版本) */
    private static final int MC_1_13 = 1_013_000;

    /** 主版本判定 (1.x 取次版本号, 年份版本取主版本号); 仅用于日志展示 */
    private static final int MAJOR_VERSION;
    private static final boolean LEGACY;
    /** 是否支持主手 API (1.9+) */
    private static final boolean SUPPORTS_MAIN_HAND;

    // 缓存 ItemMeta#setCustomModelData(Integer) 方法 (1.14+ 存在)
    private static final Method SET_CUSTOM_MODEL_DATA_METHOD;
    // Material 解析失败的哨兵 (AIR 不会用于方块匹配, 可安全作为"未命中"标记)
    private static final Material MISS = Material.AIR;
    // Material 名称 -> Material 解析缓存 (含失败标记, 避免重复解析失败项)
    private static final Map<String, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();

    static {
        String ver = null;
        try {
            ver = extractVersion(Bukkit.getVersion());
        } catch (Throwable ignored) {
        }
        // 编码后的服务端版本值: 主*1000000 + 次*1000 + 修订 (如 1.21.4 -> 1021004)
        int versionCode = (ver != null && !ver.isEmpty()) ? encode(ver) : MC_1_13;
        // 基于编码值判定, 天然兼容年份版本方案 (26.2 不会被误判为旧版)
        LEGACY = versionCode < MC_1_13;
        SUPPORTS_MAIN_HAND = versionCode >= MC_1_9;
        MAJOR_VERSION = resolveMajor(ver);

        // 缓存 setCustomModelData 方法 (1.14+)
        Method setCmd = null;
        try {
            setCmd = ItemMeta.class.getMethod("setCustomModelData", Integer.class);
        } catch (Throwable ignored) {
        }
        SET_CUSTOM_MODEL_DATA_METHOD = setCmd;
    }

    private VersionUtil() {
    }

    /**
     * 主版本判定: 1.x 取次版本号 (1.21.4 -> 21); 年份版本取主版本号 (26.2 -> 26)
     */
    private static int resolveMajor(String ver) {
        if (ver == null || ver.isEmpty()) {
            return 13;
        }
        try {
            String[] parts = ver.split("\\.");
            int first = Integer.parseInt(parts[0].trim());
            if (first <= 1 && parts.length >= 2) {
                return Integer.parseInt(parts[1].trim());
            }
            return first;
        } catch (Throwable ignored) {
            return 13;
        }
    }

    /**
     * 将 "1.21.4" / "26.2" 形式的版本号编码为可比较整数
     * (主*1000000 + 次*1000 + 修订)
     */
    private static int encode(String ver) {
        String[] parts = ver.split("\\.");
        int a = 0, b = 0, c = 0;
        try {
            if (parts.length > 0) a = Integer.parseInt(parts[0].trim());
            if (parts.length > 1) b = Integer.parseInt(parts[1].trim());
            if (parts.length > 2) c = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException ignored) {
        }
        return a * 1_000_000 + b * 1_000 + c;
    }

    /**
     * 从Bukkit版本字符串中提取纯版本号
     */
    private static String extractVersion(String bukkitVersion) {
        if (bukkitVersion == null) return null;
        int mcIdx = bukkitVersion.indexOf("MC:");
        if (mcIdx >= 0) {
            String sub = bukkitVersion.substring(mcIdx + 3).trim();
            return sub.split("[^0-9.]")[0];
        }
        String[] parts = bukkitVersion.split("-");
        if (parts.length > 0) {
            return parts[0];
        }
        return null;
    }

    public static int getMajorVersion() {
        return MAJOR_VERSION;
    }

    public static boolean isLegacy() {
        return LEGACY;
    }

    /**
     * 是否支持主手/副手 API (PlayerInventory#getItemInMainHand 等, 1.9+)
     */
    public static boolean supportsMainHand() {
        return SUPPORTS_MAIN_HAND;
    }

    /**
     * 预解析后的方块ID: Material + 可选旧版数据值
     */
    public static final class BlockId {
        private final Material material;
        private final short damage;
        private final boolean hasDamage;

        BlockId(Material material, short damage, boolean hasDamage) {
            this.material = material;
            this.damage = damage;
            this.hasDamage = hasDamage;
        }

        public Material getMaterial() {
            return material;
        }

        public short getDamage() {
            return damage;
        }

        /**
         * 是否指定了旧版数据值 (仅 1.8-1.12 参与比较)
         */
        public boolean hasDamage() {
            return hasDamage;
        }
    }

    /**
     * 批量解析方块ID字符串列表为 {@link BlockId} 列表 (在配置加载时调用一次).
     * 无法在当前版本解析出的ID会被跳过.
     */
    public static List<BlockId> parseBlockIds(List<String> blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<BlockId> result = new ArrayList<>(blockIds.size());
        for (String id : blockIds) {
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            ParsedId parsed = parseId(id.trim());
            if (parsed == null) {
                continue;
            }
            Material material = resolveMaterial(parsed.materialName);
            if (material == null || material == Material.AIR) {
                continue;
            }
            result.add(new BlockId(material, parsed.damage, parsed.hasDamage));
        }
        return result;
    }

    /**
     * 判断预解析ID是否匹配指定方块.
     * 旧版 (1.12-) 数据值有指定时才参与比较; 新版忽略数据值.
     */
    public static boolean matches(BlockId id, Material blockType, byte blockData) {
        if (id == null || blockType == null || blockType == Material.AIR) {
            return false;
        }
        if (id.material != blockType) {
            return false;
        }
        return !id.hasDamage || !LEGACY || blockData == (byte) id.damage;
    }

    /**
     * 读取方块的旧版数据值 (新版返回 0)
     */
    public static byte getBlockData(Block block) {
        if (!LEGACY || block == null) {
            return 0;
        }
        try {
            @SuppressWarnings("deprecation")
            byte d = block.getData();
            return d;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static ParsedId parseId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String cleaned = id;
        if (cleaned.toLowerCase().startsWith("minecraft:")) {
            cleaned = cleaned.substring(10);
        }

        int colonIndex = cleaned.indexOf(':');
        if (colonIndex >= 0) {
            String materialName = cleaned.substring(0, colonIndex);
            String damageStr = cleaned.substring(colonIndex + 1);
            try {
                short damage = Short.parseShort(damageStr);
                return new ParsedId(materialName, damage, true);
            } catch (NumberFormatException e) {
                return new ParsedId(materialName, (short) 0, false);
            }
        }
        return new ParsedId(cleaned, (short) 0, false);
    }

    /**
     * 解析 Material 名称 (带缓存, 兼容大小写/命名空间/旧版名).
     * 解析失败或仅能解析为 LEGACY_ 别名时返回 null.
     */
    public static Material resolveMaterial(String materialName) {
        if (materialName == null || materialName.isEmpty()) {
            return null;
        }
        String name = materialName.trim();
        if (name.isEmpty()) {
            return null;
        }

        Material cached = MATERIAL_CACHE.get(name);
        if (cached != null) {
            return cached == MISS ? null : cached;
        }

        Material resolved = doResolveMaterial(name);
        MATERIAL_CACHE.put(name, resolved != null ? resolved : MISS);
        return resolved;
    }

    private static Material doResolveMaterial(String name) {
        try {
            Material material = Material.matchMaterial(name);
            if (material != null && !isLegacyMaterial(material)) {
                return material;
            }
        } catch (Throwable ignored) {
        }

        try {
            Material material = Material.matchMaterial(name.toUpperCase());
            if (material != null && !isLegacyMaterial(material)) {
                return material;
            }
        } catch (Throwable ignored) {
        }

        try {
            Material material = Material.matchMaterial("minecraft:" + name.toLowerCase());
            if (material != null && !isLegacyMaterial(material)) {
                return material;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static boolean isLegacyMaterial(Material material) {
        try {
            return material.name().startsWith("LEGACY_");
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ================================================================
    // CustomModelData (1.14+)
    // ================================================================

    /**
     * 是否支持 CustomModelData (1.14+)
     */
    public static boolean supportsCustomModelData() {
        return SET_CUSTOM_MODEL_DATA_METHOD != null;
    }

    /**
     * 设置物品的 CustomModelData (仅 1.14+ 生效, 低版本静默忽略)
     *
     * @param item             物品
     * @param customModelData  CustomModelData 值 (小于0表示不设置)
     */
    public static void setCustomModelData(ItemStack item, int customModelData) {
        if (item == null || customModelData < 0) {
            return;
        }
        if (SET_CUSTOM_MODEL_DATA_METHOD == null) {
            // 当前版本不支持 CustomModelData, 静默忽略
            return;
        }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return;
            }
            SET_CUSTOM_MODEL_DATA_METHOD.invoke(meta, Integer.valueOf(customModelData));
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 解析后的方块ID
     */
    private static class ParsedId {
        final String materialName;
        final short damage;
        final boolean hasDamage;

        ParsedId(String materialName, short damage, boolean hasDamage) {
            this.materialName = materialName;
            this.damage = damage;
            this.hasDamage = hasDamage;
        }
    }
}
