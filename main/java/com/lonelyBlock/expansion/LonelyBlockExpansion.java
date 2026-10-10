package com.lonelyBlock.expansion;

import com.lonelyBlock.LonelyBlock;
import com.lonelyBlock.manager.VariableManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

 












public class LonelyBlockExpansion extends PlaceholderExpansion {

    private final LonelyBlock plugin;

    public LonelyBlockExpansion(LonelyBlock plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        String id = plugin.getConfigLoader().getPapiIdentifier();
        if (id == null || id.trim().isEmpty()) {
            return "ll";
        }
        return id.trim();
    }

    @Override
    public String getAuthor() {
        return "lone_vep";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

     


    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) {
            return "";
        }
        VariableManager vm = plugin.getVariableManager();
        if (vm == null) {
            return "0";
        }
        
        
        String varName = getIdentifier() + "_" + params;
        double value = vm.get(player, varName);
        return formatValue(value);
    }

     


    private String formatValue(double v) {
        if (v == (long) v) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
