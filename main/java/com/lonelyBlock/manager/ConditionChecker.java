package com.lonelyBlock.manager;

import com.lonelyBlock.config.BlockConfig;
import com.lonelyBlock.hook.PlaceholderAPIHook;
import com.lonelyBlock.hook.PlayerPointsHook;
import com.lonelyBlock.hook.VaultHook;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

 










public class ConditionChecker {

    private final VariableManager variableManager;

    public ConditionChecker(VariableManager variableManager) {
        this.variableManager = variableManager;
    }

     


    public static class CheckResult {
        public static final int OK = 0;
        public static final int NO_PERMISSION = 1;
        public static final int CONDITION_FAILED = 2;
        public static final int CANNOT_AFFORD = 3;

        private final int code;
        private final String detail;

        public CheckResult(int code) {
            this(code, "");
        }

        public CheckResult(int code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public int getCode() {
            return code;
        }

        public String getDetail() {
            return detail;
        }

        public boolean isOk() {
            return code == OK;
        }
    }

     




    public CheckResult check(Player player, BlockConfig config) {
        if (player == null || config == null) {
            return new CheckResult(CheckResult.CONDITION_FAILED);
        }

        
        if (config.hasRequiredPermissions()) {
            for (String perm : config.getRequiredPermissions()) {
                if (perm == null || perm.trim().isEmpty()) continue;
                if (!player.hasPermission(perm.trim())) {
                    return new CheckResult(CheckResult.NO_PERMISSION, perm);
                }
            }
        }

        
        if (config.hasVariableConditions()) {
            for (String cond : config.getVariableConditions()) {
                if (cond == null || cond.trim().isEmpty()) continue;
                if (!evaluateCondition(player, cond.trim())) {
                    return new CheckResult(CheckResult.CONDITION_FAILED, cond);
                }
            }
        }

        
        if (config.hasCostMoney() && VaultHook.isEnabled()) {
            double cost = config.rollCostMoney();
            if (cost > 0) {
                double bal = VaultHook.getEconomy().getBalance(player);
                if (bal < cost) {
                    return new CheckResult(CheckResult.CANNOT_AFFORD, "money");
                }
            }
        }
        if (config.hasCostPoints() && PlayerPointsHook.isEnabled()) {
            int cost = config.rollCostPoints();
            if (cost > 0) {
                int bal = PlayerPointsHook.getBalance(player);
                if (bal < cost) {
                    return new CheckResult(CheckResult.CANNOT_AFFORD, "points");
                }
            }
        }
        if (config.hasCostExp()) {
            int cost = config.rollCostExp();
            if (cost > 0 && getTotalExp(player) < cost) {
                return new CheckResult(CheckResult.CANNOT_AFFORD, "exp");
            }
        }
        if (config.hasCostVariables()) {
            for (BlockConfig.VariableEntry entry : config.getCostVariables()) {
                String varName = entry.getName();
                double amount = entry.rollValue();
                if (amount > 0 && !variableManager.has(player, varName, amount)) {
                    return new CheckResult(CheckResult.CANNOT_AFFORD, "variable:" + varName);
                }
            }
        }

        return new CheckResult(CheckResult.OK);
    }

     



    private boolean evaluateCondition(Player player, String cond) {
        
        String[] ops = {">=", "<=", "==", "!=", ">", "<", "="};
        String foundOp = null;
        int opIdx = -1;
        for (String op : ops) {
            int idx = cond.indexOf(op);
            if (idx > 0) {
                if (foundOp == null || idx < opIdx || (idx == opIdx && op.length() > foundOp.length())) {
                    foundOp = op;
                    opIdx = idx;
                }
            }
        }
        if (foundOp == null) {
            
            return true;
        }

        String left = cond.substring(0, opIdx).trim();
        String right = cond.substring(opIdx + foundOp.length()).trim();
        if (left.isEmpty() || right.isEmpty()) {
            return true;
        }

        double leftVal = resolveValue(player, left);
        double rightVal;
        try {
            rightVal = Double.parseDouble(right);
        } catch (NumberFormatException e) {
            
            rightVal = resolveValue(player, right);
        }

        switch (foundOp) {
            case ">=":
                return leftVal >= rightVal;
            case "<=":
                return leftVal <= rightVal;
            case "==":
            case "=":
                return leftVal == rightVal;
            case "!=":
                return leftVal != rightVal;
            case ">":
                return leftVal > rightVal;
            case "<":
                return leftVal < rightVal;
            default:
                return true;
        }
    }

     






    private double resolveValue(Player player, String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException ignored) {
        }
        
        if (PlaceholderAPIHook.isPlaceholder(token)) {
            String resolved = PlaceholderAPIHook.setPlaceholders(player, token);
            try {
                return Double.parseDouble(resolved.trim());
            } catch (NumberFormatException e) {
                
                String numPart = resolved.replaceAll("[^0-9.\\-]", "");
                try {
                    return numPart.isEmpty() ? 0 : Double.parseDouble(numPart);
                } catch (NumberFormatException e2) {
                    return 0;
                }
            }
        }
        
        return variableManager.get(player, token);
    }

     



    public static int getTotalExp(Player player) {
        if (player == null) return 0;
        try {
            int level = player.getLevel();
            float exp = player.getExp();
            int levelExp = getExpAtLevel(level);
            int nextLevelExp = getExpAtLevel(level + 1) - levelExp;
            return levelExp + (int) Math.floor(nextLevelExp * exp);
        } catch (Throwable e) {
            return 0;
        }
    }

     


    private static int getExpAtLevel(int level) {
        if (level <= 15) {
            return level * level + 6 * level;
        } else if (level <= 30) {
            return (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            return (int) (4.5 * level * level - 162.5 * level + 2220);
        }
    }
}
