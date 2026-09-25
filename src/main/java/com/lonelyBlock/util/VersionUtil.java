package com.lonelyBlock.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

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
 * 注: 旧版本曾包含自定义发包裂纹动画 (sendBlockDamage) 与 NMS 按键状态检测
 *     (isPlayerMining) 用于自定义挖掘会话. 现挖掘速度改由 急迫/挖掘疲劳 buff 控制
 *     (见 MiningBuffManager / MiningSpeedCalculator), 让原版挖掘系统工作, 原版裂纹
 *     动画天然同步, 上述旧机制及 ProtocolLib 依赖已全部移除.
 */
public final class VersionUtil {

    private static final int MAJOR_VERSION;
    private static final boolean LEGACY;

    // 缓存 ItemMeta#setCustomModelData(Integer) 方法 (1.14+ 存在)
    private static final Method SET_CUSTOM_MODEL_DATA_METHOD;

    static {
        int major = 13;
        try {
            String bukkitVersion = Bukkit.getVersion();
            String ver = extractVersion(bukkitVersion);
            if (ver != null) {
                String[] parts = ver.split("\\.");
                if (parts.length >= 2) {
                    try {
                        major = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        MAJOR_VERSION = major;
        LEGACY = major < 13;

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
     * 检查方块是否匹配给定的ID列表中的任意一个
     */
    public static boolean blockMatches(Block block, java.util.List<String> blockIds) {
        if (block == null || blockIds == null || blockIds.isEmpty()) {
            return false;
        }

        Material blockType = block.getType();
        if (blockType == null || blockType == Material.AIR) {
            return false;
        }

        byte blockData = 0;
        if (LEGACY) {
            try {
                @SuppressWarnings("deprecation")
                byte d = block.getData();
                blockData = d;
            } catch (Throwable ignored) {
            }
        }

        for (String id : blockIds) {
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            ParsedId parsed = parseId(id.trim());
            if (parsed == null) {
                continue;
            }

            Material expected = resolveMaterial(parsed.materialName);
            if (expected == null || expected != blockType) {
                continue;
            }

            if (parsed.hasDamage && LEGACY) {
                if (blockData != parsed.damage) {
                    continue;
                }
            }

            return true;
        }
        return false;
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

    public static Material resolveMaterial(String materialName) {
        if (materialName == null || materialName.isEmpty()) {
            return null;
        }

        String name = materialName.trim();

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
