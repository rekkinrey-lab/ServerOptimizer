package dev.crystalopt;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CrystalOptimizer - lag/RAM optimizations for crystal PvP servers with Bedrock players.
 * Requires Paper (or a Paper fork) 1.20+. Not Folia compatible.
 */
public final class CrystalOptimizer extends JavaPlugin implements Listener, TabExecutor {

    private static final Set<SpawnReason> NATURAL_REASONS = EnumSet.of(
            SpawnReason.NATURAL, SpawnReason.CHUNK_GEN, SpawnReason.REINFORCEMENTS,
            SpawnReason.PATROL, SpawnReason.VILLAGE_INVASION);

    private static final Set<SpawnReason> EXEMPT_FROM_CAP = EnumSet.of(
            SpawnReason.CUSTOM, SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG);

    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Map<Long, Integer> explosionsThisTick = new HashMap<>();

    // config values
    private Set<String> worlds = new HashSet<>();
    private boolean noDrops, blockDamage;
    private int maxBlockExplosions, maxCrystals;
    private boolean cleanupEnabled;
    private int cleanupInterval, itemMaxAge, maxItemsPerChunk, orbMaxAge, arrowMaxAge;
    private boolean blockNaturalMobs;
    private int maxMobsPerChunk;
    private boolean distEnabled, dynamicEnabled;
    private int javaView, javaSim, bedrockView, bedrockSim;
    private double lowTps, highTps;
    private int maxReduction, minView, minSim, distInterval;
    private boolean unloadEnabled;
    private int unloadInterval, unloadMargin, unloadPerRun;

