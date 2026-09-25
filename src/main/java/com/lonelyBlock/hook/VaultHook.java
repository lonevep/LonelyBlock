package com.lonelyBlock.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 经济插件 Hook
 * 提供金币奖励功能
 */
public final class VaultHook {

    private static boolean enabled = false;
    private static Economy economy = null;

    private VaultHook() {
    }

    /**
     * 初始化 Vault Hook
     *
     * @return 是否成功加载
     */
    public static boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        try {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                return false;
            }
            economy = rsp.getProvider();
            enabled = economy != null;
            return enabled;
        } catch (Throwable e) {
            enabled = false;
            return false;
        }
    }

    /**
     * 检查 Vault 经济是否可用
     */
    public static boolean isEnabled() {
        return enabled && economy != null;
    }

    /**
     * 给玩家发放金币
     *
     * @param player 玩家
     * @param amount 金额
     * @return 是否成功
     */
    public static boolean deposit(OfflinePlayer player, double amount) {
        if (!isEnabled() || player == null || amount <= 0) {
            return false;
        }
        try {
            return economy.depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 获取 Economy 实例
     */
    public static Economy getEconomy() {
        return economy;
    }
}
