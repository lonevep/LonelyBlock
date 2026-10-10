package com.lonelyBlock.config;

import com.lonelyBlock.util.RandomValue;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

 



















public class BlockConfig {

    private final String name;

    
    private final List<String> blockIds;

    
    private final List<String> requiredPermissions;
    private final List<String> variableConditions;

    
    private final List<ToolEntry> tools;     
    private final RandomValue defaultTime;   

    
    private final double hardness;

    
    private final List<DropConfig> drops;

    
    private final RandomValue rewardMoney;
    private final RandomValue rewardPoints;
    private final RandomValue rewardExp;
    private final List<VariableEntry> rewardVariables;   

    
    private final RandomValue costMoney;
    private final RandomValue costPoints;
    private final RandomValue costExp;
    private final List<VariableEntry> costVariables;

    
    private final String msgSuccess;
    private final String msgBroadcast;
    private final String msgWrongTool;
    private final String msgConditionFailed;
    private final String msgNoPermission;
    private final String msgCannotAfford;

    
    private final boolean restoreEnabled;
    private final RandomValue restoreTime;

    
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

     






    public static class ToolEntry {
        private final String toolDefinition;
        private final RandomValue time;
        private final String material;

        public ToolEntry(String toolDefinition, RandomValue time, String material) {
            this.toolDefinition = toolDefinition;
            this.time = time != null ? time : RandomValue.of(0);
            this.material = material != null ? material.trim() : "";
        }

         
        public ToolEntry(String toolDefinition, RandomValue time) {
            this(toolDefinition, time, "");
        }

        public String getToolDefinition() {
            return toolDefinition;
        }

        public RandomValue getTime() {
            return time;
        }

         



        public String getMaterial() {
            return material;
        }

        public boolean hasMaterial() {
            return material != null && !material.isEmpty();
        }

         


        public double rollTime() {
            return Math.max(0, time.roll());
        }
    }

     



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

     


    public static BlockConfig fromSection(String name, ConfigurationSection section) {
        if (section == null) {
            return null;
        }

        
        List<String> blockIds = readStringList(section, "block-ids", "block", "id");

        
        List<String> requiredPermissions = new ArrayList<>();
        List<String> variableConditions = new ArrayList<>();
        if (section.isConfigurationSection("conditions")) {
            ConfigurationSection cond = section.getConfigurationSection("conditions");
            requiredPermissions = readStringList(cond, "permissions", "permission", "perm");
            variableConditions = readStringList(cond, "variables", "variable", "var");
        }

        
        List<ToolEntry> tools = new ArrayList<>();
        if (section.isList("tools")) {
            for (Object obj : section.getList("tools")) {
                ToolEntry entry = parseToolEntry(obj);
                if (entry != null) {
                    tools.add(entry);
                }
            }
        } else if (section.isConfigurationSection("tools")) {
            
            ConfigurationSection toolsSec = section.getConfigurationSection("tools");
            for (String key : toolsSec.getKeys(false)) {
                String toolDef = key.trim();
                if (toolDef.isEmpty()) continue;
                RandomValue t = RandomValue.parse(toolsSec.get(key));
                tools.add(new ToolEntry(toolDef, t));
            }
        }
        RandomValue defaultTime = RandomValue.parse(section.get("default-time", "1.0"));

        
        double hardness = section.getDouble("hardness", -1.0);
        
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
            
            rewardMoney = RandomValue.parse(section.get("money", "0"));
        }

        
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
                
                time = RandomValue.of(-1);
            }
            Object matObj = map.get("material");
            String material = matObj != null ? matObj.toString() : "";
            return new ToolEntry(tool, time, material);
        }
        
        String s = obj.toString().trim();
        if (s.isEmpty()) return null;
        return new ToolEntry(s, RandomValue.of(-1), "");
    }

     


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

     


    public double rollDefaultTime() {
        return Math.max(0, defaultTime.roll());
    }

     


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
