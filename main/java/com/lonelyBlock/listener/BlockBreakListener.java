package com.lonelyBlock.listener;

import com.lonelyBlock.LonelyBlock;
import com.lonelyBlock.config.BlockConfig;
import com.lonelyBlock.config.ConfigManager;
import com.lonelyBlock.config.DropConfig;
import com.lonelyBlock.hook.NeigeItemsHook;
import com.lonelyBlock.hook.PlaceholderAPIHook;
import com.lonelyBlock.manager.CommandExecutor;
import com.lonelyBlock.manager.ConditionChecker;
import com.lonelyBlock.manager.ItemManager;
import com.lonelyBlock.manager.MiningBuffManager;
import com.lonelyBlock.manager.MiningSpeedCalculator;
import com.lonelyBlock.manager.RestorationManager;
import com.lonelyBlock.manager.RewardManager;
import com.lonelyBlock.manager.ToolMatcher;
import com.lonelyBlock.util.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlockBreakListener implements Listener {

    private final LonelyBlock plugin;
    private final ConfigManager configManager;
    private final ItemManager itemManager;
    private final ToolMatcher toolMatcher;
    private final RestorationManager restorationManager;
    private final CommandExecutor commandExecutor;
    private final ConditionChecker conditionChecker;
    private final RewardManager rewardManager;
    private final MiningBuffManager buffManager;

    private final Map<UUID, Long> messageCooldown = new HashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 1000L;

    private static final Enchantment UNBREAKING_ENCHANT = resolveEnchantment("UNBREAKING", "DURABILITY");
    private static final Sound ITEM_BREAK_SOUND = resolveSound("ENTITY_ITEM_BREAK", "ITEM_BREAK");

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern COLOR_PATTERN = Pattern.compile("[\u00a7&][0-9a-fk-orA-FK-OR]");

    private static Enchantment resolveEnchantment(String... names) {
        for (String name : names) {
            try {
                Enchantment ench = Enchantment.getByName(name);
                if (ench != null) {
                    return ench;
                }
            } catch (Throwable ignored) {
            }
        }
        for (String name : names) {
            Enchantment byRegistry = resolveByRegistry("ENCHANTMENT", name.toLowerCase(Locale.ROOT), Enchantment.class);
            if (byRegistry != null) {
                return byRegistry;
            }
        }
        return null;
    }

    private static Sound resolveSound(String... names) {
        for (String name : names) {
            try {
                return Sound.valueOf(name);
            } catch (Throwable ignored) {
            }
        }
        for (String name : names) {
            Sound byRegistry = resolveByRegistry("SOUNDS", name.toLowerCase(Locale.ROOT).replace('_', '.'), Sound.class);
            if (byRegistry != null) {
                return byRegistry;
            }
        }
        return null;
    }

    private static <T> T resolveByRegistry(String fieldName, String registryKey, Class<T> type) {
        try {
            Class<?> registryClass = Class.forName("org.bukkit.Registry");
            Object registry = registryClass.getField(fieldName).get(null);
            Class<?> keyClass = Class.forName("org.bukkit.NamespacedKey");
            Method minecraftKey = keyClass.getMethod("minecraft", String.class);
            Object namespacedKey = minecraftKey.invoke(null, registryKey);
            Method getMethod = registry.getClass().getMethod("get", keyClass);
            Object resolved = getMethod.invoke(registry, namespacedKey);
            if (type.isInstance(resolved)) {
                return type.cast(resolved);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public BlockBreakListener(LonelyBlock plugin, ConfigManager configManager,
                              ItemManager itemManager, ToolMatcher toolMatcher,
                              RestorationManager restorationManager,
                              CommandExecutor commandExecutor,
                              ConditionChecker conditionChecker,
                              RewardManager rewardManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.toolMatcher = toolMatcher;
        this.restorationManager = restorationManager;
        this.commandExecutor = commandExecutor;
        this.conditionChecker = conditionChecker;
        this.rewardManager = rewardManager;
        this.buffManager = new MiningBuffManager(plugin);
    }

    public void start() {
        buffManager.start();
    }

    public void shutdown() {
        buffManager.shutdown();
        messageCooldown.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (block == null || block.getType() == Material.AIR) {
            return;
        }

        BlockConfig blockConfig = configManager.findMatchingBlock(block);
        if (blockConfig == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        handleMiningStart(player, block, blockConfig, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (!buffManager.hasActiveSessions()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        buffManager.refreshActivity(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block == null || block.getType() == Material.AIR) {
            return;
        }
        BlockConfig blockConfig = configManager.findMatchingBlock(block);
        if (blockConfig == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null && player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        event.setCancelled(true);

        if (player != null) {
            buffManager.clearBuff(player);
        }

        if (player == null) {
            return;
        }

        ItemStack tool = getPlayerTool(player);
        boolean validTool;
        if (!blockConfig.hasTools()) {
            validTool = true;
        } else {
            BlockConfig.ToolEntry matched = toolMatcher.findMatch(tool, blockConfig.getTools());
            validTool = matched != null;
        }

        if (validTool) {
            handleValidToolBreak(player, block, blockConfig, tool);
        } else {
            handleWrongToolBreak(player, block, blockConfig);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        buffManager.clearBuff(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        buffManager.clearBuff(event.getPlayer());
        messageCooldown.remove(event.getPlayer().getUniqueId());
    }

    private void handleMiningStart(Player player, Block block, BlockConfig blockConfig,
                                   BlockDamageEvent damageEvent) {
        if (buffManager.refreshIfSameBlock(player, block)) {
            return;
        }

        if (configManager.isRequireUsePermission()
                && !player.hasPermission("lonelyblock.use")) {
            sendCooldownMessage(player, configManager.getPlayerMessage("no-permission"));
            if (damageEvent != null) {
                damageEvent.setCancelled(true);
            }
            return;
        }

        ConditionChecker.CheckResult checkResult = conditionChecker.check(player, blockConfig);
        if (!checkResult.isOk()) {
            sendConditionFailMessage(player, blockConfig, checkResult);
            if (damageEvent != null) {
                damageEvent.setCancelled(true);
            }
            return;
        }

        ItemStack tool = getPlayerTool(player);
        boolean validTool;
        double targetTime;
        String material = "";
        if (!blockConfig.hasTools()) {
            validTool = true;
            targetTime = blockConfig.rollDefaultTime();
            material = MiningSpeedCalculator.detectMaterial(tool);
        } else {
            BlockConfig.ToolEntry matched = toolMatcher.findMatch(tool, blockConfig.getTools());
            if (matched != null) {
                double toolTime = matched.rollTime();
                validTool = true;
                targetTime = toolTime >= 0 ? toolTime : blockConfig.rollDefaultTime();
                material = matched.hasMaterial()
                        ? matched.getMaterial()
                        : MiningSpeedCalculator.detectMaterial(tool);
            } else {
                sendWrongToolMessage(player, blockConfig);
                if (damageEvent != null) {
                    damageEvent.setCancelled(true);
                }
                return;
            }
        }

        targetTime = applyMiningSpeedLore(tool, targetTime);

        double toolSpeed = MiningSpeedCalculator.getMaterialSpeed(material);
        double hardness = MiningSpeedCalculator.getBlockHardness(block.getType(), blockConfig.getHardness());
        MiningSpeedCalculator.BuffResult buff = MiningSpeedCalculator.calculateBuff(
                toolSpeed, hardness, targetTime);

        buffManager.applyBuff(player, block, buff.getHasteAmp(), buff.getFatigueAmp());
        buffManager.refreshActivity(player);

        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[挖掘] " + player.getName()
                    + " 方块=" + blockConfig.getName()
                    + " 材质=" + (material.isEmpty() ? "(未指定)" : material) + "(速度=" + toolSpeed + ")"
                    + " 硬度=" + hardness
                    + " 目标=" + round1(targetTime) + "s"
                    + " 达成=" + round1(buff.getAchievedTime()) + "s"
                    + " 急迫amp=" + buff.getHasteAmp()
                    + " 疲劳amp=" + buff.getFatigueAmp());
        }
    }

    private void handleValidToolBreak(Player player, Block block, BlockConfig config, ItemStack tool) {
        ConditionChecker.CheckResult recheck = conditionChecker.check(player, config);
        if (!recheck.isOk()) {
            sendConditionFailMessage(player, config, recheck);
            return;
        }

        double restoreTime = config.rollRestoreTime();
        if (config.isRestoreEnabled() && restoreTime > 0) {
            restorationManager.scheduleRestore(block, restoreTime);
        }

        rewardManager.deductCosts(player, config);

        setAir(block);

        try {
            damageTool(player);
        } catch (Throwable e) {
            if (LonelyBlock.isDebug()) {
                plugin.getLogger().warning("耐久扣除失败: " + e.getMessage());
            }
        }

        Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
        double dropMultiplier = applyMiningMultiplierLore(tool);
        for (DropConfig drop : config.getDrops()) {
            dropItemWithChance(player, block, drop, dropLocation, dropMultiplier);
        }

        rewardManager.giveRewards(player, config);

        sendSuccessMessage(player, config);

        sendBroadcast(player, config);

        if (config.hasCommands()) {
            commandExecutor.executeCommands(player, block.getLocation(), config.getCommands());
        }

        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("玩家 " + player.getName() + " 破坏了配置方块 "
                    + config.getName() + " @ " + block.getLocation());
        }
    }

    private void handleWrongToolBreak(Player player, Block block, BlockConfig config) {
        restorationManager.instantRestore(block);
        setAir(block);
        sendWrongToolMessage(player, config);
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("玩家 " + player.getName() + " 用错误工具破坏 "
                    + config.getName() + " @ " + block.getLocation() + " (已立马复原)");
        }
    }

    private ItemStack getPlayerTool(Player player) {
        if (player == null) {
            return null;
        }
        PlayerInventory inventory = player.getInventory();
        if (VersionUtil.supportsMainHand()) {
            try {
                return inventory.getItemInMainHand();
            } catch (Throwable ignored) {
            }
        }
        try {
            @SuppressWarnings("deprecation")
            ItemStack item = inventory.getItemInHand();
            return item;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Double readLoreValue(ItemStack tool, String keyword) {
        if (keyword == null || keyword.isEmpty() || tool == null) {
            return null;
        }
        ItemMeta meta;
        try {
            if (!tool.hasItemMeta()) {
                return null;
            }
            meta = tool.getItemMeta();
        } catch (Throwable ignored) {
            return null;
        }
        if (meta == null || !meta.hasLore()) {
            return null;
        }
        List<String> lore;
        try {
            lore = meta.getLore();
        } catch (Throwable ignored) {
            return null;
        }
        if (lore == null || lore.isEmpty()) {
            return null;
        }
        for (String line : lore) {
            if (line == null) {
                continue;
            }
            String stripped = stripColorCodes(line);
            int idx = stripped.indexOf(keyword);
            if (idx < 0) {
                continue;
            }
            String after = stripped.substring(idx + keyword.length());
            Double val = parseFirstNumber(after);
            if (val != null) {
                return val;
            }
        }
        return null;
    }

    private double applyMiningSpeedLore(ItemStack tool, double requiredSeconds) {
        Double rate = readLoreValue(tool, configManager.getMiningSpeedLore());
        if (rate == null || rate <= 0) {
            return requiredSeconds;
        }
        double adjusted = requiredSeconds / rate;
        double rounded = Math.round(adjusted * 10.0) / 10.0;
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[挖掘速率] lore倍率=" + rate
                    + " 原时间=" + requiredSeconds + "s 调整后=" + rounded + "s");
        }
        return rounded;
    }

    private double applyMiningMultiplierLore(ItemStack tool) {
        Double mult = readLoreValue(tool, configManager.getMiningMultiplierLore());
        if (mult == null || mult <= 0) {
            return 1.0;
        }
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[挖掘倍率] lore倍率=" + mult);
        }
        return mult;
    }

    private String stripColorCodes(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return COLOR_PATTERN.matcher(s).replaceAll("");
        } catch (Throwable ignored) {
            return s;
        }
    }

    private Double parseFirstNumber(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            Matcher m = NUMBER_PATTERN.matcher(s);
            if (m.find()) {
                return Double.parseDouble(m.group());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String round1(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private void setAir(Block block) {
        try {
            block.setType(Material.AIR, false);
        } catch (Throwable e) {
            try {
                block.setType(Material.AIR);
            } catch (Throwable ignored) {
            }
        }
    }

    private void damageTool(Player player) {
        if (player == null) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        ItemStack tool = getPlayerTool(player);
        if (tool == null) {
            return;
        }
        Material type = tool.getType();
        if (type == null || type == Material.AIR) {
            return;
        }

        boolean niApi = NeigeItemsHook.isDurabilityApiAvailable();
        boolean niItem = niApi && NeigeItemsHook.isNeigeItemsItem(tool);
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[耐久] 玩家=" + player.getName()
                    + " 物品=" + type + " NI耐久API可用=" + niApi
                    + " 是NI物品=" + niItem);
        }
        if (niItem) {
            damageNeigeItemsTool(player, tool);
            return;
        }

        damageVanillaTool(player, tool, type);
    }

    private void damageNeigeItemsTool(Player player, ItemStack tool) {
        if (!NeigeItemsHook.durabilityApiHandlesUnbreaking()) {
            int unbreakingLevel = getEnchantmentLevelSafe(tool);
            if (unbreakingLevel > 0) {
                double noConsumeChance = 1.0 / (unbreakingLevel + 1);
                if (ThreadLocalRandom.current().nextDouble() < noConsumeChance) {
                    return;
                }
            }
        }
        ItemStack result = NeigeItemsHook.damageNeigeItemsItem(player, tool, 1);
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[耐久] 调用 NI 耐久API(damage=1) 结果="
                    + (result != null ? "成功" : "失败")
                    + " 返回amount=" + (result != null ? result.getAmount() : -1));
        }
        if (result == null) {
            return;
        }
        if (result.getAmount() <= 0 || result.getType() == Material.AIR) {
            setItemInHandSafe(player, null);
            playItemBreakSoundSafe(player);
        } else {
            increaseVanillaDamageCritical(result);
            setItemInHandSafe(player, result);
        }
    }

    private void increaseVanillaDamageCritical(ItemStack item) {
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type == null || type == Material.AIR) {
            return;
        }
        short maxDurability = type.getMaxDurability();
        if (maxDurability <= 0) {
            return;
        }
        short currentDamage = item.getDurability();
        if (currentDamage + 1 >= maxDurability) {
            if (LonelyBlock.isDebug()) {
                plugin.getLogger().info("[耐久] NI物品原版破损值已到临界 ("
                        + currentDamage + "/" + maxDurability + "), 停止增加");
            }
            return;
        }
        item.setDurability((short) (currentDamage + 1));
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[耐久] NI物品原版破损值增加: "
                    + currentDamage + " -> " + (currentDamage + 1)
                    + " (max=" + maxDurability + ")");
        }
    }

    private void damageVanillaTool(Player player, ItemStack tool, Material type) {
        if (type.getMaxDurability() <= 0) {
            return;
        }
        if (isUnbreakable(tool)) {
            return;
        }
        int unbreakingLevel = getEnchantmentLevelSafe(tool);
        if (unbreakingLevel > 0) {
            double noConsumeChance = 1.0 / (unbreakingLevel + 1);
            if (ThreadLocalRandom.current().nextDouble() < noConsumeChance) {
                return;
            }
        }
        short currentDurability = tool.getDurability();
        short newDurability = (short) (currentDurability + 1);
        short maxDurability = type.getMaxDurability();

        if (newDurability >= maxDurability) {
            setItemInHandSafe(player, null);
            playItemBreakSoundSafe(player);
        } else {
            tool.setDurability(newDurability);
            setItemInHandSafe(player, tool);
        }
    }

    private boolean isUnbreakable(ItemStack item) {
        if (item == null) {
            return false;
        }
        try {
            if (!item.hasItemMeta()) {
                return false;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return false;
            }
            try {
                Method m = ItemMeta.class.getMethod("isUnbreakable");
                Object result = m.invoke(meta);
                return Boolean.TRUE.equals(result);
            } catch (NoSuchMethodException ignored) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int getEnchantmentLevelSafe(ItemStack item) {
        if (item == null || UNBREAKING_ENCHANT == null) {
            return 0;
        }
        try {
            return item.getEnchantmentLevel(UNBREAKING_ENCHANT);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void setItemInHandSafe(Player player, ItemStack item) {
        if (player == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (VersionUtil.supportsMainHand()) {
            try {
                inventory.setItemInMainHand(item);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            inventory.setItemInHand(item);
        } catch (Throwable ignored) {
        }
    }

    private void playItemBreakSoundSafe(Player player) {
        if (player == null || ITEM_BREAK_SOUND == null) {
            return;
        }
        try {
            player.playSound(player.getLocation(), ITEM_BREAK_SOUND, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    private void dropItemWithChance(Player player, Block block, DropConfig drop, Location dropLocation, double dropMultiplier) {
        double chance = drop.rollChance();
        if (chance < 100.0) {
            double roll = ThreadLocalRandom.current().nextDouble() * 100.0;
            if (roll >= chance) {
                return;
            }
        }
        int amount = drop.rollAmount();
        if (amount <= 0) {
            return;
        }
        if (dropMultiplier != 1.0 && dropMultiplier > 0) {
            amount = (int) Math.round(amount * dropMultiplier);
            if (amount < 1) {
                amount = 1;
            }
        }
        ItemManager.ItemResult result = itemManager.getItem(drop, amount);
        if (result.isSuccess()) {
            try {
                block.getWorld().dropItemNaturally(dropLocation, result.getItem());
            } catch (Throwable e) {
                if (LonelyBlock.isDebug()) {
                    plugin.getLogger().warning("掉落物品 [" + drop + "] 失败: " + e.getMessage());
                }
            }
        } else {
            String errorMsg;
            String error = result.getErrorMessage();
            if ("mythicmobs-not-found".equals(error)) {
                errorMsg = configManager.getPlayerMessage("mythicmobs-not-found",
                        "%item_id%", result.getItemId());
            } else if ("neigeitems-not-found".equals(error)) {
                errorMsg = configManager.getPlayerMessage("neigeitems-not-found",
                        "%item_id%", result.getItemId());
            } else {
                errorMsg = configManager.getPlayerMessage("item-not-found",
                        "%item_id%", result.getItemId());
            }
            if (!errorMsg.isEmpty()) {
                player.sendMessage(errorMsg);
            }
            if (LonelyBlock.isDebug()) {
                plugin.getLogger().warning("掉落物 [" + drop + "] 加载失败: " + error);
            }
        }
    }

    private void sendSuccessMessage(Player player, BlockConfig config) {
        if (!config.hasCustomSuccessMessage() && config.getMsgSuccess().isEmpty()) {
            return;
        }
        String raw = config.getMsgSuccess();
        String msg = buildMessage(raw, player, config);
        if (!msg.isEmpty()) {
            player.sendMessage(msg);
        }
    }

    private void sendBroadcast(Player player, BlockConfig config) {
        if (config.getMsgBroadcast() == null || config.getMsgBroadcast().trim().isEmpty()) {
            return;
        }
        String msg = buildMessage(config.getMsgBroadcast(), player, config);
        if (msg.isEmpty()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
        }
    }

    private void sendWrongToolMessage(Player player, BlockConfig config) {
        String message;
        if (config.hasCustomWrongToolMessage()) {
            message = configManager.getPlayerMessageFromRaw(config.getMsgWrongTool(),
                    "%player_name%", player.getName());
        } else {
            message = configManager.getPlayerMessage("invalid-tool");
        }
        if (!message.isEmpty()) {
            sendCooldownMessage(player, message);
        }
    }

    private void sendConditionFailMessage(Player player, BlockConfig config, ConditionChecker.CheckResult result) {
        String raw;
        switch (result.getCode()) {
            case ConditionChecker.CheckResult.NO_PERMISSION:
                raw = config.hasCustomNoPermissionMessage() ? config.getMsgNoPermission() : "";
                if (raw.isEmpty()) {
                    sendCooldownMessage(player, configManager.getPlayerMessage("no-permission-mine"));
                } else {
                    sendCooldownMessage(player, configManager.getPlayerMessageFromRaw(raw,
                            "%player_name%", player.getName()));
                }
                return;
            case ConditionChecker.CheckResult.CANNOT_AFFORD:
                raw = config.hasCustomCannotAffordMessage() ? config.getMsgCannotAfford() : "";
                if (raw.isEmpty()) {
                    sendCooldownMessage(player, configManager.getPlayerMessage("cannot-afford"));
                } else {
                    sendCooldownMessage(player, configManager.getPlayerMessageFromRaw(raw,
                            "%player_name%", player.getName()));
                }
                return;
            case ConditionChecker.CheckResult.CONDITION_FAILED:
            default:
                raw = config.hasCustomConditionFailedMessage() ? config.getMsgConditionFailed() : "";
                if (raw.isEmpty()) {
                    sendCooldownMessage(player, configManager.getPlayerMessage("condition-failed"));
                } else {
                    sendCooldownMessage(player, configManager.getPlayerMessageFromRaw(raw,
                            "%player_name%", player.getName()));
                }
                return;
        }
    }

    private String buildMessage(String raw, Player player, BlockConfig config) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String msg = raw;
        msg = msg.replace("%player_name%", player.getName());
        msg = msg.replace("%player_uuid%", player.getUniqueId().toString());
        Location playerLoc = player.getLocation();
        msg = msg.replace("%player_x%", String.valueOf(playerLoc.getBlockX()));
        msg = msg.replace("%player_y%", String.valueOf(playerLoc.getBlockY()));
        msg = msg.replace("%player_z%", String.valueOf(playerLoc.getBlockZ()));
        msg = msg.replace("%player_world%", playerLoc.getWorld() != null ? playerLoc.getWorld().getName() : "");
        msg = msg.replace("%money%", rewardManager.formatNumber(config.rollRewardMoney()));
        msg = msg.replace("%points%", String.valueOf(config.rollRewardPoints()));
        msg = msg.replace("%exp%", String.valueOf(config.rollRewardExp()));
        msg = PlaceholderAPIHook.setPlaceholders(player, msg);
        msg = configManager.getPlayerMessageFromRaw(msg);
        return msg;
    }

    private void sendCooldownMessage(Player player, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Long last = messageCooldown.get(uuid);
        long now = System.currentTimeMillis();
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
            return;
        }
        messageCooldown.put(uuid, now);
        player.sendMessage(message);
    }
}
