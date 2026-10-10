package com.lonelyBlock.config;

import com.lonelyBlock.util.RandomValue;

import java.util.Map;

 







public class DropConfig {

    private final String itemDefinition;   
    private final RandomValue amount;       
    private final RandomValue chance;       
    private final int customModelData;     

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

     


    public int rollAmount() {
        return Math.max(1, amount.rollInt());
    }

    public RandomValue getChance() {
        return chance;
    }

     


    public double rollChance() {
        return chance.roll();
    }

    public int getCustomModelData() {
        return customModelData;
    }

     


    public boolean hasCustomModelData() {
        return customModelData >= 0;
    }

     
















    @SuppressWarnings("unchecked")
    public static DropConfig parse(Object obj) {
        if (obj == null) {
            return null;
        }

        
        if (obj instanceof String) {
            return parseString((String) obj);
        }
        
        if (obj instanceof Map) {
            return parseMap((Map<String, Object>) obj);
        }
        
        if (obj instanceof org.bukkit.configuration.ConfigurationSection) {
            org.bukkit.configuration.ConfigurationSection sec =
                    (org.bukkit.configuration.ConfigurationSection) obj;
            Map<String, Object> map = sec.getValues(false);
            return parseMap(map);
        }
        
        return parseString(obj.toString());
    }

     


    private static DropConfig parseString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();

        
        String[] parts = s.split("\\|", -1);
        String itemDef = parts[0].trim();
        if (itemDef.isEmpty()) {
            return null;
        }

        RandomValue amount = RandomValue.of(1);
        RandomValue chance = RandomValue.of(100.0);
        int cmd = -1;

        
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

     


    private static DropConfig parseMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Object itemObj = map.get("item");
        if (itemObj == null) {
            
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
