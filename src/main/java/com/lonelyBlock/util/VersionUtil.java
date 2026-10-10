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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionUtil {

    private static final int MC_1_9 = 1_009_000;
    private static final int MC_1_13 = 1_013_000;

    private static final int MAJOR_VERSION;
    private static final boolean LEGACY;
    private static final boolean SUPPORTS_MAIN_HAND;

    private static final Method SET_CUSTOM_MODEL_DATA_METHOD;
    private static final Material MISS = Material.AIR;
    private static final Map<String, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();
    private static final Pattern VERSION_TOKEN_PATTERN = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    static {
        String ver = null;
        try {
            ver = extractVersion(Bukkit.getVersion());
        } catch (Throwable ignored) {
        }
        int versionCode = MC_1_13;
        if (ver != null && !ver.isEmpty()) {
            int encoded = encode(ver);
            if (encoded > 0) {
                versionCode = encoded;
            }
        }
        LEGACY = versionCode < MC_1_13;
        SUPPORTS_MAIN_HAND = versionCode >= MC_1_9;
        MAJOR_VERSION = resolveMajor(ver);

        Method setCmd = null;
        try {
            setCmd = ItemMeta.class.getMethod("setCustomModelData", Integer.class);
        } catch (Throwable ignored) {
        }
        SET_CUSTOM_MODEL_DATA_METHOD = setCmd;
    }

    private VersionUtil() {
    }

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

    private static String extractVersion(String bukkitVersion) {
        if (bukkitVersion == null || bukkitVersion.isEmpty()) {
            return null;
        }
        int mcIdx = bukkitVersion.indexOf("MC:");
        if (mcIdx >= 0) {
            String sub = bukkitVersion.substring(mcIdx + 3).trim();
            String token = sub.split("[^0-9.]")[0];
            if (!token.isEmpty()) {
                return token;
            }
        }
        Matcher m = VERSION_TOKEN_PATTERN.matcher(bukkitVersion);
        if (m.find()) {
            return m.group();
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

    public static boolean supportsMainHand() {
        return SUPPORTS_MAIN_HAND;
    }

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

        public boolean hasDamage() {
            return hasDamage;
        }
    }

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

    public static boolean matches(BlockId id, Material blockType, byte blockData) {
        if (id == null || blockType == null || blockType == Material.AIR) {
            return false;
        }
        if (id.material != blockType) {
            return false;
        }
        return !id.hasDamage || !LEGACY || blockData == (byte) id.damage;
    }

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

    public static boolean supportsCustomModelData() {
        return SET_CUSTOM_MODEL_DATA_METHOD != null;
    }

    public static void setCustomModelData(ItemStack item, int customModelData) {
        if (item == null || customModelData < 0) {
            return;
        }
        if (SET_CUSTOM_MODEL_DATA_METHOD == null) {
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
