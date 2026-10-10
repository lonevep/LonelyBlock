package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

 












public class VariableManager {

    private final LonelyBlock plugin;
    private final File dataFile;
    private FileConfiguration data;

    
    private final Map<String, Double> defaults = new ConcurrentHashMap<>();

    
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<>();

    public VariableManager(LonelyBlock plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "variables.yml");
    }

     


    public void loadDefaults(ConfigurationSection customVariablesSection) {
        defaults.clear();
        if (customVariablesSection != null) {
            for (String key : customVariablesSection.getKeys(false)) {
                try {
                    double val = customVariablesSection.getDouble(key, 0);
                    defaults.put(key, val);
                } catch (Throwable ignored) {
                }
            }
        }
    }

     


    public void loadData() {
        try {
            if (!dataFile.exists()) {
                dataFile.getParentFile().mkdirs();
                try {
                    dataFile.createNewFile();
                } catch (IOException ignored) {
                }
            }
            data = YamlConfiguration.loadConfiguration(dataFile);
        } catch (Throwable e) {
            plugin.getLogger().warning("加载 variables.yml 失败: " + e.getMessage());
            data = new YamlConfiguration();
        }
        cache.clear();
    }

     


    public void saveData() {
        if (data == null) {
            return;
        }
        try {
            
            ConfigurationSection varsSection = data.getConfigurationSection("variables");
            if (varsSection == null) {
                varsSection = data.createSection("variables");
            }
            for (Map.Entry<UUID, Map<String, Double>> playerEntry : cache.entrySet()) {
                String uuid = playerEntry.getKey().toString();
                ConfigurationSection playerSection = varsSection.getConfigurationSection(uuid);
                if (playerSection == null) {
                    playerSection = varsSection.createSection(uuid);
                }
                for (Map.Entry<String, Double> varEntry : playerEntry.getValue().entrySet()) {
                    playerSection.set(varEntry.getKey(), varEntry.getValue());
                }
            }
            data.save(dataFile);
        } catch (Throwable e) {
            plugin.getLogger().warning("保存 variables.yml 失败: " + e.getMessage());
        }
    }

     


    public double getDefaultValue(String varName) {
        Double d = defaults.get(varName);
        return d == null ? 0 : d;
    }

     


    public double get(OfflinePlayer player, String varName) {
        if (player == null || varName == null) {
            return 0;
        }
        Map<String, Double> playerVars = cache.get(player.getUniqueId());
        if (playerVars != null && playerVars.containsKey(varName)) {
            return playerVars.get(varName);
        }
        
        double val = data.getDouble("variables." + player.getUniqueId() + "." + varName,
                Double.NaN);
        if (Double.isNaN(val)) {
            
            return getDefaultValue(varName);
        }
        
        if (playerVars == null) {
            playerVars = new ConcurrentHashMap<>();
            cache.put(player.getUniqueId(), playerVars);
        }
        playerVars.put(varName, val);
        return val;
    }

     


    public void set(OfflinePlayer player, String varName, double value) {
        if (player == null || varName == null) {
            return;
        }
        Map<String, Double> playerVars = cache.get(player.getUniqueId());
        if (playerVars == null) {
            playerVars = new ConcurrentHashMap<>();
            cache.put(player.getUniqueId(), playerVars);
        }
        playerVars.put(varName, value);
    }

     


    public void add(OfflinePlayer player, String varName, double amount) {
        double current = get(player, varName);
        set(player, varName, current + amount);
    }

     


    public boolean remove(OfflinePlayer player, String varName, double amount) {
        double current = get(player, varName);
        if (current < amount) {
            return false;
        }
        set(player, varName, current - amount);
        return true;
    }

     


    public boolean has(OfflinePlayer player, String varName, double amount) {
        return get(player, varName) >= amount;
    }

     





    public static String[] parseVariableEntry(String entry) {
        if (entry == null) return null;
        String s = entry.trim();
        if (s.isEmpty()) return null;
        int colon = s.lastIndexOf(':');
        if (colon <= 0) return null;
        String name = s.substring(0, colon).trim();
        String valueStr = s.substring(colon + 1).trim();
        if (name.isEmpty() || valueStr.isEmpty()) return null;
        try {
            double amount = Double.parseDouble(valueStr);
            return new String[]{name, String.valueOf(amount)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

     


    public void clearCache() {
        cache.clear();
    }
}
