package com.lonelyBlock.manager;

import com.lonelyBlock.config.BlockConfig;
import com.lonelyBlock.hook.PlaceholderAPIHook;
import com.lonelyBlock.hook.PlayerPointsHook;
import com.lonelyBlock.hook.VaultHook;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * 条件检查器 - 检查玩家是否满足挖掘方块的条件
 *
 * 检查项:
 *   1. 权限: 玩家必须拥有 conditions.permissions 中的所有权限
 *   2. 变量条件: conditions.variables 中的所有条件需满足
 *      格式: "变量名 操作符 数值"
 *      操作符: >= | <= | == | != | > | <
 *      变量名: 本插件内部变量 (如 ll_level) 或 PAPI 占位符 (如 %player_points%)
 *   3. 资源充足: costs 中的金币/点券/经验/变量 玩家需拥有足够数量
 */
public class ConditionChecker {

    private final VariableManager variableManager;

    public ConditionChecker(VariableManager variableManager) {
        this.variableManager = variableManager;
    }

    /**
     * 检查结果
     */
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

    /**
     * 检查玩家是否满足挖掘方块的所有条件 (权限 + 变量条件 + 资源充足)
     *
     * @return CheckResult, code=OK 表示全部满足
     */
    public CheckResult check(Player player, BlockConfig config) {
        if (player == null || config == null) {
            return new CheckResult(CheckResult.CONDITION_FAILED);
        }

        // 1. 权限检查
        if (config.hasRequiredPermissions()) {
            for (String perm : config.getRequiredPermissions()) {
                if (perm == null || perm.trim().isEmpty()) continue;
                if (!player.hasPermission(perm.trim())) {
                    return new CheckResult(CheckResult.NO_PERMISSION, perm);
                }
            }
        }

        // 2. 变量条件检查
        if (config.hasVariableConditions()) {
            for (String cond : config.getVariableConditions()) {
                if (cond == null || cond.trim().isEmpty()) continue;
                if (!evaluateCondition(player, cond.trim())) {
                    return new CheckResult(CheckResult.CONDITION_FAILED, cond);
                }
            }
        }

        // 3. 资源充足检查 (扣除项) - 检查时滚动随机值, 可能与实际扣除略有差异
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

    /**
     * 解析并求值单个条件表达式
     * 格式: "<变量/占位符> <操作符> <数值>"
     */
    private boolean evaluateCondition(Player player, String cond) {
        // 找到操作符 (按长度优先, 避免 >= 被识别为 >)
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
            // 无操作符, 视为不合法条件, 跳过 (返回 true 不阻断)
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
            // 右侧可能是占位符/变量
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

    /**
     * 解析左/右操作数为数值
     * 支持:
     *   - 纯数字
     *   - PAPI 占位符 (%...%)
     *   - 本插件内部变量名
     */
    private double resolveValue(Player player, String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        // 纯数字
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException ignored) {
        }
        // PAPI 占位符
        if (PlaceholderAPIHook.isPlaceholder(token)) {
            String resolved = PlaceholderAPIHook.setPlaceholders(player, token);
            try {
                return Double.parseDouble(resolved.trim());
            } catch (NumberFormatException e) {
                // 解析失败 (如返回 "无" / "false" 等), 尝试去数字
                String numPart = resolved.replaceAll("[^0-9.\\-]", "");
                try {
                    return numPart.isEmpty() ? 0 : Double.parseDouble(numPart);
                } catch (NumberFormatException e2) {
                    return 0;
                }
            }
        }
        // 本插件内部变量
        return variableManager.get(player, token);
    }

    /**
     * 获取玩家总经验值 (跨版本兼容)
     * 计算公式: 1.8+ 经验 = level^2 + 6*level (0-15级) 等分段
     */
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

    /**
     * 计算到达指定等级所需的总经验 (1.8+ 通用公式)
     */
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
