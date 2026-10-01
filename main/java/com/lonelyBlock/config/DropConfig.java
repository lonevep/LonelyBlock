package com.lonelyBlock.config;

import com.lonelyBlock.util.RandomValue;

import java.util.Map;

/**
 * 单个掉落物配置
 * 包含物品定义、数量、概率、CustomModelData
 *
 * 数量与概率均支持随机值:
 *   - amount: 支持 "1_5" (随机 1~5 个) 或固定值
 *   - chance: 支持 "50_80" (随机 50%~80%) 或固定值
 */
public class DropConfig {

    private final String itemDefinition;   // 物品定义, 如 "mm:特殊石头" / "ni:木板" / "vanilla:DIAMOND"
    private final RandomValue amount;       // 数量 (随机值, 默认1)
    private final RandomValue chance;       // 概率 0~100 (随机值, 默认100)
    private final int customModelData;     // CustomModelData, -1 表示不设置 (仅1.14+生效, 固定值)

    public DropConfig(String itemDefinition, RandomValue amount, RandomValue chance, int customModelData) {
        this.itemDefinition = itemDefinition;
        this.amount = amount != null ? amount : RandomValue.of(1);
        this.chance = chance != null ? chance : RandomValue.of(100);
        this.customModelData = customModelData;
    }

    public String getItemDefinition() {
        return itemDefinition;
    }

    public RandomValue getAmount() {
        return amount;
    }

    /**
     * 滚动本次掉落数量
     */
    public int rollAmount() {
        return Math.max(1, amount.rollInt());
    }

    public RandomValue getChance() {
        return chance;
    }

    /**
     * 滚动本次掉落概率 (0~100)
     */
    public double rollChance() {
        return chance.roll();
    }

    public int getCustomModelData() {
        return customModelData;
    }

    /**
     * 是否设置了 CustomModelData
     */
    public boolean hasCustomModelData() {
        return customModelData >= 0;
    }

    /**
     * 从配置项解析 DropConfig
     * 支持两种格式:
     *   1. 字符串:
     *      - "mm:物品ID"                       (数量1, 概率100, 无CMD)
     *      - "mm:物品ID|数量|概率|CustomModelData" (用 | 分隔, 因物品ID含冒号)
     *        数量/概率/CMD 可省略 (从右往左省略), 如 "mm:物品ID|2" 或 "mm:物品ID|2|50"
     *        数量/概率 支持 "1_5" 随机值格式
     *   2. 映射 (ConfigurationSection):
     *      item: "mm:物品ID"
     *      amount: 1                数量 (支持随机值 "1_5")
     *      chance: 100.0            概率 0~100 (支持随机值 "50_80")
     *      custom-model-data: -1    CustomModelData (固定整数, -1不设置, 仅1.14+生效)
     *
     * @param obj 配置项原始对象 (String 或 Map)
     * @return DropConfig, 解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    public static DropConfig parse(Object obj) {
        if (obj == null) {
            return null;
        }

        // 字符串格式
        if (obj instanceof String) {
            return parseString((String) obj);
        }
        // 配置节 / Map 格式
        if (obj instanceof Map) {
            return parseMap((Map<String, Object>) obj);
        }
        // ConfigurationSection
        if (obj instanceof org.bukkit.configuration.ConfigurationSection) {
            org.bukkit.configuration.ConfigurationSection sec =
                    (org.bukkit.configuration.ConfigurationSection) obj;
            Map<String, Object> map = sec.getValues(false);
            return parseMap(map);
        }
        // 其它类型转字符串再尝试
        return parseString(obj.toString());
    }

    /**
     * 解析字符串格式
     */
    private static DropConfig parseString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();

        // 用 | 分隔出物品定义和参数 (因物品ID含冒号, 不能用冒号分隔)
        String[] parts = s.split("\\|", -1);
        String itemDef = parts[0].trim();
        if (itemDef.isEmpty()) {
            return null;
        }

        RandomValue amount = RandomValue.of(1);
        RandomValue chance = RandomValue.of(100.0);
        int cmd = -1;

        // parts[1] = 数量, parts[2] = 概率, parts[3] = CMD (从左到右, 可从右省略)
        if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
            amount = RandomValue.parse(parts[1].trim());
        }
        if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
            chance = RandomValue.parse(parts[2].trim());
        }
        if (parts.length >= 4 && !parts[3].trim().isEmpty()) {
            cmd = parseInt(parts[3].trim(), -1);
        }

        return new DropConfig(itemDef, amount, chance, cmd);
    }

    /**
     * 解析映射格式
     */
    private static DropConfig parseMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Object itemObj = map.get("item");
        if (itemObj == null) {
            // 兼容其它键名
            for (String key : map.keySet()) {
                if (key.equalsIgnoreCase("item") || key.equalsIgnoreCase("id")) {
                    itemObj = map.get(key);
                    break;
                }
            }
        }
        if (itemObj == null) {
            return null;
        }
        String itemDef = itemObj.toString().trim();
        if (itemDef.isEmpty()) {
            return null;
        }

        RandomValue amount = RandomValue.of(1);
        RandomValue chance = RandomValue.of(100.0);
        int cmd = -1;

        Object amountObj = getCaseInsensitive(map, "amount", "count", "qty");
        if (amountObj != null) {
            amount = RandomValue.parse(amountObj);
        }

        Object chanceObj = getCaseInsensitive(map, "chance", "probability", "prob");
        if (chanceObj != null) {
            chance = RandomValue.parse(chanceObj);
        }

        Object cmdObj = getCaseInsensitive(map, "custom-model-data", "custommodeldata", "cmd", "model-data", "modeldata");
        if (cmdObj != null) {
            cmd = parseInt(cmdObj.toString(), -1);
        }

        return new DropConfig(itemDef, amount, chance, cmd);
    }

    private static Object getCaseInsensitive(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        // 大小写不敏感查找
        for (String k : map.keySet()) {
            for (String key : keys) {
                if (k.equalsIgnoreCase(key)) {
                    return map.get(k);
                }
            }
        }
        return null;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (Throwable e2) {
                return def;
            }
        }
    }

    @Override
    public String toString() {
        return "DropConfig{item=" + itemDefinition + ", amount=" + amount
                + ", chance=" + chance + ", cmd=" + customModelData + "}";
    }
}
