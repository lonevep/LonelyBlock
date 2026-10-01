package com.lonelyBlock.expansion;

import com.lonelyBlock.LonelyBlock;
import com.lonelyBlock.manager.VariableManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI 扩展 - 将本插件的自定义变量暴露为占位符
 *
 * 占位符格式: %<identifier>_<变量名>%
 *   identifier 由 config.yml settings.papi-identifier 配置 (默认 "ll")
 *   变量名 即 VariableManager 中存储的变量名 (含 identifier 前缀)
 *
 * 示例 (identifier=ll):
 *   %ll_level-默认%  -> 变量名 "ll_level-默认" 的值
 *   %ll_mining_count% -> 变量名 "ll_mining_count" 的值
 *
 * 注意: 此类仅在 PlaceholderAPI 已安装时加载 (LonelyBlock 中通过反射性条件实例化)
 */
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

    /**
     * 插件重载后保留此扩展 (不会因 PlaceholderAPI 重载而注销)
     */
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
        // 重构变量名: identifier + "_" + params
        // 这样 %ll_level-默认% (params=level-默认) -> 变量名 "ll_level-默认"
        String varName = getIdentifier() + "_" + params;
        double value = vm.get(player, varName);
        return formatValue(value);
    }

    /**
     * 格式化数值输出 (整数不带小数点)
     */
    private String formatValue(double v) {
        if (v == (long) v) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
