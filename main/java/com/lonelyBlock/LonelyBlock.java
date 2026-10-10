package com.lonelyBlock;

import com.lonelyBlock.Metrics.Metrics;
import com.lonelyBlock.command.LonelyBlockCommand;
import com.lonelyBlock.config.ConfigManager;
import com.lonelyBlock.hook.MythicMobsHook;
import com.lonelyBlock.hook.NeigeItemsHook;
import com.lonelyBlock.hook.PlaceholderAPIHook;
import com.lonelyBlock.hook.PlayerPointsHook;
import com.lonelyBlock.hook.VaultHook;
import com.lonelyBlock.listener.BlockBreakListener;
import com.lonelyBlock.manager.CommandExecutor;
import com.lonelyBlock.manager.ConditionChecker;
import com.lonelyBlock.manager.ItemManager;
import com.lonelyBlock.manager.RestorationManager;
import com.lonelyBlock.manager.RewardManager;
import com.lonelyBlock.manager.ToolMatcher;
import com.lonelyBlock.manager.VariableManager;
import com.lonelyBlock.util.ColorUtil;
import com.lonelyBlock.util.VersionUtil;
import org.bukkit.plugin.java.JavaPlugin;

public final class LonelyBlock extends JavaPlugin {

    private static LonelyBlock instance;

    private ConfigManager configManager;
    private ItemManager itemManager;
    private ToolMatcher toolMatcher;
    private RestorationManager restorationManager;
    private CommandExecutor commandExecutor;
    private VariableManager variableManager;
    private ConditionChecker conditionChecker;
    private RewardManager rewardManager;

    private BlockBreakListener blockBreakListener;

    private static final String[] STARTUP_MESSAGES = {
            "&a插件作者: &flone_vep",
            "&a感谢您对本插件的支持",
            "&a如有问题请及时反馈",
            "&a插件启动成功"
    };

    private static final String[] SHUTDOWN_MESSAGES = {
            "&a插件作者: &flone_vep",
            "&a感谢您对本插件的支持",
            "&a如有问题请及时反馈",
            "&a插件已关闭"
    };

    @Override
    public void onEnable() {

        int pluginId = 32882;
        Metrics metrics = new Metrics(this, pluginId);

        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();

        if (MythicMobsHook.setup()) {
            getLogger().info("已成功加载 MythicMobs 物品库 (版本: " + MythicMobsHook.getVersion() + ")");
        } else {
            getLogger().info("未检测到 MythicMobs, 跳过加载 (mm: 物品将不可用)");
        }
        if (NeigeItemsHook.setup()) {
            getLogger().info("已成功加载 NeigeItems 物品库 (版本: " + NeigeItemsHook.getVersion() + ")");
        } else {
            getLogger().info("未检测到 NeigeItems, 跳过加载 (ni: 物品将不可用)");
        }
        if (VaultHook.setup()) {
            getLogger().info("已成功加载 Vault 经济插件");
        } else {
            getLogger().info("未检测到 Vault, 跳过加载 (金币奖励将不可用)");
        }
        if (PlayerPointsHook.setup()) {
            getLogger().info("已成功加载 PlayerPoints 点券插件 (版本: " + PlayerPointsHook.getVersion() + ")");
        } else {
            getLogger().info("未检测到 PlayerPoints, 跳过加载 (点券奖励将不可用)");
        }
        if (PlaceholderAPIHook.setup()) {
            getLogger().info("已成功加载 PlaceholderAPI");
            registerPapiExpansion();
        } else {
            getLogger().info("未检测到 PlaceholderAPI, 跳过加载 (自定义变量占位符将不可用, 内部变量仍可使用)");
        }

        variableManager = new VariableManager(this);
        variableManager.loadDefaults(configManager.getCustomVariablesSection());
        variableManager.loadData();

        itemManager = new ItemManager();
        toolMatcher = new ToolMatcher();
        restorationManager = new RestorationManager(this);
        commandExecutor = new CommandExecutor(this);
        conditionChecker = new ConditionChecker(variableManager);
        rewardManager = new RewardManager(this, configManager, variableManager);

        blockBreakListener = new BlockBreakListener(this, configManager, itemManager, toolMatcher,
                restorationManager, commandExecutor, conditionChecker, rewardManager);
        blockBreakListener.start();
        getServer().getPluginManager().registerEvents(blockBreakListener, this);

        LonelyBlockCommand commandHandler = new LonelyBlockCommand(this, configManager);
        getCommand("lonelyblock").setExecutor(commandHandler);

        getLogger().info("服务端版本: " + getServer().getVersion());
        getLogger().info("LonelyBlock 主版本判定: " + VersionUtil.getMajorVersion()
                + " (Legacy模式: " + VersionUtil.isLegacy() + ")"
                + " CustomModelData支持: " + VersionUtil.supportsCustomModelData());
        getLogger().info("已加载 " + configManager.getBlocks().size() + " 个方块配置");

        printStartupMessages();
    }

    @Override
    public void onDisable() {
        if (blockBreakListener != null) {
            blockBreakListener.shutdown();
        }
        if (restorationManager != null) {
            restorationManager.shutdown();
        }
        if (variableManager != null) {
            variableManager.saveData();
        }
        printShutdownMessages();

        instance = null;
    }

    private void registerPapiExpansion() {
        try {
            new com.lonelyBlock.expansion.LonelyBlockExpansion(this).register();
            getLogger().info("已注册 PlaceholderAPI 扩展 (标识符: " + configManager.getPapiIdentifier() + ")");
        } catch (Throwable e) {
            getLogger().warning("注册 PlaceholderAPI 扩展失败: " + e.getMessage());
        }
    }

    private void printStartupMessages() {
        for (String line : STARTUP_MESSAGES) {
            getServer().getConsoleSender().sendMessage(ColorUtil.colorize(line));
        }
    }

    private void printShutdownMessages() {
        for (String line : SHUTDOWN_MESSAGES) {
            getServer().getConsoleSender().sendMessage(ColorUtil.colorize(line));
        }
    }

    public static LonelyBlock getInstance() {
        return instance;
    }

    public static boolean isDebug() {
        return instance != null && instance.configManager != null && instance.configManager.isDebug();
    }

    public ConfigManager getConfigLoader() {
        return configManager;
    }

    public ItemManager getItemManager() {
        return itemManager;
    }

    public ToolMatcher getToolMatcher() {
        return toolMatcher;
    }

    public RestorationManager getRestorationManager() {
        return restorationManager;
    }

    public CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    public VariableManager getVariableManager() {
        return variableManager;
    }

    public ConditionChecker getConditionChecker() {
        return conditionChecker;
    }

    public RewardManager getRewardManager() {
        return rewardManager;
    }
}
