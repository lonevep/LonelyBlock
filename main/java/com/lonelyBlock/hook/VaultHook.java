package com.lonelyBlock.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

 



public final class VaultHook {

    private static boolean enabled = false;
    private static Economy economy = null;

    private VaultHook() {
    }

     




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

     


    public static boolean isEnabled() {
        return enabled && economy != null;
    }

     






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

     


    public static Economy getEconomy() {
        return economy;
    }
}
