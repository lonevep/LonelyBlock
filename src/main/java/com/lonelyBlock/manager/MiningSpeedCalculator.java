package com.lonelyBlock.manager;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * 挖掘速度计算器 - 基于原版机制通过急迫/挖掘疲劳效果控制挖掘速度
 *
 * <p>设计思路:
 * <ul>
 *   <li>不再使用自定义会话+发包裂纹动画 (旧机制存在不同步问题)</li>
 *   <li>而是让原版挖掘系统工作, 通过给予玩家 急迫(Haste)/挖掘疲劳(Mining Fatigue) 效果来调整挖掘速度</li>
 *   <li>原版裂纹动画由客户端与服务端共同计算, 效果一致, 天然同步</li>
 * </ul>
 *
 * <p>原版挖掘时间公式 (正确工具, 站立地面, 不在水下):
 * <pre>
 *   T_vanilla(秒) = 方块硬度 * 1.5 / 工具材质速度
 * </pre>
 *
 * <p>状态效果倍率:
 * <ul>
 *   <li>急迫 Haste (等级L=amplifier+1): 速度 *= (1 + 0.2 * L), 线性叠加 (Haste I=1.2x, II=1.4x, III=1.6x...)</li>
 *   <li>挖掘疲劳 Mining Fatigue (等级L=amplifier+1): 速度 *= 0.3^L, 几何衰减 (Fatigue I=0.3x, II=0.09x, III=0.027x...)</li>
 * </ul>
 *
 * <p>反算所需倍率:
 * <pre>
 *   requiredMult = T_vanilla / T_target = (硬度 * 1.5) / (工具速度 * 目标时间)
 * </pre>
 *
 * <p>由于急迫/疲劳等级为整数, 无法精确命中任意目标时间. 本计算器在 (hasteAmp, fatigueAmp)
 * 组合空间内暴力搜索, 选择 "<b>不快于目标</b> (T_achieved >= T_target) 的最小达成时间",
 * 即最接近目标且不会让玩家挖得比配置更快的组合. 这样既贴近目标又杜绝速度滥用.
 *
 * <p>组合搜索意义: 单纯急迫或疲劳的可达倍率稀疏 (如 1.2, 1.4, 1.6... 与 0.3, 0.09...),
 * 但 急迫+疲劳 组合可填充中间值 (如 Haste IV * Fatigue I = 1.8*0.3 = 0.54),
 * 显著提升精度. 例如目标 2.0s, 原版 1.125s: 纯 Fatigue I 得 3.75s (偏差大),
 * 而 Haste IV + Fatigue I 得 2.08s (接近目标).
 */
public final class MiningSpeedCalculator {

    /** 原版工具材质 -> 挖掘速度 (与 Minecraft 原版一致) */
    private static final Map<String, Double> MATERIAL_SPEED = new HashMap<>();
    /** 常见方块 -> 硬度 (用于未配置 hardness 时的自动查表; 不在表内则用默认值) */
    private static final Map<Material, Float> BLOCK_HARDNESS = new HashMap<>();

    /** 未指定 hardness 且不在硬度表内时的默认硬度 (近似石头) */
    private static final double DEFAULT_HARDNESS = 1.5;
    /** 硬度低于此值视为不可控 (原版瞬破), 计算时按此下限处理避免除零 */
    private static final double MIN_HARDNESS = 0.1;

    /**
     * 急迫搜索上限 (amplifier 0..MAX_HASTE_AMP).
     * 限制为 9 (Haste X, 倍率 3.0): 高等级急迫 (如 amp=20+) 在实际服务端/客户端不可靠,
     * 实测会出现"急迫不生效只剩疲劳"导致挖掘时间暴增 (如目标 8s 实际 60s).
     * amp 0-9 是常见可靠范围, 配合疲劳足以覆盖所需倍率.
     */
    private static final int MAX_HASTE_AMP = 9;
    /**
     * 挖掘疲劳搜索上限 (amplifier 0..MAX_FATIGUE_AMP).
     * Java 版硬上限: 疲劳倍率 = 0.3^min(level, 4), 即 amplifier 最大 3 (Fatigue IV, 0.0081x).
     * 超过 amplifier 3 的等级被原版截断为 IV, 无额外减速效果, 故搜索上限锁定 3.
     */
    private static final int MAX_FATIGUE_AMP = 3;

