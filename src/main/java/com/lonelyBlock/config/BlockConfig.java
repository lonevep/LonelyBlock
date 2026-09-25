package com.lonelyBlock.config;

import com.lonelyBlock.util.RandomValue;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个方块的配置信息
 *
 * 包含:
 *   - 方块ID列表 (跨版本)
 *   - 挖掘条件 (权限 + 变量)
 *   - 工具与挖掘时间 (工具列表 + 默认时间, 时间支持随机值)
 *   - 掉落物 (含概率与 CustomModelData, 数量/概率支持随机值)
 *   - 奖励 (金币/点券/经验/自定义变量, 数值支持随机值)
 *   - 扣除 (金币/点券/经验/自定义变量, 数值支持随机值)
 *   - 消息 (成功/通告/错误工具/条件失败/权限不足/资源不足)
 *   - 方块恢复 (时间支持随机值)
 *   - 命令 (op:/console:)
 *
 * 随机值格式:
 *   - "5" / "5.5"            固定值
 *   - "1_5"                  随机范围 [1, 5], 默认 0 位小数
 *   - "1.5_5.5_2"            随机范围 [1.5, 5.5], 保留 2 位小数
 *   变量条目中的数值同样支持 (如 "mining_count:1_5")
 */
public class BlockConfig {

    private final String name;

    // 方块ID
    private final List<String> blockIds;

    // 挖掘条件
    private final List<String> requiredPermissions;
    private final List<String> variableConditions;

    // 工具与挖掘时间
    private final List<ToolEntry> tools;     // 工具+挖掘时间+材质
    private final RandomValue defaultTime;   // 未列出工具的默认挖掘时间(秒)

    // 方块硬度 (用于挖掘速度计算; <=0 表示未指定, 走自动查表)
    private final double hardness;

    // 掉落物
    private final List<DropConfig> drops;

    // 奖励 (随机值)
    private final RandomValue rewardMoney;
    private final RandomValue rewardPoints;
    private final RandomValue rewardExp;
    private final List<VariableEntry> rewardVariables;   // 变量名 + 随机数值

    // 扣除 (随机值)
    private final RandomValue costMoney;
    private final RandomValue costPoints;
    private final RandomValue costExp;
    private final List<VariableEntry> costVariables;

    // 消息 (覆盖默认, 空字符串表示用全局默认)
    private final String msgSuccess;
    private final String msgBroadcast;
    private final String msgWrongTool;
    private final String msgConditionFailed;
    private final String msgNoPermission;
    private final String msgCannotAfford;

    // 恢复
    private final boolean restoreEnabled;
    private final RandomValue restoreTime;

    // 命令
    private final List<String> commands;

    public BlockConfig(String name, List<String> blockIds,
                       List<String> requiredPermissions, List<String> variableConditions,
                       List<ToolEntry> tools, RandomValue defaultTime, double hardness,
                       List<DropConfig> drops,
                       RandomValue rewardMoney, RandomValue rewardPoints, RandomValue rewardExp,
                       List<VariableEntry> rewardVariables,
                       RandomValue costMoney, RandomValue costPoints, RandomValue costExp,
                       List<VariableEntry> costVariables,
                       String msgSuccess, String msgBroadcast, String msgWrongTool,
                       String msgConditionFailed, String msgNoPermission, String msgCannotAfford,
                       boolean restoreEnabled, RandomValue restoreTime,
                       List<String> commands) {
        this.name = name;
        this.blockIds = blockIds != null ? blockIds : Collections.<String>emptyList();
        this.requiredPermissions = requiredPermissions != null ? requiredPermissions : Collections.<String>emptyList();
        this.variableConditions = variableConditions != null ? variableConditions : Collections.<String>emptyList();
        this.tools = tools != null ? tools : Collections.<ToolEntry>emptyList();
        this.defaultTime = defaultTime != null ? defaultTime : RandomValue.of(1.0);
        this.hardness = hardness;
        this.drops = drops != null ? drops : Collections.<DropConfig>emptyList();
        this.rewardMoney = rewardMoney != null ? rewardMoney : RandomValue.of(0);
        this.rewardPoints = rewardPoints != null ? rewardPoints : RandomValue.of(0);
        this.rewardExp = rewardExp != null ? rewardExp : RandomValue.of(0);
        this.rewardVariables = rewardVariables != null ? rewardVariables : Collections.<VariableEntry>emptyList();
        this.costMoney = costMoney != null ? costMoney : RandomValue.of(0);
        this.costPoints = costPoints != null ? costPoints : RandomValue.of(0);
        this.costExp = costExp != null ? costExp : RandomValue.of(0);
        this.costVariables = costVariables != null ? costVariables : Collections.<VariableEntry>emptyList();
        this.msgSuccess = msgSuccess != null ? msgSuccess : "";
        this.msgBroadcast = msgBroadcast != null ? msgBroadcast : "";
        this.msgWrongTool = msgWrongTool != null ? msgWrongTool : "";
        this.msgConditionFailed = msgConditionFailed != null ? msgConditionFailed : "";
        this.msgNoPermission = msgNoPermission != null ? msgNoPermission : "";
        this.msgCannotAfford = msgCannotAfford != null ? msgCannotAfford : "";
        this.restoreEnabled = restoreEnabled;
        this.restoreTime = restoreTime != null ? restoreTime : RandomValue.of(0);
        this.commands = commands != null ? commands : Collections.<String>emptyList();
    }

