package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挖掘 Buff 管理器 - 通过急迫/挖掘疲劳效果控制挖掘速度 (替代旧的自定义会话+发包机制)
 *
 * <p>核心思路: 让原版挖掘系统工作, 由本管理器在玩家挖掘目标方块时给予计算好的
 * 急迫(Haste)/挖掘疲劳(Mining Fatigue) 效果, 从而把原版挖掘时间调整到配置目标.
 * 原版裂纹动画由客户端/服务端协同计算, 天然同步, 彻底解决旧机制的不同步问题.
 *
 * <p>Buff 生命周期:
 * <ul>
 *   <li>玩家对配置方块按左键 -> BlockDamageEvent -> 调用 applyBuff (保存原有同类效果, 施加我们的 buff)</li>
 *   <li>玩家持续挖掘 -> PlayerAnimationEvent/BlockDamageEvent 持续 refreshActivity -> tick 任务定期 refreshBuff (续命时长)</li>
 *   <li>玩家松开左键 / 切换目标 / 切换手持栏 / 破坏完成 / 退出 -> clearBuff (移除我们的效果, 还原原有同类效果)</li>
 * </ul>
 *
 * <p>原版效果保护: 施加我们的 buff 前会保存玩家原有的同类效果 (同种才保存),
 * 清除时移除当前效果并还原原有效果, 避免影响玩家自身的急迫/疲劳 (如信标、药水).
 *
 * <p>线程安全: sessions 使用 ConcurrentHashMap. refreshActivity 可能由事件 (主线程) 调用,
 * tick 任务也在主线程, 实际无竞争, 但 ConcurrentHashMap 保证遍历删除时的安全.
 */
public class MiningBuffManager {

    /** 急迫效果类型 (兼容 1.13 FAST_DIGGING 与 1.20.2+ HASTE 命名) */
    private static final PotionEffectType HASTE_TYPE = resolveType("FAST_DIGGING", "HASTE");
    /** 挖掘疲劳效果类型 (兼容 1.13 SLOW_DIGGING 与 1.20.2+ MINING_FATIGUE 命名) */
    private static final PotionEffectType FATIGUE_TYPE = resolveType("SLOW_DIGGING", "MINING_FATIGUE");

    /** Buff 单次施加时长 (tick). tick 任务会持续续命, 故只需覆盖刷新间隔的余量. */
    private static final int BUFF_DURATION_TICKS = 60;
    /** tick 任务执行间隔 (tick) */
    private static final int REFRESH_INTERVAL_TICKS = 4;
    /** 玩家多少 tick 无活动信号 (左键挥手/BlockDamage) 视为松开左键, 清除 buff. 略大于挥手间隔(~6t). */
    private static final int STALE_THRESHOLD_TICKS = 12;

    private final LonelyBlock plugin;
    private final Map<UUID, BuffSession> sessions = new ConcurrentHashMap<>();
    private BukkitTask tickTask;
    private int internalTick = 0;

    public MiningBuffManager(LonelyBlock plugin) {
        this.plugin = plugin;
    }

