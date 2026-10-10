package com.lonelyBlock.config;

import com.lonelyBlock.LonelyBlock;
import com.lonelyBlock.util.ColorUtil;
import com.lonelyBlock.util.VersionUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

 















public class ConfigManager {

    private final LonelyBlock plugin;

    private List<BlockConfig> blocks = new ArrayList<>();
     



    private final Map<Material, List<IndexedBlock>> blockIndex = new HashMap<>();
    private FileConfiguration langConfig;
    private ConfigurationSection customVariablesSection;
    private String prefix = "";
    private boolean debug = false;
    private boolean requireUsePermission = false;
    private String papiIdentifier = "ll";
     
    private String miningSpeedLore = "";
     
    private String miningMultiplierLore = "";

    public ConfigManager(LonelyBlock plugin) {
        this.plugin = plugin;
    }

     



    public void load() {
        
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        
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
            
            String msl = settingsSection.getString("mining-speed-lore", "");
            miningSpeedLore = msl == null ? "" : msl.trim();
            
            String mml = settingsSection.getString("mining-multiplier-lore", "");
            miningMultiplierLore = mml == null ? "" : mml.trim();
        } else {
            debug = false;
            requireUsePermission = false;
            papiIdentifier = "ll";
            miningSpeedLore = "";
            miningMultiplierLore = "";
        }

        
        customVariablesSection = config.getConfigurationSection("custom-variables");

        
        loadLangFile();

        
        loadBlockConfigs();

        plugin.getLogger().info("已加载 " + blocks.size() + " 个方块配置");
    }

     



    private void loadLangFile() {
        File langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            try {
                plugin.saveResource("lang.yml", false);
            } catch (Throwable ignored) {
                
            }
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);

        String rawPrefix = langConfig.getString("prefix", "");
        prefix = ColorUtil.colorize(rawPrefix);
    }

     




    private void loadBlockConfigs() {
        blocks = new ArrayList<>();
        blockIndex.clear();
        File blocksDir = new File(plugin.getDataFolder(), "blocks");
        if (!blocksDir.exists()) {
            try {
                blocksDir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        
        File exampleFile = new File(blocksDir, "example.yml");
        if (!exampleFile.exists()) {
            try {
                plugin.saveResource("blocks/example.yml", false);
            } catch (Throwable ignored) {
                
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

        
        buildBlockIndex();
    }

     



    private void buildBlockIndex() {
        for (BlockConfig config : blocks) {
            List<VersionUtil.BlockId> ids = VersionUtil.parseBlockIds(config.getBlockIds());
            for (VersionUtil.BlockId id : ids) {
                List<IndexedBlock> list = blockIndex.get(id.getMaterial());
                if (list == null) {
                    list = new ArrayList<>(2);
                    blockIndex.put(id.getMaterial(), list);
                }
                list.add(new IndexedBlock(config, id));
            }
        }
    }

     


    public BlockConfig findMatchingBlock(Block block) {
        if (block == null || blockIndex.isEmpty()) {
            return null;
        }
        Material type = block.getType();
        if (type == null || type == Material.AIR) {
            return null;
        }
        List<IndexedBlock> candidates = blockIndex.get(type);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        
        if (!VersionUtil.isLegacy()) {
            return candidates.get(0).config;
        }
        byte data = VersionUtil.getBlockData(block);
        for (IndexedBlock candidate : candidates) {
            if (!candidate.id.hasDamage() || data == (byte) candidate.id.getDamage()) {
                return candidate.config;
            }
        }
        return null;
    }

     


    private static final class IndexedBlock {
        final BlockConfig config;
        final VersionUtil.BlockId id;

        IndexedBlock(BlockConfig config, VersionUtil.BlockId id) {
            this.config = config;
            this.id = id;
        }
    }

     


    public String getMessage(String key) {
        if (langConfig == null) {
            return "";
        }
        if (langConfig.isString(key)) {
            return ColorUtil.colorize(langConfig.getString(key));
        }
        return "";
    }

     


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

     





    public String getPlayerMessage(String key, String... placeholders) {
        String msg = getMessage(key);
        if (msg.isEmpty()) {
            return "";
        }
        msg = applyPlaceholders(msg, placeholders);
        return prefix + msg;
    }

     


    public String getPlayerMessageFromRaw(String rawMessage, String... placeholders) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return "";
        }
        String msg = ColorUtil.colorize(rawMessage);
        msg = applyPlaceholders(msg, placeholders);
        return prefix + msg;
    }

     


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

     



    public String getMiningSpeedLore() {
        return miningSpeedLore;
    }

     



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
