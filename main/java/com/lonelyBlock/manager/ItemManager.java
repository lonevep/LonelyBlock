package com.lonelyBlock.manager;

import com.lonelyBlock.config.DropConfig;
import com.lonelyBlock.hook.MythicMobsHook;
import com.lonelyBlock.hook.NeigeItemsHook;
import com.lonelyBlock.util.VersionUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

 









public class ItemManager {

    public ItemManager() {
    }

     


    public static class ItemResult {
        private final ItemStack item;
        private final String errorMessage;
        private final String itemId;
        private final String source;

        private ItemResult(ItemStack item, String errorMessage, String itemId, String source) {
            this.item = item;
            this.errorMessage = errorMessage;
            this.itemId = itemId;
            this.source = source;
        }

        public static ItemResult success(ItemStack item, String itemId, String source) {
            return new ItemResult(item, null, itemId, source);
        }

        public static ItemResult error(String itemId, String source, String errorMessage) {
            return new ItemResult(null, errorMessage, itemId, source);
        }

        public boolean isSuccess() {
            return item != null;
        }

        public ItemStack getItem() {
            return item;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getItemId() {
            return itemId;
        }

        public String getSource() {
            return source;
        }
    }

     





    public ItemResult getItem(String definition) {
        if (definition == null || definition.trim().isEmpty()) {
            return ItemResult.error("", "", "物品定义为空, 请检查配置文件!");
        }
        String trimmed = definition.trim();

        int colonIdx = trimmed.indexOf(':');
        if (colonIdx < 0) {
            return ItemResult.error(trimmed, "", "物品定义缺少前缀! 应为 mm:/ni:/vanilla: 格式, 当前值: " + trimmed);
        }

        String prefix = trimmed.substring(0, colonIdx).toLowerCase();
        String itemId = trimmed.substring(colonIdx + 1).trim();

        if (itemId.isEmpty()) {
            return ItemResult.error(itemId, prefix, "物品ID为空, 请检查配置文件!");
        }

        switch (prefix) {
            case "mm":
                return getMythicMobsItem(itemId);
            case "ni":
                return getNeigeItemsItem(itemId);
            case "vanilla":
                return getVanillaItem(itemId);
            default:
                return ItemResult.error(itemId, prefix, "未知的物品前缀 '" + prefix + "'! 应为 mm:/ni:/vanilla:");
        }
    }

     






    public ItemResult getItem(DropConfig dropConfig) {
        if (dropConfig == null) {
            return ItemResult.error("", "", "掉落物配置为空!");
        }
        return getItem(dropConfig, dropConfig.rollAmount());
    }

     






    public ItemResult getItem(DropConfig dropConfig, int amount) {
        if (dropConfig == null) {
            return ItemResult.error("", "", "掉落物配置为空!");
        }
        ItemResult result = getItem(dropConfig.getItemDefinition());
        if (!result.isSuccess()) {
            return result;
        }
        ItemStack item = result.getItem();
        try {
            
            if (amount > 0) {
                int maxAmount = item.getMaxStackSize();
                if (amount > maxAmount) {
                    amount = maxAmount;
                }
                item.setAmount(amount);
            }
            
            if (dropConfig.hasCustomModelData()) {
                VersionUtil.setCustomModelData(item, dropConfig.getCustomModelData());
            }
        } catch (Throwable e) {
            
        }
        return ItemResult.success(item, result.getItemId(), result.getSource());
    }

     


    private ItemResult getMythicMobsItem(String itemId) {
        if (!MythicMobsHook.isEnabled()) {
            return ItemResult.error(itemId, "mm", "mythicmobs-not-found");
        }
        ItemStack item = MythicMobsHook.getItem(itemId);
        if (item == null) {
            return ItemResult.error(itemId, "mm", "MythicMobs 物品 [" + itemId + "] 未找到!");
        }
        return ItemResult.success(item.clone(), itemId, "mm");
    }

     


    private ItemResult getNeigeItemsItem(String itemId) {
        if (!NeigeItemsHook.isEnabled()) {
            return ItemResult.error(itemId, "ni", "neigeitems-not-found");
        }
        ItemStack item = NeigeItemsHook.getItem(itemId);
        if (item == null) {
            return ItemResult.error(itemId, "ni", "NeigeItems 物品 [" + itemId + "] 未找到!");
        }
        return ItemResult.success(item.clone(), itemId, "ni");
    }

     


    private ItemResult getVanillaItem(String itemId) {
        try {
            
            String name = itemId;
            if (name.toLowerCase().startsWith("minecraft:")) {
                name = name.substring(10);
            }
            Material material = VersionUtil.resolveMaterial(name);
            if (material == null || material == Material.AIR) {
                return ItemResult.error(itemId, "vanilla", "原版物品 [" + itemId + "] 不存在!");
            }
            return ItemResult.success(new ItemStack(material, 1), itemId, "vanilla");
        } catch (Throwable e) {
            return ItemResult.error(itemId, "vanilla", "原版物品 [" + itemId + "] 解析失败: " + e.getMessage());
        }
    }
}