    /**
     * 工具条目: 工具定义 + 挖掘时间 (随机值) + 工具材质
     *
     * material 字段指定工具对应的原版材质 (wood/stone/iron/diamond/netherite/gold/hand),
     * 用于内部计算急迫/挖掘疲劳等级以达成目标挖掘时间. 用户需确保所填材质与物品实际材质一致,
     * 否则挖掘时间会有偏差.
     */
    public static class ToolEntry {
        private final String toolDefinition;
        private final RandomValue time;
        private final String material;

        public ToolEntry(String toolDefinition, RandomValue time, String material) {
            this.toolDefinition = toolDefinition;
            this.time = time != null ? time : RandomValue.of(0);
            this.material = material != null ? material.trim() : "";
        }

        /** 兼容旧调用 (无材质) */
        public ToolEntry(String toolDefinition, RandomValue time) {
            this(toolDefinition, time, "");
        }

        public String getToolDefinition() {
            return toolDefinition;
        }

        public RandomValue getTime() {
            return time;
        }

        /**
         * 获取工具材质名 (小写, 如 "wood"/"stone"/"iron"/"diamond"/"netherite"/"gold"/"hand").
         * 空字符串表示未指定 (将由计算器按空手处理或自动检测).
         */
        public String getMaterial() {
            return material;
        }

        public boolean hasMaterial() {
            return material != null && !material.isEmpty();
        }

        /**
         * 滚动本次挖掘时间 (秒)
         */
        public double rollTime() {
            return Math.max(0, time.roll());
        }
    }

    /**
     * 变量条目: 变量名 + 随机数值
     * 如 "mining_count:1_5" -> name="mining_count", value=RandomValue.range(1,5,0)
     */
    public static class VariableEntry {
        private final String name;
        private final RandomValue value;

        public VariableEntry(String name, RandomValue value) {
            this.name = name;
            this.value = value != null ? value : RandomValue.of(0);
        }

        public String getName() {
            return name;
        }

        public RandomValue getValue() {
            return value;
        }

        public double rollValue() {
            return value.roll();
        }

        public int rollValueInt() {
            return value.rollInt();
        }
    }

