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

/**
 * 自定义变量管理器
 *
 * 管理 LonelyBlock 自定义的内部变量 (按玩家存储, 持久化到 variables.yml)
 * 这些变量可通过 PlaceholderAPI 暴露为 %<papi-identifier>_<变量名>%
 *
 * 变量名可包含字母/数字/下划线/连字符/中文, 如 ll_level / ll_level-默认 / mining_count
 *
 * 存储格式 (variables.yml):
 *   variables:
 *     <玩家UUID>:
 *       <变量名>: <数值>
 */
public class VariableManager {

    private final LonelyBlock plugin;
    private final File dataFile;
    private FileConfiguration data;

    // 默认变量值 (从 config.yml custom-variables 读取)
    private final Map<String, Double> defaults = new ConcurrentHashMap<>();

    // 内存缓存: 玩家UUID -> (变量名 -> 值)
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<>();

    public VariableManager(LonelyBlock plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "variables.yml");
    }

    /**
     * 加载默认值 (从 config.yml custom-variables 节点)
     */
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

    /**
     * 加载数据文件到内存
     */
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

    /**
     * 保存内存数据到文件
     */
    public void saveData() {
        if (data == null) {
            return;
        }
        try {
            // 将缓存写回 data
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

    /**
     * 获取变量的默认值 (未定义则返回 0)
     */
    public double getDefaultValue(String varName) {
        Double d = defaults.get(varName);
        return d == null ? 0 : d;
    }

    /**
     * 获取玩家某变量的当前值 (未设置则返回默认值)
     */
    public double get(OfflinePlayer player, String varName) {
        if (player == null || varName == null) {
            return 0;
        }
        Map<String, Double> playerVars = cache.get(player.getUniqueId());
        if (playerVars != null && playerVars.containsKey(varName)) {
            return playerVars.get(varName);
        }
        // 从数据文件读取
        double val = data.getDouble("variables." + player.getUniqueId() + "." + varName,
                Double.NaN);
        if (Double.isNaN(val)) {
            // 没有存储值, 返回默认值
            return getDefaultValue(varName);
        }
        // 缓存
        if (playerVars == null) {
            playerVars = new ConcurrentHashMap<>();
            cache.put(player.getUniqueId(), playerVars);
        }
        playerVars.put(varName, val);
        return val;
    }

    /**
     * 设置玩家某变量的值
     */
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
        // 同步到 data 配置
        data.set("variables." + player.getUniqueId() + "." + varName, value);
    }

    /**
     * 增加变量值 (可正可负)
     */
    public void add(OfflinePlayer player, String varName, double amount) {
        double current = get(player, varName);
        set(player, varName, current + amount);
    }

    /**
     * 扣除变量值; 不足则返回 false 且不扣除
     */
    public boolean remove(OfflinePlayer player, String varName, double amount) {
        double current = get(player, varName);
        if (current < amount) {
            return false;
        }
        set(player, varName, current - amount);
        return true;
    }

    /**
     * 玩家是否拥有至少 amount 的变量值
     */
    public boolean has(OfflinePlayer player, String varName, double amount) {
        return get(player, varName) >= amount;
    }

    /**
     * 解析 "变量名:数值" 字符串为数组 [变量名, 数值]
     * 数值支持小数和负数
     *
     * @return [varName, amount], 解析失败返回 null
     */
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

    /**
     * 全服广播变量值清理 (管理员调试用)
     */
    public void clearCache() {
        cache.clear();
    }
}