    static {
        // 工具材质速度 (原版值)
        MATERIAL_SPEED.put("wood", 2.0);
        MATERIAL_SPEED.put("wooden", 2.0);
        MATERIAL_SPEED.put("stone", 4.0);
        MATERIAL_SPEED.put("iron", 6.0);
        MATERIAL_SPEED.put("diamond", 8.0);
        MATERIAL_SPEED.put("netherite", 9.0);
        MATERIAL_SPEED.put("gold", 12.0);
        MATERIAL_SPEED.put("golden", 12.0);
        MATERIAL_SPEED.put("hand", 1.0);   // 空手
        MATERIAL_SPEED.put("none", 1.0);   // 无工具
        MATERIAL_SPEED.put("fist", 1.0);

        // 常见方块硬度表 (Minecraft wiki 数据, 1.13+ Material 名)
        // 石类
        addHardness("STONE", 1.5f);
        addHardness("COBBLESTONE", 2.0f);
        addHardness("MOSSY_COBBLESTONE", 2.0f);
        addHardness("COBBLED_DEEPSLATE", 3.0f);
        addHardness("DEEPSLATE", 3.0f);
        addHardness("GRANITE", 1.5f);
        addHardness("DIORITE", 1.5f);
        addHardness("ANDESITE", 1.5f);
        addHardness("BEDROCK", 50.0f);
        addHardness("OBSIDIAN", 50.0f);
        addHardness("CRYING_OBSIDIAN", 50.0f);
        addHardness("NETHERRACK", 0.4f);
        addHardness("BLACKSTONE", 1.5f);
        addHardness("BASALT", 1.25f);
        addHardness("SMOOTH_BASALT", 1.25f);
        addHardness("END_STONE", 3.0f);
        addHardness("SANDSTONE", 0.8f);
        addHardness("RED_SANDSTONE", 0.8f);
        // 矿石
        addHardness("COAL_ORE", 3.0f);
        addHardness("IRON_ORE", 3.0f);
        addHardness("COPPER_ORE", 3.0f);
        addHardness("GOLD_ORE", 3.0f);
        addHardness("DIAMOND_ORE", 3.0f);
        addHardness("EMERALD_ORE", 3.0f);
        addHardness("REDSTONE_ORE", 3.0f);
        addHardness("LAPIS_ORE", 3.0f);
        addHardness("NETHER_QUARTZ_ORE", 3.0f);
        addHardness("NETHER_GOLD_ORE", 3.0f);
        addHardness("ANCIENT_DEBRIS", 30.0f);
        addHardness("DEEPSLATE_COAL_ORE", 4.5f);
        addHardness("DEEPSLATE_IRON_ORE", 4.5f);
        addHardness("DEEPSLATE_COPPER_ORE", 4.5f);
        addHardness("DEEPSLATE_GOLD_ORE", 4.5f);
        addHardness("DEEPSLATE_DIAMOND_ORE", 4.5f);
        addHardness("DEEPSLATE_EMERALD_ORE", 4.5f);
        addHardness("DEEPSLATE_REDSTONE_ORE", 4.5f);
        addHardness("DEEPSLATE_LAPIS_ORE", 4.5f);
        // 原木 / 木板
        addHardness("OAK_LOG", 2.0f);
        addHardness("SPRUCE_LOG", 2.0f);
        addHardness("BIRCH_LOG", 2.0f);
        addHardness("JUNGLE_LOG", 2.0f);
        addHardness("ACACIA_LOG", 2.0f);
        addHardness("DARK_OAK_LOG", 2.0f);
        addHardness("MANGROVE_LOG", 2.0f);
        addHardness("CHERRY_LOG", 2.0f);
        addHardness("CRIMSON_STEM", 2.0f);
        addHardness("WARPED_STEM", 2.0f);
        addHardness("OAK_PLANKS", 2.0f);
        addHardness("SPRUCE_PLANKS", 2.0f);
        addHardness("BIRCH_PLANKS", 2.0f);
        addHardness("JUNGLE_PLANKS", 2.0f);
        addHardness("ACACIA_PLANKS", 2.0f);
        addHardness("DARK_OAK_PLANKS", 2.0f);
        addHardness("MANGROVE_PLANKS", 2.0f);
        addHardness("CHERRY_PLANKS", 2.0f);
        addHardness("CRIMSON_PLANKS", 2.0f);
        addHardness("WARPED_PLANKS", 2.0f);
        // 泥土 / 沙砾
        addHardness("DIRT", 0.5f);
        addHardness("GRASS_BLOCK", 0.6f);
        addHardness("GRASS_PATH", 0.6f);
        addHardness("DIRT_PATH", 0.6f);
        addHardness("SAND", 0.5f);
        addHardness("RED_SAND", 0.5f);
        addHardness("GRAVEL", 0.6f);
        addHardness("CLAY", 0.6f);
        addHardness("SOUL_SAND", 0.5f);
        addHardness("SOUL_SOIL", 0.5f);
        // 矿物块
        addHardness("IRON_BLOCK", 5.0f);
        addHardness("GOLD_BLOCK", 3.0f);
        addHardness("DIAMOND_BLOCK", 5.0f);
        addHardness("EMERALD_BLOCK", 5.0f);
        addHardness("NETHERITE_BLOCK", 50.0f);
        addHardness("COAL_BLOCK", 5.0f);
        addHardness("REDSTONE_BLOCK", 5.0f);
        addHardness("LAPIS_BLOCK", 3.0f);
        // 冰 / 雪
        addHardness("ICE", 0.5f);
        addHardness("PACKED_ICE", 0.5f);
        addHardness("BLUE_ICE", 2.8f);
        // 其它常见
        addHardness("TERRACOTTA", 1.25f);
        addHardness("BRICKS", 2.0f);
        addHardness("NETHER_BRICKS", 2.0f);
        addHardness("STONE_BRICKS", 1.5f);
        addHardness("MUD_BRICKS", 2.0f);
        addHardness("SAND", 0.5f);
    }

