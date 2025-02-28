package me.thecatisbest.foliaback;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public class FoliaBack extends JavaPlugin implements Listener {

    // 記錄上一次的位置（非落體時的 tick 位置）
    private final ConcurrentHashMap<UUID, Location> lastTickLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> backLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportFlags = new ConcurrentHashMap<>();
    private static final long TELEPORT_IGNORE_THRESHOLD = 10000L;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("foliaback").setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof Player player) {
                UUID uuid = player.getUniqueId();
                Location lastLocation = backLocations.get(uuid);
                if (lastLocation == null) {
                    player.sendMessage("§c你沒有最近的傳送記錄！");
                    return true;
                }
                player.teleportAsync(lastLocation);
                player.sendMessage("§f已傳送回上一個位置！");
                return true;
            }
            return false;
        });

        // 每 50 毫秒（約 1 tick）執行一次偵測任務
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            long currentTime = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                Location currentLocation = player.getLocation();

                if (player.isGliding() || hasMovementAffectingPotion(player) || teleportRecently(uuid, currentTime)
                        || player.isFlying() || player.getGameMode() == GameMode.SPECTATOR || player.getVehicle() != null) {
                    // 更新記錄（因這些情況不參與偵測）
                    lastTickLocations.put(uuid, currentLocation);
                    continue;
                }

                // 如果之前有記錄，則偵測本 tick 與上一次記錄的位移
                if (lastTickLocations.containsKey(uuid)) {
                    Location previous = lastTickLocations.get(uuid);
                    if (previous != null && previous.getWorld() != null && currentLocation.getWorld() != null
                            && previous.getWorld().equals(currentLocation.getWorld())) {
                        double tickDistance = previous.distance(currentLocation);
                        if (tickDistance > 5) {
                            double dx = currentLocation.getX() - previous.getX();
                            double dz = currentLocation.getZ() - previous.getZ();
                            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
                            double verticalDifference = Math.abs(currentLocation.getY() - previous.getY());
                            // 如果主要是垂直位移（水平位移很小），視為高空落下，此時不更新記錄
                            if (horizontalDistance < 1.0 && verticalDifference > 5) {
                                lastTickLocations.put(uuid, currentLocation);
                                continue;
                            } else {
                                // 非落體的異常移動，記錄上一次的位置供傳送使用
                                backLocations.put(uuid, previous);
                                player.sendMessage("§b已記錄傳送點");
                                player.sendMessage("X: " + previous.getX() + ", Y: " + previous.getY() + ", Z: " + previous.getZ());
                            }
                        }
                    }
                }
                // 更新記錄（僅在非落體情況更新）
                lastTickLocations.put(uuid, currentLocation);
            }
        }, 50L, 50L, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onDisable() {
        lastTickLocations.clear();
        backLocations.clear();
        teleportFlags.clear();
    }

    private boolean hasMovementAffectingPotion(Player player) {
        return player.hasPotionEffect(PotionEffectType.SPEED) || player.hasPotionEffect(PotionEffectType.LEVITATION);
    }

    // 判斷玩家是否在近期因傳送而觸發大幅位移
    private boolean teleportRecently(UUID uuid, long currentTime) {
        if (teleportFlags.containsKey(uuid)) {
            long teleportTime = teleportFlags.get(uuid);
            if (currentTime - teleportTime < TELEPORT_IGNORE_THRESHOLD) {
                return true;
            } else {
                teleportFlags.remove(uuid);
            }
        }
        return false;
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            Object shooter = event.getEntity().getShooter();
            if (shooter instanceof Player player) {
                teleportFlags.put(player.getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.CHORUS_FRUIT) {
            Player player = event.getPlayer();
            teleportFlags.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }
}