    /** 启动 tick 续命任务 */
    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 1L, REFRESH_INTERVAL_TICKS);
    }

    /** 关闭, 清除所有玩家的 buff */
    public void shutdown() {
        if (tickTask != null) {
            try {
                tickTask.cancel();
            } catch (Throwable ignored) {
            }
            tickTask = null;
        }
        for (UUID uuid : sessions.keySet().toArray(new UUID[0])) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                clearBuff(p);
            }
        }
        sessions.clear();
    }

    /**
     * 施加挖掘 buff. 保存玩家原有的同类效果, 然后施加我们的急迫/疲劳.
     *
     * @param player     玩家
     * @param block      目标方块
     * @param hasteAmp   急迫 amplifier (-1 不施加)
     * @param fatigueAmp 挖掘疲劳 amplifier (-1 不施加)
     */
    public void applyBuff(Player player, Block block, int hasteAmp, int fatigueAmp) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();

        // 若已存在会话且效果相同, 直接续命即可 (避免重复保存原有效果)
        BuffSession existing = sessions.get(uuid);
        if (existing != null && existing.hasteAmp == hasteAmp && existing.fatigueAmp == fatigueAmp) {
            existing.lastActivityTick = currentTick();
            refreshEffects(player, existing);
            return;
        }

        // 新会话或效果变更: 保存玩家当前同类效果 (仅保存我们即将施加的类型)
        PotionEffect naturalHaste = null;
        PotionEffect naturalFatigue = null;
        if (hasteAmp >= 0 && HASTE_TYPE != null) {
            naturalHaste = player.getPotionEffect(HASTE_TYPE);
        }
        if (fatigueAmp >= 0 && FATIGUE_TYPE != null) {
            naturalFatigue = player.getPotionEffect(FATIGUE_TYPE);
        }

        BuffSession session = new BuffSession(block, hasteAmp, fatigueAmp,
                naturalHaste, naturalFatigue, currentTick());
        sessions.put(uuid, session);
        refreshEffects(player, session);

        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[Buff] 施加 " + player.getName()
                    + " hasteAmp=" + hasteAmp + " fatigueAmp=" + fatigueAmp
                    + " 方块=" + locStr(block));
        }
    }

    /**
     * 刷新玩家活动信号 (仍按住左键挖掘). 由 BlockDamageEvent(配置方块) 与 PlayerAnimationEvent 调用.
     */
    public void refreshActivity(Player player) {
        if (player == null) {
            return;
        }
        BuffSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.lastActivityTick = currentTick();
        }
    }

    /**
     * 若玩家当前 buff 针对的方块与传入方块不同, 则清除 buff.
     * 用于玩家切换挖掘目标时立即移除旧 buff.
     *
     * @return true 表示已清除 (玩家之前有针对其它方块的 buff)
     */
    public boolean clearBuffIfDifferentBlock(Player player, Block block) {
        if (player == null || block == null) {
            return false;
        }
        BuffSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        if (sameBlock(session.block, block)) {
            return false;
        }
        clearBuff(player);
        return true;
    }

    /**
     * 判断玩家是否有针对指定方块的活跃 buff
     */
    public boolean hasBuffFor(Player player, Block block) {
        if (player == null || block == null) {
            return false;
        }
        BuffSession session = sessions.get(player.getUniqueId());
        return session != null && sameBlock(session.block, block);
    }

    /**
     * 清除玩家的 buff: 移除我们施加的效果, 还原原有同类效果.
     */
    public void clearBuff(Player player) {
        if (player == null) {
            return;
        }
        BuffSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        // 移除我们施加的急迫
        if (session.hasteAmp >= 0 && HASTE_TYPE != null) {
            PotionEffect current = player.getPotionEffect(HASTE_TYPE);
            // 仅当当前效果是我们的 (amplifier 匹配) 才移除, 避免误删玩家中途获得的其它效果
            if (current != null && current.getAmplifier() == session.hasteAmp) {
                player.removePotionEffect(HASTE_TYPE);
            }
            // 还原原有急迫
            if (session.naturalHaste != null) {
                player.addPotionEffect(session.naturalHaste, false);
            }
        }
        // 移除我们施加的疲劳
        if (session.fatigueAmp >= 0 && FATIGUE_TYPE != null) {
            PotionEffect current = player.getPotionEffect(FATIGUE_TYPE);
            if (current != null && current.getAmplifier() == session.fatigueAmp) {
                player.removePotionEffect(FATIGUE_TYPE);
            }
            if (session.naturalFatigue != null) {
                player.addPotionEffect(session.naturalFatigue, false);
            }
        }
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[Buff] 清除 " + player.getName() + " 的挖掘 buff");
        }
    }

    /**
     * tick 任务: 续命活跃会话, 清除过期会话
     */
    private void tick() {
        internalTick++;
        if (sessions.isEmpty()) {
            return;
        }
        int cur = currentTick();
        Iterator<Map.Entry<UUID, BuffSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BuffSession> e = it.next();
            UUID uuid = e.getKey();
            BuffSession s = e.getValue();

            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                it.remove();
                continue;
            }

            // 活动信号过期: 玩家已松开左键, 清除 buff
            if (cur - s.lastActivityTick > STALE_THRESHOLD_TICKS) {
                it.remove();
                removeOurEffects(player, s);
                if (LonelyBlock.isDebug()) {
                    plugin.getLogger().info("[Buff] " + player.getName()
                            + " 活动信号过期, 清除 buff (松开左键)");
                }
                continue;
            }

            // 仍在挖掘, 续命效果时长
            refreshEffects(player, s);
        }
    }

    /**
     * 重新施加我们的 buff 效果 (续命时长). 覆盖当前同类效果.
     */
    private void refreshEffects(Player player, BuffSession s) {
        if (s.hasteAmp >= 0 && HASTE_TYPE != null) {
            PotionEffect eff = createEffect(HASTE_TYPE, BUFF_DURATION_TICKS, s.hasteAmp);
            if (eff != null) {
                player.addPotionEffect(eff, false);
            }
        }
        if (s.fatigueAmp >= 0 && FATIGUE_TYPE != null) {
            PotionEffect eff = createEffect(FATIGUE_TYPE, BUFF_DURATION_TICKS, s.fatigueAmp);
            if (eff != null) {
                player.addPotionEffect(eff, false);
            }
        }
    }

    /**
     * 仅移除我们施加的效果并还原原有同类效果 (tick 过期分支调用, 不再走 clearBuff 的重复移除)
     */
    private void removeOurEffects(Player player, BuffSession s) {
        try {
            if (s.hasteAmp >= 0 && HASTE_TYPE != null) {
                PotionEffect current = player.getPotionEffect(HASTE_TYPE);
                if (current != null && current.getAmplifier() == s.hasteAmp) {
                    player.removePotionEffect(HASTE_TYPE);
                }
                if (s.naturalHaste != null) {
                    player.addPotionEffect(s.naturalHaste, false);
                }
            }
            if (s.fatigueAmp >= 0 && FATIGUE_TYPE != null) {
                PotionEffect current = player.getPotionEffect(FATIGUE_TYPE);
                if (current != null && current.getAmplifier() == s.fatigueAmp) {
                    player.removePotionEffect(FATIGUE_TYPE);
                }
                if (s.naturalFatigue != null) {
                    player.addPotionEffect(s.naturalFatigue, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int currentTick() {
        return internalTick;
    }

    private boolean sameBlock(Block a, Block b) {
        if (a == null || b == null) return false;
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ()
                && a.getWorld() != null && b.getWorld() != null
                && a.getWorld().equals(b.getWorld());
    }

    private String locStr(Block b) {
        if (b == null) return "null";
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /**
     * 兼容解析 PotionEffectType (新版本命名变更)
     */
    private static PotionEffectType resolveType(String... names) {
        for (String name : names) {
            try {
                PotionEffectType t = PotionEffectType.getByName(name);
                if (t != null) {
                    return t;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 创建 PotionEffect, 隐藏粒子与图标. 兼容多版本构造器:
     * 优先 6 参 (1.14+, 含 icon), 回退 5 参 (1.13, 含 particles), 再回退 4 参/3 参.
     */
    private static PotionEffect createEffect(PotionEffectType type, int duration, int amplifier) {
        if (type == null) {
            return null;
        }
        try {
            // 6 参: (type, duration, amplifier, ambient, particles, icon) - 1.14+
            Constructor<PotionEffect> c6 = findConstructor(6);
            if (c6 != null) {
                return c6.newInstance(type, duration, amplifier, false, false, false);
            }
            // 5 参: (type, duration, amplifier, ambient, particles) - 1.13
            Constructor<PotionEffect> c5 = findConstructor(5);
            if (c5 != null) {
                return c5.newInstance(type, duration, amplifier, false, false);
            }
            // 4 参: (type, duration, amplifier, ambient)
            Constructor<PotionEffect> c4 = findConstructor(4);
            if (c4 != null) {
                return c4.newInstance(type, duration, amplifier, false);
            }
            // 3 参
            return new PotionEffect(type, duration, amplifier);
        } catch (Throwable ignored) {
            try {
                return new PotionEffect(type, duration, amplifier);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static Constructor<PotionEffect> findConstructor(int paramCount) {
        try {
            for (Constructor<?> c : PotionEffect.class.getConstructors()) {
                if (c.getParameterTypes().length == paramCount) {
                    @SuppressWarnings("unchecked")
                    Constructor<PotionEffect> cast = (Constructor<PotionEffect>) c;
                    return cast;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 单个 buff 会话
     */
    private static class BuffSession {
        final Block block;
        final int hasteAmp;       // -1 表示不施加急迫
        final int fatigueAmp;     // -1 表示不施加疲劳
        final PotionEffect naturalHaste;    // 施加前玩家原有的急迫 (null 表示无/未保存)
        final PotionEffect naturalFatigue;  // 施加前玩家原有的挖掘疲劳
        volatile int lastActivityTick;      // 最近一次活动信号 tick

        BuffSession(Block block, int hasteAmp, int fatigueAmp,
                    PotionEffect naturalHaste, PotionEffect naturalFatigue, int initTick) {
            this.block = block;
            this.hasteAmp = hasteAmp;
            this.fatigueAmp = fatigueAmp;
            this.naturalHaste = naturalHaste;
            this.naturalFatigue = naturalFatigue;
            this.lastActivityTick = initTick;
        }
    }
}