    private MiningSpeedCalculator() {
    }

    private static void addHardness(String materialName, float hardness) {
        try {
            Material m = Material.matchMaterial(materialName);
            if (m != null) {
                BLOCK_HARDNESS.put(m, hardness);
            }
        } catch (Throwable ignored) {
            // 跨版本可能不存在该 Material, 跳过
        }
    }

    /**
     * 获取工具材质对应的原版挖掘速度.
     *
     * @param material 材质名 (wood/stone/iron/diamond/netherite/gold/hand 等, 大小写不敏感)
     * @return 挖掘速度; 未知材质返回 1.0 (空手)
     */
    public static double getMaterialSpeed(String material) {
        if (material == null) {
            return 1.0;
        }
        String key = material.trim().toLowerCase();
        Double speed = MATERIAL_SPEED.get(key);
        return speed != null ? speed : 1.0;
    }

    /**
     * 从物品的 Material 自动推断工具材质名 (用于配置未指定 material, 或未配置 tools 的场景).
     *
     * 识别原版工具 (镐/斧/锹/锄/剑) 的材质前缀:
     *   WOODEN_* / WOOD_*  -> wood
     *   STONE_*            -> stone
     *   IRON_*             -> iron
     *   DIAMOND_*          -> diamond
     *   NETHERITE_*        -> netherite
     *   GOLDEN_* / GOLD_*  -> gold
     *   其它               -> hand (空手, 速度 1.0)
     *
     * @param tool 玩家手持物品
     * @return 材质名 (小写); 无法识别返回 "hand"
     */
    public static String detectMaterial(org.bukkit.inventory.ItemStack tool) {
        if (tool == null) {
            return "hand";
        }
        Material m = tool.getType();
        if (m == null) {
            return "hand";
        }
        String name = m.name();
        if (name.startsWith("WOODEN_") || name.startsWith("WOOD_")) {
            return "wood";
        }
        if (name.startsWith("STONE_")) {
            return "stone";
        }
        if (name.startsWith("IRON_")) {
            return "iron";
        }
        if (name.startsWith("DIAMOND_")) {
            return "diamond";
        }
        if (name.startsWith("NETHERITE_")) {
            return "netherite";
        }
        if (name.startsWith("GOLDEN_") || name.startsWith("GOLD_")) {
            return "gold";
        }
        return "hand";
    }

    /**
     * 获取方块硬度.
     * 优先级: configHardness > 硬度表 > 默认值(1.5).
     *
     * @param blockMaterial 方块 Material
     * @param configHardness 配置中指定的硬度 (<=0 表示未指定, 走自动查表)
     * @return 方块硬度 (至少 MIN_HARDNESS, 避免除零)
     */
    public static double getBlockHardness(Material blockMaterial, double configHardness) {
        double hardness;
        if (configHardness > 0) {
            hardness = configHardness;
        } else if (blockMaterial != null) {
            Float fromTable = BLOCK_HARDNESS.get(blockMaterial);
            hardness = fromTable != null ? fromTable.floatValue() : DEFAULT_HARDNESS;
        } else {
            hardness = DEFAULT_HARDNESS;
        }
        return Math.max(hardness, MIN_HARDNESS);
    }

    /**
     * 计算急迫倍率 (1 + 0.2 * level), level = amplifier + 1.
     * hasteAmp = -1 表示无急迫 (倍率 1.0).
     */
    private static double hasteMult(int hasteAmp) {
        if (hasteAmp < 0) {
            return 1.0;
        }
        return 1.0 + 0.2 * (hasteAmp + 1);
    }

