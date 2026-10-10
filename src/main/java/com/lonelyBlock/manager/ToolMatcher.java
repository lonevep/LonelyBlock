package com.lonelyBlock.manager;

import com.lonelyBlock.config.BlockConfig;
import com.lonelyBlock.hook.MythicMobsHook;
import com.lonelyBlock.hook.NeigeItemsHook;
import com.lonelyBlock.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

 











public class ToolMatcher {

     
    private static volatile int niDiagnoseCount = 0;

    public ToolMatcher() {
    }

     






    public BlockConfig.ToolEntry findMatch(ItemStack tool, List<BlockConfig.ToolEntry> tools) {
        if (tool == null || tool.getType() == Material.AIR) {
            return null;
        }
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        for (BlockConfig.ToolEntry entry : tools) {
            if (entry == null) continue;
            String def = entry.getToolDefinition();
            if (def == null || def.trim().isEmpty()) continue;
            if (matchesDefinition(tool, def.trim())) {
                return entry;
            }
        }
        return null;
    }

     


    public boolean matchesDefinition(ItemStack tool, String definition) {
        if (tool == null || tool.getType() == Material.AIR) {
            return false;
        }
        if (definition == null || definition.trim().isEmpty()) {
            return false;
        }
        String trimmed = definition.trim();
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx < 0) {
            return false;
        }
        String prefix = trimmed.substring(0, colonIdx).toLowerCase();
        String value = trimmed.substring(colonIdx + 1);
        if (value.isEmpty()) {
            return false;
        }
        switch (prefix) {
            case "name":
                return matchesByName(tool, value);
            case "mm":
                return matchesByMythicMobs(tool, value);
            case "ni":
                return matchesByNeigeItems(tool, value);
            default:
                return false;
        }
    }

     




    private boolean matchesByName(ItemStack tool, String expectedName) {
        String actualName = getDisplayName(tool);
        return ColorUtil.nameEquals(actualName, expectedName);
    }

     




    private String getDisplayName(ItemStack tool) {
        if (tool == null) {
            return "";
        }
        try {
            if (tool.hasItemMeta()) {
                ItemMeta meta = tool.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    return meta.getDisplayName();
                }
            }
        } catch (Throwable ignored) {
        }
        
        return tool.getType() != null ? tool.getType().name() : "";
    }

     


    private boolean matchesByMythicMobs(ItemStack tool, String itemId) {
        if (!MythicMobsHook.isEnabled()) {
            return false;
        }
        ItemStack expected = MythicMobsHook.getItem(itemId);
        if (expected == null) {
            return false;
        }
        return isSimilar(tool, expected);
    }

     







    private boolean matchesByNeigeItems(ItemStack tool, String itemId) {
        if (!NeigeItemsHook.isEnabled()) {
            logNiDiagnose(itemId, "NI hook 未启用", null, false);
            return false;
        }
        
        String toolItemId = NeigeItemsHook.getNeigeItemsItemId(tool);
        if (toolItemId != null) {
            boolean match = toolItemId.equals(itemId);
            logNiDiagnose(itemId, "NBT_ID=" + toolItemId, "isSimilar=未执行", match);
            return match;
        }
        
        
        boolean isNi = NeigeItemsHook.isNeigeItemsItem(tool);
        ItemStack expected = NeigeItemsHook.getItem(itemId);
        if (expected == null) {
            logNiDiagnose(itemId, "NBT_ID=null, isNiItem=" + isNi + ", getItem返回null", null, false);
            return false;
        }
        boolean similar = isSimilar(tool, expected);
        logNiDiagnose(itemId, "NBT_ID=null, isNiItem=" + isNi, "isSimilar=" + similar, similar);
        return similar;
    }

     


    private static void logNiDiagnose(String configId, String nbtInfo, String similarInfo, boolean result) {
        if (niDiagnoseCount >= 5) {
            return;
        }
        niDiagnoseCount++;
        try {
            org.bukkit.Bukkit.getLogger().info(
                    "[ToolMatcher NI诊断#" + niDiagnoseCount + "] 配置ID=" + configId
                            + " | " + nbtInfo
                            + (similarInfo != null ? " | " + similarInfo : "")
                            + " | 匹配结果=" + result);
        } catch (Throwable ignored) {
        }
    }

     




    private boolean isSimilar(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getType() != b.getType()) {
            return false;
        }
        if (a.hasItemMeta() != b.hasItemMeta()) {
            return false;
        }
        if (a.hasItemMeta()) {
            ItemMeta metaA = a.getItemMeta();
            ItemMeta metaB = b.getItemMeta();
            if (metaA == null || metaB == null) {
                return false;
            }
            return metaA.equals(metaB);
        }
        return true;
    }
}
