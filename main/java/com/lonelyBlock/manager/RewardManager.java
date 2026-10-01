package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import com.lonelyBlock.config.BlockConfig;
import com.lonelyBlock.config.ConfigManager;
import com.lonelyBlock.hook.PlayerPointsHook;
import com.lonelyBlock.hook.VaultHook;
import org.bukkit.entity.Player;

/**
 * 奖励与扣除管理器
 *
 * 挖掘成功后:
 *   - 给予奖励 (金币/点券/经验/自定义变量) - 每次挖掘都重新滚动随机值
 *   - 扣除资源 (金币/点券/经验/自定义变量) - 每次挖掘都重新滚动随机值
 *   - 发送对应提示消息
 *
 * 注意: 检查资源充足性 (ConditionChecker) 与实际扣除均在此处滚动随机值,
 *       可能存在轻微不一致 (检查时滚动得 X, 扣除时滚动得 Y > X), 但概率较低且影响小,
 *       且需求是"支持随机值", 这里采用每次滚动的方式以保证奖励随机性.
 *       若需严格一致, 可让 ConditionChecker 与 RewardManager 共用一次滚动结果 (需重构 API).
 */
public class RewardManager {

    private final LonelyBlock plugin;
    private final ConfigManager configManager;
    private final VariableManager variableManager;

    public RewardManager(LonelyBlock plugin, ConfigManager configManager,
                         VariableManager variableManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.variableManager = variableManager;
    }

    /**
     * 给予挖掘奖励 (金币/点券/经验/自定义变量)
     * 每次调用都会重新滚动随机值
     */
    public void giveRewards(Player player, BlockConfig config) {
        if (player == null || config == null) {
            return;
        }

        // 金币
        if (config.hasRewardMoney()) {
            giveMoney(player, config.rollRewardMoney());
        }
        // 点券
        if (config.hasRewardPoints()) {
            givePoints(player, config.rollRewardPoints());
        }
        // 经验
        if (config.hasRewardExp()) {
            giveExp(player, config.rollRewardExp());
        }
        // 自定义变量
        if (config.hasRewardVariables()) {
            for (BlockConfig.VariableEntry entry : config.getRewardVariables()) {
                String varName = entry.getName();
                double amount = entry.rollValue();
                variableManager.add(player, varName, amount);
                sendVariableGivenMessage(player, varName, amount);
            }
        }
    }

    /**
     * 扣除挖掘成本 (金币/点券/经验/自定义变量)
     * 每次调用都会重新滚动随机值
     * 调用前应已通过 ConditionChecker 验证资源充足
     */
    public void deductCosts(Player player, BlockConfig config) {
        if (player == null || config == null) {
            return;
        }

        // 金币
        if (config.hasCostMoney()) {
            double amount = config.rollCostMoney();
            if (VaultHook.isEnabled()) {
                try {
                    VaultHook.getEconomy().withdrawPlayer(player, amount);
                } catch (Throwable ignored) {
                }
                sendMoneyCostMessage(player, amount);
            }
        }
        // 点券
        if (config.hasCostPoints()) {
            int amount = config.rollCostPoints();
            if (PlayerPointsHook.isEnabled()) {
                PlayerPointsHook.withdraw(player, amount);
            }
            sendPointsCostMessage(player, amount);
        }
        // 经验
        if (config.hasCostExp()) {
            int amount = config.rollCostExp();
            deductExp(player, amount);
            sendExpCostMessage(player, amount);
        }
        // 自定义变量
        if (config.hasCostVariables()) {
            for (BlockConfig.VariableEntry entry : config.getCostVariables()) {
                String varName = entry.getName();
                double amount = entry.rollValue();
                if (amount > 0) {
                    variableManager.remove(player, varName, amount);
                    sendVariableCostMessage(player, varName, amount);
                }
            }
        }
    }

    // ===== 内部方法: 发放 + 提示 =====

    private void giveMoney(Player player, double amount) {
        if (!VaultHook.isEnabled()) {
            String msg = configManager.getPlayerMessage("vault-not-found");
            if (!msg.isEmpty()) player.sendMessage(msg);
            return;
        }
        boolean success = VaultHook.deposit(player, amount);
        if (success) {
            String msg = configManager.getPlayerMessage("money-given",
                    "%amount%", formatNumber(amount));
            if (!msg.isEmpty()) player.sendMessage(msg);
        } else {
            log("给予玩家 " + player.getName() + " " + amount + " 金币失败");
        }
    }