    private int viewReduction = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("crystalopt") != null) {
            getCommand("crystalopt").setExecutor(this);
            getCommand("crystalopt").setTabCompleter(this);
        }
        load();
    }

    @Override
    public void onDisable() {
        cancelTasks();
    }

    // ------------------------------------------------------------------ config

    private void load() {
        cancelTasks();
        reloadConfig();
        FileConfiguration c = getConfig();

        worlds = new HashSet<>();
        for (String w : c.getStringList("worlds")) worlds.add(w.toLowerCase());

        noDrops = c.getBoolean("explosions.no-drops", true);
        blockDamage = c.getBoolean("explosions.block-damage", true);
        maxBlockExplosions = c.getInt("explosions.max-block-explosions-per-chunk-per-tick", 3);
        maxCrystals = c.getInt("explosions.max-crystals-per-chunk", 40);

        cleanupEnabled = c.getBoolean("cleanup.enabled", true);
        cleanupInterval = Math.max(5, c.getInt("cleanup.interval-seconds", 20));
        itemMaxAge = c.getInt("cleanup.item-max-age-seconds", 150) * 20;
        maxItemsPerChunk = c.getInt("cleanup.max-items-per-chunk", 40);
        orbMaxAge = c.getInt("cleanup.xp-orb-max-age-seconds", 30) * 20;
        arrowMaxAge = c.getInt("cleanup.stuck-arrow-max-age-seconds", 20) * 20;

        blockNaturalMobs = c.getBoolean("mobs.block-natural-spawns", true);
        maxMobsPerChunk = c.getInt("mobs.max-per-chunk", 12);

        distEnabled = c.getBoolean("distances.enabled", true);
        javaView = clamp(c.getInt("distances.java.view", 8), 2, 32);
        javaSim = clamp(c.getInt("distances.java.simulation", 5), 2, 32);
        bedrockView = clamp(c.getInt("distances.bedrock.view", 6), 2, 32);
        bedrockSim = clamp(c.getInt("distances.bedrock.simulation", 4), 2, 32);
        dynamicEnabled = c.getBoolean("distances.dynamic.enabled", true);
        lowTps = c.getDouble("distances.dynamic.low-tps", 18.0);
        highTps = c.getDouble("distances.dynamic.high-tps", 19.6);
        maxReduction = Math.max(0, c.getInt("distances.dynamic.max-reduction", 3));
        minView = clamp(c.getInt("distances.dynamic.min-view", 4), 2, 32);
        minSim = clamp(c.getInt("distances.dynamic.min-simulation", 3), 2, 32);
        distInterval = Math.max(1, c.getInt("distances.check-interval-seconds", 5));

        unloadEnabled = c.getBoolean("chunks.unload-enabled", true);
        unloadInterval = Math.max(10, c.getInt("chunks.interval-seconds", 60));
        unloadMargin = Math.max(0, c.getInt("chunks.margin", 3));
        unloadPerRun = Math.max(1, c.getInt("chunks.max-unloads-per-run", 100));

        viewReduction = 0;

        // The explosion counter resets every tick.
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!explosionsThisTick.isEmpty()) explosionsThisTick.clear();
        }, 1L, 1L));

        if (cleanupEnabled) {
            long p = cleanupInterval * 20L;
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::cleanupPass, p, p));
        }
        if (distEnabled) {
            long p = distInterval * 20L;
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::distancePass, 40L, p));
        }
        if (unloadEnabled) {
            long p = unloadInterval * 20L;
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::unloadPass, p, p));
        }
    }

    private void cancelTasks() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private boolean enabledIn(World w) {
        return worlds.isEmpty() || worlds.contains(w.getName().toLowerCase());
    }

    private static boolean isCrystal(Entity e) {
        // Name check keeps this working across the ENDER_CRYSTAL -> END_CRYSTAL rename.
        return e.getType().name().endsWith("CRYSTAL");
    }

    /** Floodgate gives Bedrock players UUIDs with a most-significant-bits value of 0. */
    private static boolean isBedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Entity ent = e.getEntity();
        if (!isCrystal(ent) || !enabledIn(ent.getWorld())) return;

        if (noDrops) e.setYield(0f);

        if (!blockDamage) {
            e.blockList().clear();
            return;
        }

        if (maxBlockExplosions > 0) {
            int cx = ent.getLocation().getBlockX() >> 4;
            int cz = ent.getLocation().getBlockZ() >> 4;
            int count = explosionsThisTick.merge(chunkKey(cx, cz), 1, Integer::sum);
            // Don't cancel the event: that would also cancel player damage.
            if (count > maxBlockExplosions) e.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        if (maxCrystals <= 0) return;
        Entity ent = e.getEntity();
        if (!isCrystal(ent) || !enabledIn(ent.getWorld())) return;

        int count = 0;
        for (Entity other : ent.getLocation().getChunk().getEntities()) {
            if (other != ent && isCrystal(other)) count++;
        }
        if (count >= maxCrystals) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        LivingEntity mob = e.getEntity();
        if (!enabledIn(mob.getWorld())) return;
        SpawnReason reason = e.getSpawnReason();

        if (blockNaturalMobs && NATURAL_REASONS.contains(reason)) {
            e.setCancelled(true);
            return;
        }

        if (maxMobsPerChunk > 0 && !EXEMPT_FROM_CAP.contains(reason)) {
            int count = 0;
            for (Entity other : mob.getLocation().getChunk().getEntities()) {
                if (other instanceof LivingEntity && !(other instanceof Player)) count++;
            }
            if (count >= maxMobsPerChunk) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!distEnabled) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) applyDistances(p);
        }, 20L);
    }

    // ------------------------------------------------------------------ periodic passes

    private void cleanupPass() {
        for (World w : Bukkit.getWorlds()) {
            if (!enabledIn(w)) continue;
            Map<Long, List<Item>> itemsByChunk = new HashMap<>();

            for (Entity ent : w.getEntities()) {
                if (ent instanceof Item it) {
                    if (itemMaxAge > 0 && it.getTicksLived() > itemMaxAge) {
                        it.remove();
                    } else if (maxItemsPerChunk > 0) {
                        long key = chunkKey(it.getLocation().getBlockX() >> 4,
                                it.getLocation().getBlockZ() >> 4);
                        itemsByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
                    }
                } else if (ent instanceof ExperienceOrb orb) {
                    if (orbMaxAge > 0 && orb.getTicksLived() > orbMaxAge) orb.remove();
                } else if (ent instanceof AbstractArrow arrow) {
                    if (arrowMaxAge > 0 && arrow.isInBlock() && arrow.getTicksLived() > arrowMaxAge) {
                        arrow.remove();
                    }
                }
            }

            if (maxItemsPerChunk > 0) {
                for (List<Item> list : itemsByChunk.values()) {
                    if (list.size() <= maxItemsPerChunk) continue;
                    // oldest first
                    list.sort(Comparator.comparingInt(Item::getTicksLived).reversed());
                    int excess = list.size() - maxItemsPerChunk;
                    for (int i = 0; i < excess; i++) list.get(i).remove();
                }
            }
        }
    }

    private void distancePass() {
        if (dynamicEnabled) {
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            if (tps < lowTps && viewReduction < maxReduction) viewReduction++;
            else if (tps > highTps && viewReduction > 0) viewReduction--;
        } else {
            viewReduction = 0;
        }
        for (Player p : Bukkit.getOnlinePlayers()) applyDistances(p);
    }

    private void applyDistances(Player p) {
        if (!enabledIn(p.getWorld())) return;
        boolean bedrock = isBedrock(p);
        int baseView = bedrock ? bedrockView : javaView;
        int baseSim = bedrock ? bedrockSim : javaSim;

        int view = Math.min(baseView, Math.max(minView, baseView - viewReduction));
        int sim = Math.min(baseSim, Math.max(minSim, baseSim - viewReduction));
        view = clamp(view, 2, 32);
        sim = clamp(sim, 2, 32);

        if (p.getViewDistance() != view) p.setViewDistance(view);
        if (p.getSendViewDistance() != view) p.setSendViewDistance(view);
        if (p.getSimulationDistance() != sim) p.setSimulationDistance(sim);
    }

    private void unloadPass() {
        int budget = unloadPerRun;
        for (World w : Bukkit.getWorlds()) {
            if (!enabledIn(w) || budget <= 0) continue;

            Set<Long> keep = new HashSet<>();
            for (Player p : w.getPlayers()) {
                int cx = p.getLocation().getBlockX() >> 4;
                int cz = p.getLocation().getBlockZ() >> 4;
                int r = Math.max(p.getViewDistance(), p.getSimulationDistance()) + unloadMargin;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) keep.add(chunkKey(cx + dx, cz + dz));
                }
            }

            for (Chunk c : w.getLoadedChunks()) {
                if (budget <= 0) break;
                if (c.isForceLoaded()) continue;
                if (keep.contains(chunkKey(c.getX(), c.getZ()))) continue;
                // Request only; chunks held by plugin tickets stay loaded.
                if (w.unloadChunkRequest(c.getX(), c.getZ())) budget--;
            }
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                load();
                s.sendMessage(ChatColor.GREEN + "CrystalOptimizer reloaded.");
            }
            case "clear" -> {
                int removed = 0;
                for (World w : Bukkit.getWorlds()) {
                    if (!enabledIn(w)) continue;
                    for (Entity ent : w.getEntities()) {
                        if (ent instanceof Item || ent instanceof ExperienceOrb) {
                            ent.remove();
                            removed++;
                        } else if (ent instanceof AbstractArrow a && a.isInBlock()) {
                            a.remove();
                            removed++;
                        }
                    }
                }
                s.sendMessage(ChatColor.GREEN + "Removed " + removed + " items/orbs/arrows.");
            }
            default -> status(s);
        }
        return true;
    }

    private void status(CommandSender s) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long max = rt.maxMemory() >> 20;
        double[] tps = Bukkit.getTPS();

        int java = 0, bedrock = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isBedrock(p)) bedrock++;
            else java++;
        }

        s.sendMessage(ChatColor.AQUA + "--- CrystalOptimizer ---");
        s.sendMessage(String.format("TPS (1m/5m/15m): %.2f / %.2f / %.2f", tps[0], tps[1], tps[2]));
        s.sendMessage("RAM: " + used + " / " + max + " MB");
        s.sendMessage("Players: " + java + " Java, " + bedrock + " Bedrock");
        s.sendMessage("View distance reduction active: " + viewReduction + " chunk(s)");
        for (World w : Bukkit.getWorlds()) {
            s.sendMessage(ChatColor.GRAY + w.getName() + ": " + w.getLoadedChunks().length
                    + " chunks, " + w.getEntities().size() + " entities");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return List.of("status", "reload", "clear");
        return List.of();
    }
}