    /**
     * 从 ConfigurationSection 加载 BlockConfig
     */
    public static BlockConfig fromSection(String name, ConfigurationSection section) {
        if (section == null) {
            return null;
        }

        // 方块ID列表
        List<String> blockIds = readStringList(section, "block-ids", "block", "id");

        // 挖掘条件
        List<String> requiredPermissions = new ArrayList<>();
        List<String> variableConditions = new ArrayList<>();
        if (section.isConfigurationSection("conditions")) {
            ConfigurationSection cond = section.getConfigurationSection("conditions");
            requiredPermissions = readStringList(cond, "permissions", "permission", "perm");
            variableConditions = readStringList(cond, "variables", "variable", "var");
        }

        // 工具与挖掘时间
        List<ToolEntry> tools = new ArrayList<>();
        if (section.isList("tools")) {
            for (Object obj : section.getList("tools")) {
                ToolEntry entry = parseToolEntry(obj);
                if (entry != null) {
                    tools.add(entry);
                }
            }
        } else if (section.isConfigurationSection("tools")) {
            // tools 为映射形式: { "name:木斧": 2.5 }
            ConfigurationSection toolsSec = section.getConfigurationSection("tools");
            for (String key : toolsSec.getKeys(false)) {
                String toolDef = key.trim();
                if (toolDef.isEmpty()) continue;
                RandomValue t = RandomValue.parse(toolsSec.get(key));
                tools.add(new ToolEntry(toolDef, t));
            }
        }
        RandomValue defaultTime = RandomValue.parse(section.get("default-time", "1.0"));

        // 方块硬度 (可选; <=0 表示未指定, 计算时走自动查表/默认值)
        double hardness = section.getDouble("hardness", -1.0);
        // 兼容旧拼写
        if (hardness <= 0) {
            Object hObj = section.get("block-hardness");
            if (hObj != null) {
                try {
                    hardness = Double.parseDouble(hObj.toString());
                } catch (Throwable ignored) {
                    hardness = -1.0;
                }
            }
        }

        // 掉落物
        List<DropConfig> drops = new ArrayList<>();
        if (section.isList("drops")) {
            for (Object obj : section.getList("drops")) {
                DropConfig drop = DropConfig.parse(obj);
                if (drop != null) {
                    drops.add(drop);
                }
            }
        } else if (section.isString("drops")) {
            DropConfig drop = DropConfig.parse(section.getString("drops"));
            if (drop != null) {
                drops.add(drop);
            }
        }

        // 奖励
        RandomValue rewardMoney = RandomValue.of(0);
        RandomValue rewardPoints = RandomValue.of(0);
        RandomValue rewardExp = RandomValue.of(0);
        List<VariableEntry> rewardVariables = new ArrayList<>();
        if (section.isConfigurationSection("rewards")) {
            ConfigurationSection r = section.getConfigurationSection("rewards");
            rewardMoney = RandomValue.parse(r.get("money", "0"));
            rewardPoints = RandomValue.parse(r.get("points", "0"));
            rewardExp = RandomValue.parse(r.get("exp", "0"));
            rewardVariables = parseVariableList(readStringList(r, "variables", "variable", "var"));
        } else {
            // 兼容旧字段
            rewardMoney = RandomValue.parse(section.get("money", "0"));
        }

        // 扣除
        RandomValue costMoney = RandomValue.of(0);
        RandomValue costPoints = RandomValue.of(0);
        RandomValue costExp = RandomValue.of(0);
        List<VariableEntry> costVariables = new ArrayList<>();
        if (section.isConfigurationSection("costs")) {
            ConfigurationSection c = section.getConfigurationSection("costs");
            costMoney = RandomValue.parse(c.get("money", "0"));
            costPoints = RandomValue.parse(c.get("points", "0"));
            costExp = RandomValue.parse(c.get("exp", "0"));
            costVariables = parseVariableList(readStringList(c, "variables", "variable", "var"));
        }

        // 消息
        String msgSuccess = "";
        String msgBroadcast = "";
        String msgWrongTool = "";
        String msgConditionFailed = "";
        String msgNoPermission = "";
        String msgCannotAfford = "";
        if (section.isConfigurationSection("messages")) {
            ConfigurationSection m = section.getConfigurationSection("messages");
            msgSuccess = m.getString("success", "");
            msgBroadcast = m.getString("broadcast", "");
            msgWrongTool = m.getString("wrong-tool", "");
            msgConditionFailed = m.getString("condition-failed", "");
            msgNoPermission = m.getString("no-permission", "");
            msgCannotAfford = m.getString("cannot-afford", "");
        } else {
            msgWrongTool = section.getString("wrong-tool-message", "");
        }

        // 恢复
        boolean restoreEnabled = false;
        RandomValue restoreTime = RandomValue.of(0);
        if (section.isConfigurationSection("restore")) {
            ConfigurationSection rs = section.getConfigurationSection("restore");
            restoreEnabled = rs.getBoolean("enabled", false);
            restoreTime = RandomValue.parse(rs.get("time", "0"));
        } else {
            restoreEnabled = section.getBoolean("restore-enabled", false);
            restoreTime = RandomValue.parse(section.get("restore-time", "0"));
        }

        // 命令
        List<String> commands = readStringList(section, "commands", "command", "cmd");

        return new BlockConfig(name, blockIds,
                requiredPermissions, variableConditions,
                tools, defaultTime, hardness,
                drops,
                rewardMoney, rewardPoints, rewardExp, rewardVariables,
                costMoney, costPoints, costExp, costVariables,
                msgSuccess, msgBroadcast, msgWrongTool,
                msgConditionFailed, msgNoPermission, msgCannotAfford,
                restoreEnabled, restoreTime,
                commands);
    }