    /**
     * 计算挖掘疲劳倍率 0.3^min(amplifier+1, 4).
     * Java 版硬上限: 疲劳等级 (amplifier+1) 超过 4 时被截断为 4 (即 amplifier>3 无额外效果).
     * fatigueAmp = -1 表示无疲劳 (倍率 1.0).
     */
    private static double fatigueMult(int fatigueAmp) {
        if (fatigueAmp < 0) {
            return 1.0;
        }
        int level = Math.min(fatigueAmp + 1, 4);
        return Math.pow(0.3, level);
    }

    /**
     * 计算达成目标挖掘时间所需的急迫/挖掘疲劳等级.
     *
     * <p>搜索策略: 遍历 (hasteAmp, fatigueAmp) 组合 (含"无效果"), 在所有
     * "T_achieved >= T_target" (不快于目标) 的组合中, 选 T_achieved 最小者
     * (最接近目标). 这样玩家永远不会挖得比配置更快, 同时尽量贴近目标秒数.
     *
     * @param toolSpeed  工具材质速度 (来自 config material, 如 wood=2.0)
     * @param hardness   方块硬度
     * @param targetTime 目标挖掘时间 (秒)
     * @return BuffResult, 包含急迫/疲劳 amplifier (无效果时为 -1) 与实际达成时间
     */
    public static BuffResult calculateBuff(double toolSpeed, double hardness, double targetTime) {
        double safeSpeed = toolSpeed > 0 ? toolSpeed : 1.0;
        double safeHardness = Math.max(hardness, MIN_HARDNESS);
        double safeTarget = targetTime > 0 ? targetTime : 0.05;

        // 原版挖掘时间 (无任何效果)
        double tVanilla = safeHardness * 1.5 / safeSpeed;

        BuffResult best = new BuffResult(-1, -1, tVanilla);

        // 若原版时间已 >= 目标 (即原版比目标慢, 需加速), 无效果可能即最优下界;
        // 若原版时间 < 目标 (需减速), 必须用疲劳. 统一在组合空间搜索.
        double bestTime = Double.POSITIVE_INFINITY;

        // 包含 "无效果" (h=-1, f=-1) 与所有急迫/疲劳/组合
        for (int h = -1; h <= MAX_HASTE_AMP; h++) {
            double hm = hasteMult(h);
            for (int f = -1; f <= MAX_FATIGUE_AMP; f++) {
                // 跳过 h=-1 & f=-1 之外的逻辑由公式处理: h=-1,f=-1 -> mult=1.0
                double fm = fatigueMult(f);
                double mult = hm * fm;
                if (mult <= 0) {
                    continue;
                }
                double tAchieved = tVanilla / mult;
                // 要求不快于目标, 且比当前最优更接近目标 (更小)
                if (tAchieved >= safeTarget && tAchieved < bestTime) {
                    bestTime = tAchieved;
                    best = new BuffResult(h, f, tAchieved);
                }
            }
        }

        // 兜底: 若无任何组合满足 >= 目标 (理论上不会发生, 因最大疲劳会让 T 极大),
        // 返回最大减速组合 (最大疲劳, 无急迫)
        if (bestTime == Double.POSITIVE_INFINITY) {
            double tMax = tVanilla / fatigueMult(MAX_FATIGUE_AMP);
            best = new BuffResult(-1, MAX_FATIGUE_AMP, tMax);
        }
        return best;
    }

    /**
     * Buff 计算结果
     *
     * @param hasteAmp    急迫 amplifier (0=Haste I); -1 表示不施加急迫
     * @param fatigueAmp  挖掘疲劳 amplifier (0=Fatigue I); -1 表示不施加疲劳
     * @param achievedTime 实际达成挖掘时间 (秒, >= 目标时间)
     */
    public static final class BuffResult {
        private final int hasteAmp;
        private final int fatigueAmp;
        private final double achievedTime;

        public BuffResult(int hasteAmp, int fatigueAmp, double achievedTime) {
            this.hasteAmp = hasteAmp;
            this.fatigueAmp = fatigueAmp;
            this.achievedTime = achievedTime;
        }

        public int getHasteAmp() {
            return hasteAmp;
        }

        public int getFatigueAmp() {
            return fatigueAmp;
        }

        public double getAchievedTime() {
            return achievedTime;
        }

        public boolean hasHaste() {
            return hasteAmp >= 0;
        }

        public boolean hasFatigue() {
            return fatigueAmp >= 0;
        }
    }
}
