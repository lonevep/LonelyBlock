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
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方块破坏监听器 - 基于 急迫/挖掘疲劳 buff 的挖掘速度控制
 *
 * <p>核心机制 (替代旧的自定义会话+发包裂纹):
 * <ul>
 *   <li>玩家对配置方块按左键 -> BlockDamageEvent:
 *     <ul>
 *       <li>有效工具: <b>不取消</b>, 让原版挖掘进行; 计算所需急迫/疲劳等级并施加 buff,
 *           原版挖掘速度被调整到配置目标时间. 原版裂纹动画天然同步.</li>
 *       <li>无效工具 / 无权限 / 条件不满足: 取消事件阻止挖掘, 发送提示.</li>
 *     </ul>
 *   </li>
 *   <li>玩家持续挖掘 -> PlayerAnimationEvent (挥手心跳) -> 刷新 buff 活动信号,
 *       {@link MiningBuffManager} 的 tick 任务持续续命 buff.</li>
 *   <li>挖掘完成 -> BlockBreakEvent: 取消原版破坏, 执行自定义逻辑
 *       (扣资源 -> 破坏 -> 掉落 -> 奖励 -> 消息 -> 命令 -> 恢复 -> 工具耐久), 清除 buff.</li>
 *   <li>玩家松开左键 / 切换目标 / 切换手持栏 / 退出 -> 清除 buff (还原原有效果).</li>
 * </ul>
 *
 * <p>挖掘速度计算 ({@link MiningSpeedCalculator}):
 * 根据配置的工具材质 (wood/stone/iron/...), 方块硬度, 目标时间,
 * 在 (急迫, 挖掘疲劳) 组合空间搜索最接近目标且不快于目标的等级组合.
 */
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

    // 提示冷却 (防止条件/权限不足时刷屏), 玩家UUID -> 上次提示毫秒
    private final Map<UUID, Long> messageCooldown = new HashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 1000L;

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

    /**
     * 启动 buff 管理器 (在插件 onEnable 时调用)
     */
    public void start() {
        buffManager.start();
    }

    /**
     * 关闭 buff 管理器 (在插件 onDisable 时调用)
     */
    public void shutdown() {
        buffManager.shutdown();
        messageCooldown.clear();
    }

    // ================================================================
    // BlockDamageEvent - 挖掘开始入口 (计算并施加 buff / 拦截无效工具)
    // ================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (block == null || block.getType() == Material.AIR) {
            return;
        }

        BlockConfig blockConfig = configManager.findMatchingBlock(block);
        if (blockConfig == null) {
            return; // 非自定义方块, 走原版逻辑
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        // 创造/旁观模式不应用自定义挖掘 (让玩家自由破坏)
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        handleMiningStart(player, block, blockConfig, event);
    }

    // ================================================================
    // PlayerAnimationEvent - 挖掘心跳 (玩家仍按住左键)
    // ================================================================

    /**
     * 玩家挥手动画事件 (左键挖掘时持续触发, 约每 6 tick 一次).
     * 用于刷新 buff 活动信号, 维持 buff 不过期.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        buffManager.refreshActivity(player);
    }

    // ================================================================
    // BlockBreakEvent - 挖掘完成, 执行自定义逻辑
    // ================================================================

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
        // 创造模式允许原版破坏 (不应用自定义逻辑)
        if (player != null && player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        // 取消原版破坏, 由本插件处理掉落/奖励/恢复/耐久
        event.setCancelled(true);

        // 清除挖掘 buff (挖掘完成)
        if (player != null) {
            buffManager.clearBuff(player);
        }

        if (player == null) {
            return;
        }

        // 再次判定工具有效性 (BlockBreakEvent 时再次确认; 无效工具走复原分支)
        ItemStack tool = getPlayerTool(player);
        boolean validTool;
        if (!blockConfig.hasTools()) {
            validTool = true;
        } else {
            BlockConfig.ToolEntry matched = toolMatcher.findMatch(tool, blockConfig.getTools());
            validTool = matched != null;
        }

        if (validTool) {
            handleValidToolBreak(player, block, blockConfig);
        } else {
            handleWrongToolBreak(player, block, blockConfig);
        }
    }

    // ================================================================
    // 玩家切换手持栏 / 退出 - 清除 buff
    // ================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        // 切换手持栏 = 换工具, 立即清除挖掘 buff
        buffManager.clearBuff(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        buffManager.clearBuff(event.getPlayer());
        messageCooldown.remove(event.getPlayer().getUniqueId());
    }

    // ================================================================
    // 挖掘开始处理 (计算 buff / 拦截)
    // ================================================================

    /**
     * 处理挖掘开始/继续.
     *
     * @param damageEvent BlockDamageEvent (可为 null, 来自其它入口时); 非 null 时用于拦截无效/无权限挖掘
     */
    private void handleMiningStart(Player player, Block block, BlockConfig blockConfig,
                                   BlockDamageEvent damageEvent) {
        // 切换目标方块时, 立即清除旧方块的 buff
        buffManager.clearBuffIfDifferentBlock(player, block);

        // 已有针对该方块的 buff = 持续挖掘中, 仅续命活动信号, 不重复计算 (避免 RandomValue 抖动)
        if (buffManager.hasBuffFor(player, block)) {
            buffManager.refreshActivity(player);
            return;
        }

        // ===== 新挖掘尝试 =====

        // 1. 使用权限
        if (configManager.isRequireUsePermission()
                && !player.hasPermission("lonelyblock.use")) {
            sendCooldownMessage(player, configManager.getPlayerMessage("no-permission"));
            if (damageEvent != null) {
                damageEvent.setCancelled(true);
            }
            return;
        }

        // 2. 挖掘条件 (权限/变量/资源)
        ConditionChecker.CheckResult checkResult = conditionChecker.check(player, blockConfig);
        if (!checkResult.isOk()) {
            sendConditionFailMessage(player, blockConfig, checkResult);
            if (damageEvent != null) {
                damageEvent.setCancelled(true);
            }
            return;
        }

        // 3. 获取工具, 判定有效性 + 目标时间 + 材质
        ItemStack tool = getPlayerTool(player);
        boolean validTool;
        double targetTime;
        String material = "";
        if (!blockConfig.hasTools()) {
            // 未配置工具列表 -> 任何工具都视为有效工具
            validTool = true;
            targetTime = blockConfig.rollDefaultTime();
            // 未配置工具, 自动从手持物推断材质
            material = MiningSpeedCalculator.detectMaterial(tool);
        } else {
            BlockConfig.ToolEntry matched = toolMatcher.findMatch(tool, blockConfig.getTools());
            if (matched != null) {
                double toolTime = matched.rollTime();
                validTool = true;
                targetTime = toolTime >= 0 ? toolTime : blockConfig.rollDefaultTime();
                // 配置指定了材质则用配置; 否则自动从手持物推断
                material = matched.hasMaterial()
                        ? matched.getMaterial()
                        : MiningSpeedCalculator.detectMaterial(tool);
            } else {
                // 未匹配 -> 错误工具: 拦截挖掘, 发送提示
                sendWrongToolMessage(player, blockConfig);
                if (damageEvent != null) {
                    damageEvent.setCancelled(true);
                }
                return;
            }
        }

        // 4. 有效工具: 不取消! 让原版挖掘进行, 通过 buff 控制速度
        //    应用工具 lore 的"挖掘速率"倍率 (调整目标时间)
        targetTime = applyMiningSpeedLore(tool, targetTime);

        // 5. 计算所需急迫/挖掘疲劳等级
        double toolSpeed = MiningSpeedCalculator.getMaterialSpeed(material);
        double hardness = MiningSpeedCalculator.getBlockHardness(block.getType(), blockConfig.getHardness());
        MiningSpeedCalculator.BuffResult buff = MiningSpeedCalculator.calculateBuff(
                toolSpeed, hardness, targetTime);

        // 6. 施加 buff + 刷新活动信号
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
        // 注意: 不取消 damageEvent, 让原版挖掘在 buff 控制下进行
    }

    // ================================================================
    // 挖掘完成 - 有效工具 / 无效工具
    // ================================================================

    /**
     * 有效工具挖掘完成: 扣除资源 -> 破坏 -> 掉落 -> 奖励 -> 消息 -> 命令 -> 恢复
     */
    private void handleValidToolBreak(Player player, Block block, BlockConfig config) {
        // 再次检查条件 (挖掘期间可能变化)
        ConditionChecker.CheckResult recheck = conditionChecker.check(player, config);
        if (!recheck.isOk()) {
            sendConditionFailMessage(player, config, recheck);
            return;
        }

        // 1. 捕获方块状态用于恢复 (在方块被破坏前)
        double restoreTime = config.rollRestoreTime();
        if (config.isRestoreEnabled() && restoreTime > 0) {
            restorationManager.scheduleRestore(block, restoreTime);
        }

        // 2. 扣除资源
        rewardManager.deductCosts(player, config);

        // 3. 破坏方块 (设为 AIR)
        setAir(block);

        // 3.5. 消耗玩家手持工具的耐久度 (考虑 Unbreakable 标签与耐久附魔)
        damageTool(player);

        // 4. 概率掉落物品 (应用工具 lore 的挖掘倍率: 掉落数量 × 倍率)
        Location dropLocation = block.getLocation().clone().add(0.5, 0.5, 0.5);
        double dropMultiplier = applyMiningMultiplierLore(getPlayerTool(player));
        for (DropConfig drop : config.getDrops()) {
            dropItemWithChance(player, block, drop, dropLocation, dropMultiplier);
        }

        // 5. 给予奖励
        rewardManager.giveRewards(player, config);

        // 6. 发送成功消息 (给玩家)
        sendSuccessMessage(player, config);

        // 7. 全服通告
        sendBroadcast(player, config);

        // 8. 执行命令
        if (config.hasCommands()) {
            commandExecutor.executeCommands(player, block.getLocation(), config.getCommands());
        }

        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("玩家 " + player.getName() + " 破坏了配置方块 "
                    + config.getName() + " @ " + block.getLocation());
        }
    }

    /**
     * 无效工具挖掘完成: 破坏 -> 立马复原 -> 错误工具提示 (不掉落任何物品)
     */
    private void handleWrongToolBreak(Player player, Block block, BlockConfig config) {
        // 捕获状态并安排 1 tick 后立即复原
        restorationManager.instantRestore(block);
        // 破坏方块 (设为 AIR, 1 tick 后由 instantRestore 恢复)
        setAir(block);
        // 发送错误工具提示
        sendWrongToolMessage(player, config);
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("玩家 " + player.getName() + " 用错误工具破坏 "
                    + config.getName() + " @ " + block.getLocation() + " (已立马复原)");
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 获取玩家手中的工具 (兼容1.8 - 1.9+)
     */
    private ItemStack getPlayerTool(Player player) {
        if (player == null) {
            return null;
        }
        PlayerInventory inventory = player.getInventory();
        if (VersionUtil.getMajorVersion() >= 9) {
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

    // ================================================================
    // 工具 lore 读取 (挖掘速率 / 挖掘倍率)
    // ================================================================

    /** 匹配 lore 行中第一个数字 (整数或小数, 可带负号) */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    /** 匹配并去除 & / § 颜色代码 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("[\u00a7&][0-9a-fk-orA-FK-OR]");

    /**
     * 从工具 lore 中读取指定关键字后的第一个数字.
     */
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

    /**
     * 读取工具 lore 中的"挖掘速率"倍率, 调整目标挖掘时间.
     * 最终目标时间 = 原时间 / 速率, 保留一位小数.
     */
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

    /**
     * 读取工具 lore 中的"挖掘倍率", 作为掉落数量的乘数.
     */
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

    /**
     * 将方块设为 AIR (兼容多版本)
     */
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

    // ================================================================
    // 工具耐久消耗
    // ================================================================

    /**
     * 消耗玩家手持工具的耐久度 (挖掘完成后调用)
     */
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
            int unbreakingLevel = getEnchantmentLevelSafe(tool, "DURABILITY");
            if (unbreakingLevel > 0) {
                double noConsumeChance = 1.0 / (unbreakingLevel + 1);
                if (Math.random() < noConsumeChance) {
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
            ItemStack brokenTool = tool.clone();
            brokenTool.setAmount(1);
            setItemInHandSafe(player, null);
            playItemBreakSoundSafe(player, brokenTool);
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
        int unbreakingLevel = getEnchantmentLevelSafe(tool, "DURABILITY");
        if (unbreakingLevel > 0) {
            double noConsumeChance = 1.0 / (unbreakingLevel + 1);
            if (Math.random() < noConsumeChance) {
                return;
            }
        }
        short currentDurability = tool.getDurability();
        short newDurability = (short) (currentDurability + 1);
        short maxDurability = type.getMaxDurability();

        if (newDurability >= maxDurability) {
            ItemStack brokenTool = tool.clone();
            setItemInHandSafe(player, null);
            playItemBreakSoundSafe(player, brokenTool);
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

    private int getEnchantmentLevelSafe(ItemStack item, String... enchantNames) {
        if (item == null || enchantNames == null) {
            return 0;
        }
        for (String name : enchantNames) {
            try {
                Enchantment ench = Enchantment.getByName(name);
                if (ench != null) {
                    return item.getEnchantmentLevel(ench);
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private void setItemInHandSafe(Player player, ItemStack item) {
        if (player == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (VersionUtil.getMajorVersion() >= 9) {
            try {
                inventory.setItemInMainHand(item);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            @SuppressWarnings("deprecation")
            ItemStack _ignored = item;
            inventory.setItemInHand(item);
        } catch (Throwable ignored) {
        }
    }

    private void playItemBreakSoundSafe(Player player, ItemStack item) {
        if (player == null) {
            return;
        }
        try {
            Sound sound;
            try {
                sound = Sound.valueOf("ENTITY_ITEM_BREAK");
            } catch (IllegalArgumentException e) {
                return;
            }
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    private void dropItemWithChance(Player player, Block block, DropConfig drop, Location dropLocation, double dropMultiplier) {
        double chance = drop.rollChance();
        if (chance < 100.0) {
            double roll = Math.random() * 100.0;
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

    // ================================================================
    // 消息发送
    // ================================================================

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