    /**
     * 解析变量条目列表
     * 每条格式: "变量名:数值" 数值可为固定值或随机范围 "1_5" / "1.5_5.5_2"
     */
    private static List<VariableEntry> parseVariableList(List<String> entries) {
        List<VariableEntry> result = new ArrayList<>();
        if (entries == null) return result;
        for (String entry : entries) {
            VariableEntry ve = parseVariableEntry(entry);
            if (ve != null) {
                result.add(ve);
            }
        }
        return result;
    }

    /**
     * 解析单个变量条目 "变量名:数值"
     * 数值支持固定值或随机范围
     */
    public static VariableEntry parseVariableEntry(String entry) {
        if (entry == null) return null;
        String s = entry.trim();
        if (s.isEmpty()) return null;
        int colon = s.lastIndexOf(':');
        if (colon <= 0) return null;
        String name = s.substring(0, colon).trim();
        String valueStr = s.substring(colon + 1).trim();
        if (name.isEmpty() || valueStr.isEmpty()) return null;
        return new VariableEntry(name, RandomValue.parse(valueStr));
    }

    /**
     * 解析单个工具条目 (来自 list 元素)
     * 支持映射: { tool: "name:木斧", time: 2.5, material: "wood" }
     * 或字符串: "name:木斧" (使用默认时间, 无材质)
     */
    @SuppressWarnings("unchecked")
    private static ToolEntry parseToolEntry(Object obj) {
        if (obj == null) return null;
        if (obj instanceof ConfigurationSection) {
            ConfigurationSection sec = (ConfigurationSection) obj;
            String tool = sec.getString("tool", sec.getString("id", ""));
            if (tool == null || tool.trim().isEmpty()) return null;
            RandomValue time = RandomValue.parse(sec.get("time", sec.get("seconds", "0")));
            String material = sec.getString("material", "");
            return new ToolEntry(tool.trim(), time, material);
        }
        if (obj instanceof java.util.Map) {
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) obj;
            Object toolObj = map.get("tool");
            if (toolObj == null) toolObj = map.get("id");
            if (toolObj == null) return null;
            String tool = toolObj.toString().trim();
            if (tool.isEmpty()) return null;
            Object timeObj = map.get("time");
            if (timeObj == null) timeObj = map.get("seconds");
            RandomValue time = timeObj != null ? RandomValue.parse(timeObj) : null;
            if (time == null) {
                // -1 表示使用 default-time
                time = RandomValue.of(-1);
            }
            Object matObj = map.get("material");
            String material = matObj != null ? matObj.toString() : "";
            return new ToolEntry(tool, time, material);
        }
        // 字符串: 仅工具定义, 使用默认时间 (用 -1 标记走 default-time)
        String s = obj.toString().trim();
        if (s.isEmpty()) return null;
        return new ToolEntry(s, RandomValue.of(-1), "");
    }

    /**
     * 读取字符串列表 (兼容 list / 单 string / 多个别名键)
     */
    private static List<String> readStringList(ConfigurationSection section, String... keys) {
        List<String> result = new ArrayList<>();
        if (section == null) return result;
        for (String key : keys) {
            if (section.isList(key)) {
                for (Object obj : section.getList(key)) {
                    if (obj != null) {
                        String s = obj.toString().trim();
                        if (!s.isEmpty()) {
                            result.add(s);
                        }
                    }
                }
                return result;
            } else if (section.isString(key)) {
                String s = section.getString(key).trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
                return result;
            }
        }
        return result;
    }

    // ===== Getters =====

    public String getName() {
        return name;
    }

    public List<String> getBlockIds() {
        return blockIds;
    }

    public List<String> getRequiredPermissions() {
        return requiredPermissions;
    }

    public boolean hasRequiredPermissions() {
        return !requiredPermissions.isEmpty();
    }

    public List<String> getVariableConditions() {
        return variableConditions;
    }

    public boolean hasVariableConditions() {
        return !variableConditions.isEmpty();
    }

    public List<ToolEntry> getTools() {
        return tools;
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public RandomValue getDefaultTime() {
        return defaultTime;
    }

    /**
     * 滚动本次默认挖掘时间 (秒)
     */
    public double rollDefaultTime() {
        return Math.max(0, defaultTime.roll());
    }

    /**
     * 获取方块硬度 (<=0 表示未指定, 由计算器自动查表/默认).
     */
    public double getHardness() {
        return hardness;
    }

    public List<DropConfig> getDrops() {
        return drops;
    }

    public boolean hasDrops() {
        return !drops.isEmpty();
    }

    public RandomValue getRewardMoney() {
        return rewardMoney;
    }

    public boolean hasRewardMoney() {
        return rewardMoney.isPositive();
    }

    public double rollRewardMoney() {
        return rewardMoney.roll();
    }

    public RandomValue getRewardPoints() {
        return rewardPoints;
    }

    public boolean hasRewardPoints() {
        return rewardPoints.isPositive();
    }

    public int rollRewardPoints() {
        return rewardPoints.rollInt();
    }

    public RandomValue getRewardExp() {
        return rewardExp;
    }

    public boolean hasRewardExp() {
        return rewardExp.isPositive();
    }

    public int rollRewardExp() {
        return rewardExp.rollInt();
    }

    public List<VariableEntry> getRewardVariables() {
        return rewardVariables;
    }

    public boolean hasRewardVariables() {
        return !rewardVariables.isEmpty();
    }

    public RandomValue getCostMoney() {
        return costMoney;
    }

    public boolean hasCostMoney() {
        return costMoney.isPositive();
    }

    public double rollCostMoney() {
        return costMoney.roll();
    }

    public RandomValue getCostPoints() {
        return costPoints;
    }

    public boolean hasCostPoints() {
        return costPoints.isPositive();
    }

    public int rollCostPoints() {
        return costPoints.rollInt();
    }

    public RandomValue getCostExp() {
        return costExp;
    }

    public boolean hasCostExp() {
        return costExp.isPositive();
    }

    public int rollCostExp() {
        return costExp.rollInt();
    }

    public List<VariableEntry> getCostVariables() {
        return costVariables;
    }

    public boolean hasCostVariables() {
        return !costVariables.isEmpty();
    }

    public String getMsgSuccess() {
        return msgSuccess;
    }

    public boolean hasCustomSuccessMessage() {
        return msgSuccess != null && !msgSuccess.trim().isEmpty();
    }

    public String getMsgBroadcast() {
        return msgBroadcast;
    }

    public String getMsgWrongTool() {
        return msgWrongTool;
    }

    public boolean hasCustomWrongToolMessage() {
        return msgWrongTool != null && !msgWrongTool.trim().isEmpty();
    }

    public String getMsgConditionFailed() {
        return msgConditionFailed;
    }

    public boolean hasCustomConditionFailedMessage() {
        return msgConditionFailed != null && !msgConditionFailed.trim().isEmpty();
    }

    public String getMsgNoPermission() {
        return msgNoPermission;
    }

    public boolean hasCustomNoPermissionMessage() {
        return msgNoPermission != null && !msgNoPermission.trim().isEmpty();
    }

    public String getMsgCannotAfford() {
        return msgCannotAfford;
    }

    public boolean hasCustomCannotAffordMessage() {
        return msgCannotAfford != null && !msgCannotAfford.trim().isEmpty();
    }

    public boolean isRestoreEnabled() {
        return restoreEnabled;
    }

    public RandomValue getRestoreTime() {
        return restoreTime;
    }

    /**
     * 滚动本次恢复时间 (秒)
     */
    public double rollRestoreTime() {
        return Math.max(0, restoreTime.roll());
    }

    public List<String> getCommands() {
        return commands;
    }

    public boolean hasCommands() {
        return !commands.isEmpty();
    }

    @Override
    public String toString() {
        return "BlockConfig{name=" + name + ", blockIds=" + blockIds.size()
                + ", tools=" + tools.size() + ", drops=" + drops.size()
                + ", money=" + rewardMoney + ", commands=" + commands.size()
                + ", restore=" + restoreEnabled + "/" + restoreTime + "}";
    }
}
