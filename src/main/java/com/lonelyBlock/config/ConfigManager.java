package com.lonelyBlock.config;

import com.lonelyBlock.LonelyBlock;
import com.lonelyBlock.util.ColorUtil;
import com.lonelyBlock.util.VersionUtil;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置管理器 - 加载并管理插件配置
 *
 * 配置文件结构 (分离存储, 已存在的文件不会被覆盖):
 *   - config.yml       主配置 (settings + custom-variables)
 *   - lang.yml         语言文件 (所有玩家提示消息)
 *   - blocks/*.yml     方块配置文件夹 (一个或多个 yml 文件, 顶层 key 为方块配置名)
 *   - variables.yml    玩家自定义变量数据 (运行时生成)
 *
 * 负责:
 *   - 加载 config.yml 解析设置 (debug / require-use-permission / papi-identifier)
 *   - 加载 lang.yml 解析消息
 *   - 加载 blocks/ 文件夹下所有 .yml 文件, 合并解析方块配置列表
 *   - 解析自定义变量默认值
 *   - 提供访问方法 (按key查找消息, 按block查找配置等)
 */
public class ConfigManager {

    private final LonelyBlock plugin;

    private List<BlockConfig> blocks = new ArrayList<>();
    private FileConfiguration langConfig;
    private ConfigurationSection customVariablesSection;
    private String prefix = "";
    private boolean debug = false;
    private boolean requireUsePermission = false;
    private String papiIdentifier = "ll";
    /** 工具 lore 中的挖掘速率关键字 (空字符串表示不启用). 读取该关键字后的数字作为挖掘速率倍率. */
    private String miningSpeedLore = "";
    /** 工具 lore 中的挖掘倍率关键字 (空字符串表示不启用). 读取该关键字后的数字作为掉落数量倍率. */
    private String miningMultiplierLore = "";

    public ConfigManager(LonelyBlock plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载配置文件
     * 注意: 不会覆盖已存在的配置文件; 缺失时才生成默认
     */
    public void load() {
        // 1. 主配置 config.yml (使用 saveDefaultConfig, 内部已做"已存在则不覆盖")
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // 解析设置
        ConfigurationSection settingsSection = config.getConfigurationSection("settings");
        if (settingsSection != null) {
            debug = settingsSection.getBoolean("debug", false);
            requireUsePermission = settingsSection.getBoolean("require-use-permission", false);
            papiIdentifier = settingsSection.getString("papi-identifier", "ll");
            if (papiIdentifier == null || papiIdentifier.trim().isEmpty()) {
                papiIdentifier = "ll";
            } else {
                papiIdentifier = papiIdentifier.trim();
            }
            // 挖掘速率 lore 关键字 (留空表示不启用)
            String msl = settingsSection.getString("mining-speed-lore", "");
            miningSpeedLore = msl == null ? "" : msl.trim();
            // 挖掘倍率 lore 关键字 (留空表示不启用)
            String mml = settingsSection.getString("mining-multiplier-lore", "");
            miningMultiplierLore = mml == null ? "" : mml.trim();
        } else {
            debug = false;
            requireUsePermission = false;
            papiIdentifier = "ll";
            miningSpeedLore = "";
            miningMultiplierLore = "";
        }

        // 解析自定义变量默认值
        customVariablesSection = config.getConfigurationSection("custom-variables");

        // 2. 语言文件 lang.yml
        loadLangFile();

        // 3. 方块配置 blocks/*.yml
        loadBlockConfigs();

        plugin.getLogger().info("已加载 " + blocks.size() + " 个方块配置");
    }

    /**
     * 加载语言文件 lang.yml
     * 若文件不存在, 释放默认资源 (不覆盖已有文件)
     */
    private void loadLangFile() {
        File langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            try {
                plugin.saveResource("lang.yml", false);
            } catch (Throwable ignored) {
                // 释放失败不报错, 后续用空消息兜底
            }
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);

        String rawPrefix = langConfig.getString("prefix", "");
        prefix = ColorUtil.colorize(rawPrefix);
    }

