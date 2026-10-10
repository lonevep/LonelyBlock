package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

 














public class CommandExecutor {

    private final LonelyBlock plugin;

    public CommandExecutor(LonelyBlock plugin) {
        this.plugin = plugin;
    }

     






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

     


    public void executeCommand(Player player, Location location, String command) {
        if (command == null || command.isEmpty()) {
            return;
        }

        
        String processed = replaceVariables(command, player, location);

        
        if (processed.toLowerCase().startsWith("op:")) {
            String actualCommand = processed.substring(3).trim();
            executeAsOp(player, actualCommand);
        } else if (processed.toLowerCase().startsWith("console:")) {
            String actualCommand = processed.substring(8).trim();
            executeAsConsole(actualCommand);
        } else {
            
            if (LonelyBlock.isDebug()) {
                plugin.getLogger().warning("命令缺少前缀 (op: 或 console:), 默认以控制台执行: " + processed);
            }
            executeAsConsole(processed);
        }
    }

     


    private void executeAsOp(Player player, String command) {
        if (player == null || command == null || command.isEmpty()) {
            return;
        }
        boolean wasOp = player.isOp();
        try {
            if (!wasOp) {
                player.setOp(true);
            }
            
            boolean success = player.performCommand(command);
            if (LonelyBlock.isDebug() && !success) {
                plugin.getLogger().info("玩家 " + player.getName() + " 以OP身份执行命令失败: /" + command);
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("以OP身份执行命令时出错: " + e.getMessage());
        } finally {
            
            if (!wasOp) {
                try {
                    player.setOp(false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

     


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
