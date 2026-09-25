package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 命令执行器 - 处理 op: 和 console: 两种命令执行方式
 *
 * 命令格式:
 *   "op:命令"       - 以 OP 身份执行 (临时将玩家设为OP, 执行完毕后恢复, 玩家权限组不变)
 *   "console:命令" - 以控制台身份执行
 *
 * 变量:
 *   %player_name%   - 触发该指令的玩家ID
 *   %player_uuid%   - 玩家UUID
 *   %player_x%       - 玩家X坐标
 *   %player_y%       - 玩家Y坐标
 *   %player_z%       - 玩家Z坐标
 *   %player_world%   - 玩家所在世界名
 */
public class CommandExecutor {

    private final LonelyBlock plugin;

    public CommandExecutor(LonelyBlock plugin) {
        this.plugin = plugin;
    }

    /**
     * 执行命令列表
     *
     * @param player   触发指令的玩家 (用于变量替换)
     * @param location 玩家破坏方块的位置 (用于变量替换, 可为 null)
     * @param commands 命令列表 (如 ["op: give player beef 64", "console: say ..."])
     */
    public void executeCommands(Player player, Location location, List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            executeCommand(player, location, command.trim());
        }
    }

    /**
     * 执行单条命令
     */
    public void executeCommand(Player player, Location location, String command) {
        if (command == null || command.isEmpty()) {
            return;
        }

        // 替换变量
        String processed = replaceVariables(command, player, location);

        // 判断执行方式
        if (processed.toLowerCase().startsWith("op:")) {
            String actualCommand = processed.substring(3).trim();
            executeAsOp(player, actualCommand);
        } else if (processed.toLowerCase().startsWith("console:")) {
            String actualCommand = processed.substring(8).trim();
            executeAsConsole(actualCommand);
        } else {
            // 没有前缀, 默认以控制台执行
            if (LonelyBlock.isDebug()) {
                plugin.getLogger().warning("命令缺少前缀 (op: 或 console:), 默认以控制台执行: " + processed);
            }
            executeAsConsole(processed);
        }
    }

    /**
     * 以 OP 身份执行命令 (临时提升玩家为OP, 执行后恢复)
     */
    private void executeAsOp(Player player, String command) {
        if (player == null || command == null || command.isEmpty()) {
            return;
        }
        boolean wasOp = player.isOp();
        try {
            if (!wasOp) {
                player.setOp(true);
            }
            // 以玩家身份执行命令 (不需要 / 前缀)
            boolean success = player.performCommand(command);
            if (LonelyBlock.isDebug() && !success) {
                plugin.getLogger().info("玩家 " + player.getName() + " 以OP身份执行命令失败: /" + command);
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("以OP身份执行命令时出错: " + e.getMessage());
        } finally {
            // 恢复玩家原本的OP状态 (玩家权限组保持不变)
            if (!wasOp) {
                try {
                    player.setOp(false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 以控制台身份执行命令
     */
    private void executeAsConsole(String command) {
        if (command == null || command.isEmpty()) {
            return;
        }
        try {
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (LonelyBlock.isDebug() && !success) {
                plugin.getLogger().info("控制台执行命令失败: /" + command);
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("以控制台身份执行命令时出错: " + e.getMessage());
        }
    }

    /**
     * 替换命令中的变量
     */
    private String replaceVariables(String command, Player player, Location location) {
        if (command == null) {
            return "";
        }
        String result = command;
        if (player != null) {
            result = result.replace("%player_name%", player.getName());
            result = result.replace("%player_uuid%", player.getUniqueId().toString());
            Location playerLoc = player.getLocation();
            result = result.replace("%player_x%", String.valueOf(playerLoc.getBlockX()));
            result = result.replace("%player_y%", String.valueOf(playerLoc.getBlockY()));
            result = result.replace("%player_z%", String.valueOf(playerLoc.getBlockZ()));
            result = result.replace("%player_world%", playerLoc.getWorld() != null ? playerLoc.getWorld().getName() : "");
        }
        if (location != null) {
            result = result.replace("%block_x%", String.valueOf(location.getBlockX()));
            result = result.replace("%block_y%", String.valueOf(location.getBlockY()));
            result = result.replace("%block_z%", String.valueOf(location.getBlockZ()));
            result = result.replace("%block_world%", location.getWorld() != null ? location.getWorld().getName() : "");
        }
        return result;
    }
}
