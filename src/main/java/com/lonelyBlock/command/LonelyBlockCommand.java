package com.lonelyBlock.command;

import com.lonelyBlock.LonelyBlock;
import com.lonelyBlock.config.ConfigManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * LonelyBlock 命令处理器
 * 处理 /lonelyblock (别名 /lpl, /lb) 命令
 *
 * 子命令:
 *   /lonelyblock reload - 重新加载配置文件
 *   /lonelyblock help  - 显示帮助信息
 */
public class LonelyBlockCommand implements CommandExecutor {

    private final LonelyBlock plugin;
    private final ConfigManager configManager;

    public LonelyBlockCommand(LonelyBlock plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                if (!sender.hasPermission("lonelyblock.admin")) {
                    sender.sendMessage(configManager.getPlayerMessage("no-permission"));
                    return true;
                }
                reloadConfig(sender);
                return true;

            case "help":
                sendHelp(sender);
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    /**
     * 重新加载配置文件
     */
    private void reloadConfig(CommandSender sender) {
        try {
            plugin.getConfigLoader().load();
            sender.sendMessage(configManager.getPlayerMessage("plugin-reloaded"));
            if (LonelyBlock.isDebug()) {
                plugin.getLogger().info(sender.getName() + " 重新加载了配置");
            }
        } catch (Throwable e) {
            sender.sendMessage(configManager.getPlayerMessage("plugin-reload-failed"));
            plugin.getLogger().warning("重新加载配置失败: " + e.getMessage());
            if (LonelyBlock.isDebug()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        List<String> help = configManager.getMessageList("help");
        if (help.isEmpty()) {
            sender.sendMessage("§8===== §aLonelyBlock §8=====");
            sender.sendMessage("§e/lonelyblock reload §7- 重新加载配置");
            sender.sendMessage("§e/lonelyblock help §7- 显示帮助");
            sender.sendMessage("§8=====================");
        } else {
            for (String line : help) {
                sender.sendMessage(line);
            }
        }
    }
}
