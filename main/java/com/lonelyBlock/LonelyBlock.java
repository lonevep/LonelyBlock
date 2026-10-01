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

/**
 * LonelyBlock 主插件类
 * 功能:
 *   - 自定义方块掉落 (支持 MythicMobs / NeigeItems 物品库, 概率掉落, CustomModelData)
 *   - 工具限制破坏 (按显示名或 MM/NI 物品匹配, 自定义挖掘时间)
 *   - 挖掘条件 (权限 / 自定义变量 / PlaceholderAPI 占位符)
 *   - 奖励与扣除 (金币 / 点券 / 经验 / 自定义变量)
 *   - 方块定时恢复 (支持两位小数秒) 或错误工具立马复原
 *   - 消息提示 (玩家消息 / 全服通告, 全部可自定义)
 *   - 命令执行 (OP / Console, 支持变量)
 *   - 自定义变量系统 (通过 PlaceholderAPI 暴露)
 * 支持版本: 1.8 - 26.2 (Paper/Spigot 全版本)
 */
public final class LonelyBlock extends JavaPlugin {

    private static LonelyBlock instance;

    // Managers
    private ConfigManager configManager;
    private ItemManager itemManager;
    private ToolMatcher toolMatcher;
    private RestorationManager restorationManager;
    private CommandExecutor commandExecutor;
    private VariableManager variableManager;
    private ConditionChecker conditionChecker;
    private RewardManager rewardManager;

    private BlockBreakListener blockBreakListener;

    // 控制台启动消息 (内置, 不可配置) - 绿色字体分行
    private static final String[] STARTUP_MESSAGES = {
            "&a插件作者: &flone_vep",
            "&a感谢您对本插件的支持",
            "&a如有问题请及时反馈",
            "&a插件启动成功"
    };

    // 控制台关闭消息 (内置, 不可配置) - 绿色字体分行
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

        // 1. 初始化配置管理器
        configManager = new ConfigManager(this);
        configManager.load();

        // 2. 初始化各物品库/经济 Hook (通过反射, 兼容各版本)
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
            // 注册本插件的 PAPI 扩展 (暴露自定义变量)
            registerPapiExpansion();
        } else {
            getLogger().info("未检测到 PlaceholderAPI, 跳过加载 (自定义变量占位符将不可用, 内部变量仍可使用)");
        }

        // 3. 初始化变量管理器 (加载默认值与数据)
        variableManager = new VariableManager(this);
        variableManager.loadDefaults(configManager.getCustomVariablesSection());
        variableManager.loadData();

        // 4. 初始化业务管理器
        itemManager = new ItemManager();
        toolMatcher = new ToolMatcher();
        restorationManager = new RestorationManager(this);
        commandExecutor = new CommandExecutor(this);
        conditionChecker = new ConditionChecker(variableManager);
        rewardManager = new RewardManager(this, configManager, variableManager);

        // 5. 注册事件监听器
        blockBreakListener = new BlockBreakListener(this, configManager, itemManager, toolMatcher,
                restorationManager, commandExecutor, conditionChecker, rewardManager);
        blockBreakListener.start();
        getServer().getPluginManager().registerEvents(blockBreakListener, this);

        // 6. 注册命令
        LonelyBlockCommand commandHandler = new LonelyBlockCommand(this, configManager);
        getCommand("lonelyblock").setExecutor(commandHandler);

        // 7. 打印服务器版本信息
        getLogger().info("服务端版本: " + getServer().getVersion());
        getLogger().info("LonelyBlock 主版本判定: " + VersionUtil.getMajorVersion()
                + " (Legacy模式: " + VersionUtil.isLegacy() + ")"
                + " CustomModelData支持: " + VersionUtil.supportsCustomModelData());
        getLogger().info("已加载 " + configManager.getBlocks().size() + " 个方块配置");

        // 8. 打印启动消息 (绿色字体, 控制台分行显示 - 内置不可配置)
        printStartupMessages();
    }

    @Override
    public void onDisable() {
        // 关闭挖掘会话管理器 (清理动画)
        if (blockBreakListener != null) {
            blockBreakListener.shutdown();
        }
        // 保存变量数据
        if (variableManager != null) {
            variableManager.saveData();
        }
        // 打印关闭消息 (绿色字体, 控制台分行显示 - 内置不可配置)
        printShutdownMessages();

        instance = null;
    }

    /**
     * 注册 PlaceholderAPI 扩展
     * 通过反射性条件实例化, 避免 PAPI 未安装时类加载失败
     */
    private void registerPapiExpansion() {
        try {
            new com.lonelyBlock.expansion.LonelyBlockExpansion(this).register();
            getLogger().info("已注册 PlaceholderAPI 扩展 (标识符: " + configManager.getPapiIdentifier() + ")");
        } catch (Throwable e) {
            getLogger().warning("注册 PlaceholderAPI 扩展失败: " + e.getMessage());
        }
    }

    /**
     * 打印启动消息 (绿色字体, 分行) - 内置不可配置
     */
    private void printStartupMessages() {
        for (String line : STARTUP_MESSAGES) {
            getServer().getConsoleSender().sendMessage(ColorUtil.colorize(line));
        }
    }

    /**
     * 打印关闭消息 (绿色字体, 分行) - 内置不可配置
     */
    private void printShutdownMessages() {
        for (String line : SHUTDOWN_MESSAGES) {
            getServer().getConsoleSender().sendMessage(ColorUtil.colorize(line));
        }
    }

    /**
     * 获取插件实例 (供其他类静态访问)
     */
    public static LonelyBlock getInstance() {
        return instance;
    }

    /**
     * 是否启用调试模式
     */
    public static boolean isDebug() {
        return instance != null && instance.configManager != null && instance.configManager.isDebug();
    }

    /**
     * 获取配置管理器
     */
    public ConfigManager getConfigLoader() {
        return configManager;
    }

    /**
     * 获取物品管理器
     */
    public ItemManager getItemManager() {
        return itemManager;
    }

    /**
     * 获取工具匹配器
     */
    public ToolMatcher getToolMatcher() {
        return toolMatcher;
    }

    /**
     * 获取恢复管理器
     */
    public RestorationManager getRestorationManager() {
        return restorationManager;
    }

    /**
     * 获取命令执行器
     */
    public CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    /**
     * 获取变量管理器
     */
    public VariableManager getVariableManager() {
        return variableManager;
    }

    /**
     * 获取条件检查器
     */
    public ConditionChecker getConditionChecker() {
        return conditionChecker;
    }

    /**
     * 获取奖励管理器
     */
    public RewardManager getRewardManager() {
        return rewardManager;
    }
}