    private void givePoints(Player player, int amount) {
        if (!PlayerPointsHook.isEnabled()) {
            String msg = configManager.getPlayerMessage("playerpoints-not-found");
            if (!msg.isEmpty()) player.sendMessage(msg);
            return;
        }
        boolean success = PlayerPointsHook.deposit(player, amount);
        if (success) {
            String msg = configManager.getPlayerMessage("points-given",
                    "%amount%", String.valueOf(amount));
            if (!msg.isEmpty()) player.sendMessage(msg);
        } else {
            log("给予玩家 " + player.getName() + " " + amount + " 点券失败");
        }
    }

    private void giveExp(Player player, int amount) {
        try {
            player.giveExp(amount);
            String msg = configManager.getPlayerMessage("exp-given",
                    "%amount%", String.valueOf(amount));
            if (!msg.isEmpty()) player.sendMessage(msg);
        } catch (Throwable e) {
            log("给予玩家 " + player.getName() + " " + amount + " 经验失败: " + e.getMessage());
        }
    }

    private void deductExp(Player player, int amount) {
        if (amount <= 0) return;
        try {
            int current = ConditionChecker.getTotalExp(player);
            int newTotal = Math.max(0, current - amount);
            setTotalExp(player, newTotal);
        } catch (Throwable e) {
            log("扣除玩家 " + player.getName() + " " + amount + " 经验失败: " + e.getMessage());
        }
    }

    /**
     * 设置玩家总经验 (跨版本兼容)
     */
    private void setTotalExp(Player player, int total) {
        try {
            int level = 0;
            int expAtLevel = getExpAtLevel(level);
            while (expAtLevel <= total && level < 100000) {
                level++;
                expAtLevel = getExpAtLevel(level);
            }
            level--;
            int levelExp = getExpAtLevel(level);
            int nextLevelExp = getExpAtLevel(level + 1) - levelExp;
            float expInLevel = nextLevelExp > 0 ? (total - levelExp) / (float) nextLevelExp : 0f;
            player.setLevel(level);
            player.setExp(Math.max(0f, Math.min(0.999f, expInLevel)));
        } catch (Throwable ignored) {
        }
    }

    private int getExpAtLevel(int level) {
        if (level <= 15) {
            return level * level + 6 * level;
        } else if (level <= 30) {
            return (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            return (int) (4.5 * level * level - 162.5 * level + 2220);
        }
    }

    private void sendVariableGivenMessage(Player player, String varName, double amount) {
        String msg = configManager.getPlayerMessage("variable-given",
                "%amount%", formatNumber(amount),
                "%variable%", varName);
        if (!msg.isEmpty()) player.sendMessage(msg);
    }

    private void sendMoneyCostMessage(Player player, double amount) {
        String msg = configManager.getPlayerMessage("money-cost",
                "%amount%", formatNumber(amount));
        if (!msg.isEmpty()) player.sendMessage(msg);
    }

    private void sendPointsCostMessage(Player player, int amount) {
        String msg = configManager.getPlayerMessage("points-cost",
                "%amount%", String.valueOf(amount));
        if (!msg.isEmpty()) player.sendMessage(msg);
    }

    private void sendExpCostMessage(Player player, int amount) {
        String msg = configManager.getPlayerMessage("exp-cost",
                "%amount%", String.valueOf(amount));
        if (!msg.isEmpty()) player.sendMessage(msg);
    }

    private void sendVariableCostMessage(Player player, String varName, double amount) {
        String msg = configManager.getPlayerMessage("variable-cost",
                "%amount%", formatNumber(amount),
                "%variable%", varName);
        if (!msg.isEmpty()) player.sendMessage(msg);
    }

    public String formatNumber(double v) {
        if (v == (long) v) {
            return String.valueOf((long) v);
        }
        return String.format("%.2f", v);
    }

    private void log(String msg) {
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().warning(msg);
        }
    }
}
