package com.lonelyBlock.manager;

import com.lonelyBlock.config.BlockConfig;
import com.lonelyBlock.hook.MythicMobsHook;
import com.lonelyBlock.hook.NeigeItemsHook;
import com.lonelyBlock.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 工具匹配器 - 判断玩家手中的物品是否匹配配置中要求的工具
 *
 * 支持的工具定义格式 (与 ItemManager 相同):
 *   "name:显示名" - 通过物品显示名匹配 (支持颜色代码)
 *   "mm:物品ID"   - 通过 MythicMobs 物品匹配 (比较 ItemStack)
 *   "ni:物品ID"   - 通过 NeigeItems 物品匹配 (比较 ItemStack)
 *
 * 匹配逻辑:
 *   - name 匹配: 将玩家物品的显示名与配置名都转换为彩色字符串后比较 (忽略大小写)
 *   - mm/ni 匹配: 调用物品库获取标准 ItemStack, 然后与玩家物品比较相似性
 */
public class ToolMatcher {

    /** matchesByNeigeItems 诊断日志计数器 (前 5 次调用输出详细中间结果) */
    private static volatile int niDiagnoseCount = 0;

    public ToolMatcher() {
    }

    /**
     * 在 ToolEntry 列表中查找匹配玩家工具的条目
     *
     * @param tool  玩家手中的物品
     * @param tools 工具条目列表 (含工具定义与挖掘时间)
     * @return 匹配的 ToolEntry, 未匹配返回 null
     */
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

    /**
     * 判断工具是否匹配某个工具定义字符串
     */
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

    /**
     * 通过物品显示名匹配
     * - 玩家物品的自定义显示名 与 配置名 (颜色代码已转换) 进行比较
     * - 如果玩家物品没有自定义显示名, 则使用 Material 名比较
     */
    private boolean matchesByName(ItemStack tool, String expectedName) {
        String actualName = getDisplayName(tool);
        return ColorUtil.nameEquals(actualName, expectedName);
    }

    /**
     * 获取物品的显示名
     * - 如果物品有自定义显示名, 返回该名称 (已含颜色代码)
     * - 如果没有, 返回 Material 名称 (用于支持按原版物品名匹配)
     */
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
        // 返回 Material 名 (如 "WOODEN_AXE")
        return tool.getType() != null ? tool.getType().name() : "";
    }

    /**
     * 通过 MythicMobs 物品匹配
     */
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

    /**
     * 通过 NeigeItems 物品匹配
     *
     * 优先通过 NBT 中的 NI 物品 ID (NeigeItems.id) 比对:
     *   - NI 物品挖掘后耐久值变化, ItemMeta.equals 会判定为不相似, 导致工具匹配失败
     *   - 而 NBT 中的 "NeigeItems.id" 字段不受耐久变更影响, 是稳定的物品标识
     * NBT 读取失败时, 回退到 ItemMeta 相似性比较 (首次未使用物品的场景)
     */
    private boolean matchesByNeigeItems(ItemStack tool, String itemId) {
        if (!NeigeItemsHook.isEnabled()) {
            logNiDiagnose(itemId, "NI hook 未启用", null, false);
            return false;
        }
        // 优先: 通过 NBT 中的 NI 物品 ID 匹配 (耐久变更后仍可正确识别)
        String toolItemId = NeigeItemsHook.getNeigeItemsItemId(tool);
        if (toolItemId != null) {
            boolean match = toolItemId.equals(itemId);
            logNiDiagnose(itemId, "NBT_ID=" + toolItemId, "isSimilar=未执行", match);
            return match;
        }
        // 回退: NBT 读取失败, 用 isSimilar 比较 (首次未使用物品的场景)
        // 诊断: 输出 isNeigeItemsItem 结果, 区分"非NI物品"与"NBT读取失败"
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

    /**
     * 输出 NI 工具匹配诊断日志 (前 5 次)
     */
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

    /**
     * 比较两个ItemStack是否相似 (不考虑数量)
     * - Material 必须相同
     * - ItemMeta 必须相同
     */
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
