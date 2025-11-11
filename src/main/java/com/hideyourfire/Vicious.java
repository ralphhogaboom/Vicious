package com.hideyourfire;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Vicious extends JavaPlugin implements Listener {

    private Set<EntityType> allowedMobs;
    private long cooldownMillis;
    private final Map<UUID, Long> mobCooldowns = new HashMap<>();
    private double aggressionRadius;
    private double followRange;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Vicous enabled for Paper 1.21+");
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        cooldownMillis = config.getLong("cooldown_millis", 10000L); // default 10s
        allowedMobs = new HashSet<>();
        aggressionRadius = config.getDouble("aggression_radius", 10.0); // default 10 blocks
        followRange = config.getDouble("follow_range", 64.0);
        debug = config.getBoolean("debug", false);
        for (String name : config.getStringList("allowed_mobs")) {
            try {
                EntityType type = EntityType.valueOf(name.toUpperCase());
                if (type.isAlive() && type.isSpawnable()) {
                    allowedMobs.add(type);
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("Unknown mob type in config: " + name);
            }
        }
        getLogger().info("Allowed mobs: " + allowedMobs);
    }


    private void boostFollowRange(Mob mob, double newRange) {
        AttributeInstance followRange = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (followRange != null && followRange.getBaseValue() < newRange) {
            followRange.setBaseValue(newRange);
        }
    }

    @EventHandler
    public void onEntityTargetPlayer(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof Player player)) return;
        if (!(event.getEntity() instanceof Monster mob)) return;
        if (!allowedMobs.contains(mob.getType())) return;

        // prevent repeated chain reactions
        if (isOnCooldown(mob.getUniqueId())) return;
        setCooldown(mob.getUniqueId());
        if (debug) {
            getLogger().info("Mob " + mob.getType() + " targeted player " + player.getName() + ", alerting nearby hostiles.");
        }
        alertNearbyHostiles(mob.getWorld(), mob.getLocation(), player.getLocation());
        if (debug) {
            getLogger().info("Alerted nearby hostiles around mob " + mob.getType());
        }
    }

    private void alertNearbyHostiles(World world, Location centerLoc, Location targetLoc) {
        int chunkX = centerLoc.getChunk().getX();
        int chunkZ = centerLoc.getChunk().getZ();

        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                Chunk chunk = world.getChunkAt(x, z);
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Monster nearbyMob
                            && allowedMobs.contains(nearbyMob.getType())
                            && nearbyMob.getTarget() == null
                            && !isOnCooldown(nearbyMob.getUniqueId())) {

                        setCooldown(nearbyMob.getUniqueId());
                        boostFollowRange(nearbyMob, followRange);
                        sendMobToLocation(nearbyMob, targetLoc);
                        if (debug) {
                            getLogger().info("Sent mob " + nearbyMob.getType() + " to location " + targetLoc);
                        }
                        monitorMobForPlayers(nearbyMob);
                    }
                }
            }
        }
    }

    private void sendMobToLocation(Monster mob, Location destination) {
        try {
            mob.getPathfinder().moveTo(destination);
        } catch (Exception e) {
            getLogger().warning("Failed to move mob " + mob.getType() + ": " + e.getMessage());
        }
    }

    private void monitorMobForPlayers(Mob mob) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!mob.isValid() || mob.getTarget() != null) {
                    cancel();
                    return;
                }

                for (Entity nearby : mob.getNearbyEntities(aggressionRadius, aggressionRadius / 2, aggressionRadius)) {
                    if (nearby instanceof Player player) {
                        double distance = mob.getLocation().distanceSquared(player.getLocation());
                        double maxDistSq = aggressionRadius * aggressionRadius;

                        // If the mob can see the player OR is within aggression radius, target them
                        if (mob.hasLineOfSight(player) || distance <= maxDistSq) {
                            mob.setTarget(player);
                            if (debug) {
                                getLogger().info("Mob " + mob.getType() + " has spotted player " + player.getName() + " at distance " + Math.sqrt(distance));
                            }
                            cancel();
                            return;
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L); // check every second
    }

    private boolean isOnCooldown(UUID mobId) {
        Long lastTime = mobCooldowns.get(mobId);
        return lastTime != null && (System.currentTimeMillis() - lastTime < cooldownMillis);
    }

    private void setCooldown(UUID mobId) {
        mobCooldowns.put(mobId, System.currentTimeMillis());
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadConfigValues();
    }
}