    /**
     * 加载 blocks/ 文件夹下所有 .yml 文件
     * 每个 .yml 文件的顶层 key 都是一个独立的方块配置名
     * 若文件夹/示例文件不存在, 释放默认资源 (不覆盖已有文件)
     */
    private void loadBlockConfigs() {
        blocks = new ArrayList<>();
        File blocksDir = new File(plugin.getDataFolder(), "blocks");
        if (!blocksDir.exists()) {
            try {
                blocksDir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        // 确保示例文件存在 (replace=false: 已存在则不覆盖, 不报错)
        File exampleFile = new File(blocksDir, "example.yml");
        if (!exampleFile.exists()) {
            try {
                plugin.saveResource("blocks/example.yml", false);
            } catch (Throwable ignored) {
                // 释放失败静默处理 (用户可能手动管理配置)
            }
        }

        File[] files = blocksDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName().toLowerCase();
            if (!name.endsWith(".yml") && !name.endsWith(".yaml")) continue;
            // 跳过备份文件
            if (name.endsWith(".bak") || name.contains(".backup")) continue;

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                ConfigurationSection blockSection = yaml.getConfigurationSection(key);
                if (blockSection == null) {
                    continue;
                }
                try {
                    BlockConfig blockConfig = BlockConfig.fromSection(key, blockSection);
                    if (blockConfig != null) {
                        blocks.add(blockConfig);
                        if (debug) {
                            plugin.getLogger().info("已加载方块配置: " + blockConfig);
                        }
                    }
                } catch (Throwable e) {
                    plugin.getLogger().warning("加载方块配置 [" + key + "] (来自 " + file.getName() + ") 失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 查找匹配的方块配置
     */
    public BlockConfig findMatchingBlock(Block block) {
        if (block == null) {
            return null;
        }
        for (BlockConfig config : blocks) {
            if (VersionUtil.blockMatches(block, config.getBlockIds())) {
                return config;
            }
        }
        return null;
    }

    /**
     * 获取单行消息 (已转译颜色代码, 不含前缀) - 从 lang.yml 读取
     */
    public String getMessage(String key) {
        if (langConfig == null) {
            return "";
        }
        if (langConfig.isString(key)) {
            return ColorUtil.colorize(langConfig.getString(key));
        }
        return "";
    }

    /**
     * 获取多行消息列表 (已转译颜色代码, 不含前缀)
     */
    public List<String> getMessageList(String key) {
        if (langConfig == null) {
            return Collections.emptyList();
        }
        if (langConfig.isList(key)) {
            List<String> result = new ArrayList<>();
            for (String line : langConfig.getStringList(key)) {
                result.add(ColorUtil.colorize(line));
            }
            return result;
        } else if (langConfig.isString(key)) {
            List<String> result = new ArrayList<>();
            result.add(ColorUtil.colorize(langConfig.getString(key)));
            return result;
        }
        return Collections.emptyList();
    }

    /**
     * 获取玩家消息 (含前缀, 已替换变量)
     *
     * @param key           消息key
     * @param placeholders  变量数组, 格式为 ["%var1%", "value1", "%var2%", "value2"]
     */
    public String getPlayerMessage(String key, String... placeholders) {
        String msg = getMessage(key);
        if (msg.isEmpty()) {
            return "";
        }
        msg = applyPlaceholders(msg, placeholders);
        return prefix + msg;
    }

    /**
     * 获取玩家消息 (基于原始消息字符串, 含前缀, 已替换变量)
     */
    public String getPlayerMessageFromRaw(String rawMessage, String... placeholders) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return "";
        }
        String msg = ColorUtil.colorize(rawMessage);
        msg = applyPlaceholders(msg, placeholders);
        return prefix + msg;
    }

    /**
     * 替换变量
     */
    private String applyPlaceholders(String message, String... placeholders) {
        if (message == null || placeholders == null || placeholders.length == 0) {
            return message;
        }
        String result = message;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            if (placeholders[i] != null && placeholders[i + 1] != null) {
                result = result.replace(placeholders[i], placeholders[i + 1]);
            }
        }
        return result;
    }

    /**
     * 获取前缀 (已转译颜色代码)
     */
    public String getPrefix() {
        return prefix;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isRequireUsePermission() {
        return requireUsePermission;
    }

    public String getPapiIdentifier() {
        return papiIdentifier;
    }

    /**
     * 获取工具 lore 中的挖掘速率关键字.
     * @return 关键字 (如 "挖掘速率"); 空字符串表示未启用该功能
     */
    public String getMiningSpeedLore() {
        return miningSpeedLore;
    }

    /**
     * 获取工具 lore 中的挖掘倍率关键字.
     * @return 关键字 (如 "挖掘倍率"); 空字符串表示未启用该功能
     */
    public String getMiningMultiplierLore() {
        return miningMultiplierLore;
    }

    public ConfigurationSection getCustomVariablesSection() {
        return customVariablesSection;
    }

    public List<BlockConfig> getBlocks() {
        return blocks;
    }
}
