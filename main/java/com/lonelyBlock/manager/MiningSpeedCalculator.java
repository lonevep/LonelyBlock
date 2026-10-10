package com.lonelyBlock.manager;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

 


































public final class MiningSpeedCalculator {

     
    private static final Map<String, Double> MATERIAL_SPEED = new HashMap<>();
     
    private static final Map<Material, Float> BLOCK_HARDNESS = new HashMap<>();

     
    private static final double DEFAULT_HARDNESS = 1.5;
     
    private static final double MIN_HARDNESS = 0.1;

     





    private static final int MAX_HASTE_AMP = 9;
     




    private static final int MAX_FATIGUE_AMP = 3;

    static {
        
        MATERIAL_SPEED.put("wood", 2.0);
        MATERIAL_SPEED.put("wooden", 2.0);
        MATERIAL_SPEED.put("stone", 4.0);
        MATERIAL_SPEED.put("iron", 6.0);
        MATERIAL_SPEED.put("diamond", 8.0);
        MATERIAL_SPEED.put("netherite", 9.0);
        MATERIAL_SPEED.put("gold", 12.0);
        MATERIAL_SPEED.put("golden", 12.0);
        MATERIAL_SPEED.put("hand", 1.0);   
        MATERIAL_SPEED.put("none", 1.0);   
        MATERIAL_SPEED.put("fist", 1.0);

        
        
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
        
        addHardness("IRON_BLOCK", 5.0f);
        addHardness("GOLD_BLOCK", 3.0f);
        addHardness("DIAMOND_BLOCK", 5.0f);
        addHardness("EMERALD_BLOCK", 5.0f);
        addHardness("NETHERITE_BLOCK", 50.0f);
        addHardness("COAL_BLOCK", 5.0f);
        addHardness("REDSTONE_BLOCK", 5.0f);
        addHardness("LAPIS_BLOCK", 3.0f);
        
        addHardness("ICE", 0.5f);
        addHardness("PACKED_ICE", 0.5f);
        addHardness("BLUE_ICE", 2.8f);
        
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
            
        }
    }

     





    public static double getMaterialSpeed(String material) {
        if (material == null) {
            return 1.0;
        }
        String key = material.trim().toLowerCase();
        Double speed = MATERIAL_SPEED.get(key);
        return speed != null ? speed : 1.0;
    }

     














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

     



    private static double hasteMult(int hasteAmp) {
        if (hasteAmp < 0) {
            return 1.0;
        }
        return 1.0 + 0.2 * (hasteAmp + 1);
    }

     













    private static double fatigueMult(int fatigueAmp) {
        if (fatigueAmp < 0) {
            return 1.0;
        }
        switch (fatigueAmp) {
            case 0:
                return 0.3;
            case 1:
                return 0.09;
            case 2:
                return 0.0027;
            default:
                return 0.00081;
        }
    }

     











    public static BuffResult calculateBuff(double toolSpeed, double hardness, double targetTime) {
        double safeSpeed = toolSpeed > 0 ? toolSpeed : 1.0;
        double safeHardness = Math.max(hardness, MIN_HARDNESS);
        double safeTarget = targetTime > 0 ? targetTime : 0.05;

        
        double tVanilla = safeHardness * 1.5 / safeSpeed;

        BuffResult best = new BuffResult(-1, -1, tVanilla);

        
        
        double bestTime = Double.POSITIVE_INFINITY;

        
        for (int h = -1; h <= MAX_HASTE_AMP; h++) {
            double hm = hasteMult(h);
            for (int f = -1; f <= MAX_FATIGUE_AMP; f++) {
                
                double fm = fatigueMult(f);
                double mult = hm * fm;
                if (mult <= 0) {
                    continue;
                }
                double tAchieved = tVanilla / mult;
                
                if (tAchieved >= safeTarget && tAchieved < bestTime) {
                    bestTime = tAchieved;
                    best = new BuffResult(h, f, tAchieved);
                }
            }
        }

        
        
        if (bestTime == Double.POSITIVE_INFINITY) {
            double tMax = tVanilla / fatigueMult(MAX_FATIGUE_AMP);
            best = new BuffResult(-1, MAX_FATIGUE_AMP, tMax);
        }
        return best;
    }

     






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
