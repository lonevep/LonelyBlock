package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MiningBuffManager {

    private static final PotionEffectType HASTE_TYPE = resolveType("FAST_DIGGING", "HASTE");
    private static final PotionEffectType FATIGUE_TYPE = resolveType("SLOW_DIGGING", "MINING_FATIGUE");

    private static final int BUFF_DURATION_TICKS = 60;
    private static final int REFRESH_INTERVAL_TICKS = 4;
    private static final int STALE_THRESHOLD_TICKS = 12;

    private static final Constructor<PotionEffect> EFFECT_CONSTRUCTOR = resolveEffectConstructor();
    private static final int EFFECT_CONSTRUCTOR_ARGS = EFFECT_CONSTRUCTOR != null
            ? EFFECT_CONSTRUCTOR.getParameterTypes().length : 0;
    private static final Method POTION_EFFECT_LOOKUP = resolvePotionEffectLookup();

    private final LonelyBlock plugin;
    private final Map<UUID, BuffSession> sessions = new ConcurrentHashMap<>();
    private BukkitTask tickTask;
    private int internalTick = 0;

    public MiningBuffManager(LonelyBlock plugin) {
        this.plugin = plugin;
    }

    public boolean hasActiveSessions() {
        return !sessions.isEmpty();
    }

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

    public void applyBuff(Player player, Block block, int hasteAmp, int fatigueAmp) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();

        BuffSession existing = sessions.get(uuid);
        if (existing != null && existing.hasteAmp == hasteAmp && existing.fatigueAmp == fatigueAmp) {
            existing.lastActivityTick = currentTick();
            refreshEffects(player, existing);
            return;
        }

        PotionEffect naturalHaste = null;
        PotionEffect naturalFatigue = null;
        if (hasteAmp >= 0 && HASTE_TYPE != null) {
            naturalHaste = getActiveEffect(player, HASTE_TYPE);
        }
        if (fatigueAmp >= 0 && FATIGUE_TYPE != null) {
            naturalFatigue = getActiveEffect(player, FATIGUE_TYPE);
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

    public void refreshActivity(Player player) {
        if (player == null) {
            return;
        }
        BuffSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.lastActivityTick = currentTick();
        }
    }

    public boolean refreshIfSameBlock(Player player, Block block) {
        if (player == null || block == null) {
            return false;
        }
        BuffSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        if (sameBlock(session.block, block)) {
            session.lastActivityTick = currentTick();
            return true;
        }
        clearBuff(player);
        return false;
    }

    public void clearBuff(Player player) {
        if (player == null) {
            return;
        }
        BuffSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.hasteAmp >= 0 && HASTE_TYPE != null) {
            PotionEffect current = getActiveEffect(player, HASTE_TYPE);
            if (current != null && current.getAmplifier() == session.hasteAmp) {
                player.removePotionEffect(HASTE_TYPE);
            }
            if (session.naturalHaste != null) {
                player.addPotionEffect(session.naturalHaste);
            }
        }
        if (session.fatigueAmp >= 0 && FATIGUE_TYPE != null) {
            PotionEffect current = getActiveEffect(player, FATIGUE_TYPE);
            if (current != null && current.getAmplifier() == session.fatigueAmp) {
                player.removePotionEffect(FATIGUE_TYPE);
            }
            if (session.naturalFatigue != null) {
                player.addPotionEffect(session.naturalFatigue);
            }
        }
        if (LonelyBlock.isDebug()) {
            plugin.getLogger().info("[Buff] 清除 " + player.getName() + " 的挖掘 buff");
        }
    }

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

            if (cur - s.lastActivityTick > STALE_THRESHOLD_TICKS) {
                it.remove();
                removeOurEffects(player, s);
                if (LonelyBlock.isDebug()) {
                    plugin.getLogger().info("[Buff] " + player.getName()
                            + " 活动信号过期, 清除 buff (松开左键)");
                }
                continue;
            }

            refreshEffects(player, s);
        }
    }

    private void refreshEffects(Player player, BuffSession s) {
        if (s.hasteAmp >= 0 && HASTE_TYPE != null) {
            PotionEffect eff = createEffect(HASTE_TYPE, BUFF_DURATION_TICKS, s.hasteAmp);
            if (eff != null) {
                try {
                    player.addPotionEffect(eff);
                } catch (Throwable ignored) {
                }
            }
        }
        if (s.fatigueAmp >= 0 && FATIGUE_TYPE != null) {
            PotionEffect eff = createEffect(FATIGUE_TYPE, BUFF_DURATION_TICKS, s.fatigueAmp);
            if (eff != null) {
                try {
                    player.addPotionEffect(eff);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void removeOurEffects(Player player, BuffSession s) {
        try {
            if (s.hasteAmp >= 0 && HASTE_TYPE != null) {
                PotionEffect current = getActiveEffect(player, HASTE_TYPE);
                if (current != null && current.getAmplifier() == s.hasteAmp) {
                    player.removePotionEffect(HASTE_TYPE);
                }
                if (s.naturalHaste != null) {
                    player.addPotionEffect(s.naturalHaste);
                }
            }
            if (s.fatigueAmp >= 0 && FATIGUE_TYPE != null) {
                PotionEffect current = getActiveEffect(player, FATIGUE_TYPE);
                if (current != null && current.getAmplifier() == s.fatigueAmp) {
                    player.removePotionEffect(FATIGUE_TYPE);
                }
                if (s.naturalFatigue != null) {
                    player.addPotionEffect(s.naturalFatigue);
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

    private static Method resolvePotionEffectLookup() {
        try {
            return LivingEntity.class.getMethod("getPotionEffect", PotionEffectType.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static PotionEffect getActiveEffect(Player player, PotionEffectType type) {
        if (POTION_EFFECT_LOOKUP != null) {
            try {
                return (PotionEffect) POTION_EFFECT_LOOKUP.invoke(player, type);
            } catch (Throwable ignored) {
            }
        }
        try {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                if (effect.getType() != null && effect.getType().equals(type)) {
                    return effect;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static PotionEffect createEffect(PotionEffectType type, int duration, int amplifier) {
        if (type == null) {
            return null;
        }
        try {
            switch (EFFECT_CONSTRUCTOR_ARGS) {
                case 6:
                    return EFFECT_CONSTRUCTOR.newInstance(type, duration, amplifier, false, false, false);
                case 5:
                    return EFFECT_CONSTRUCTOR.newInstance(type, duration, amplifier, false, false);
                case 4:
                    return EFFECT_CONSTRUCTOR.newInstance(type, duration, amplifier, false);
                case 3:
                    return EFFECT_CONSTRUCTOR.newInstance(type, duration, amplifier);
                default:
                    return new PotionEffect(type, duration, amplifier);
            }
        } catch (Throwable ignored) {
            try {
                return new PotionEffect(type, duration, amplifier);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Constructor<PotionEffect> resolveEffectConstructor() {
        try {
            for (int wanted : new int[]{6, 5, 4, 3}) {
                for (Constructor<?> c : PotionEffect.class.getConstructors()) {
                    if (c.getParameterTypes().length == wanted) {
                        return (Constructor<PotionEffect>) c;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static class BuffSession {
        final Block block;
        final int hasteAmp;
        final int fatigueAmp;
        final PotionEffect naturalHaste;
        final PotionEffect naturalFatigue;
        volatile int lastActivityTick;

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
