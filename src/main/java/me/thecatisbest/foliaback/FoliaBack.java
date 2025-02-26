package me.thecatisbest.foliaback;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FoliaBack extends JavaPlugin implements Listener {

    // 单 tick 内认为“瞬间跳跃”的距离阈值（可根据需要调整）
    private static final double TELEPORT_THRESHOLD = 5.0;
    private static final long MONITORING_DURATION = 100L;
    // 如果玩家在此毫秒数内投掷过珍珠，则认为是珍珠传送，取消监控（可根据需要调整）
    private static final long PEARL_IGNORE_THRESHOLD_MS = 10000;

    private final Map<UUID, ScheduledTask> monitoringTasks = new HashMap<>();
    private final Map<UUID, Location> lastTeleportLocation = new HashMap<>();
    // 存储玩家上次投掷珍珠的时间
    private final Map<UUID, Long> lastPearlThrowTime = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        getCommand("foliaback").setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof Player player) {
                Location lastLoc = getLastTeleportLocation(player);

                if (lastLoc == null) {
                    player.sendMessage("§c你沒有最近的傳送記錄！");
                    return true;
                }

                player.teleportAsync(lastLoc);
                player.sendMessage("§f已傳送回上一個位置！");
                return true;
            }
            return false;
        });
    }

    private void cancelMonitoring(UUID uuid) {
        if (monitoringTasks.containsKey(uuid)) {
            monitoringTasks.get(uuid).cancel();
            monitoringTasks.remove(uuid);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        cancelMonitoring(uuid);

        Location initialLocation = player.getLocation().clone();
        MonitoringData data = new MonitoringData(initialLocation, player.getFallDistance());

        ScheduledTask task = getServer().getGlobalRegionScheduler().runAtFixedRate(this, (ScheduledTask scheduledTask) -> {
            data.ticks++;

            // 检查玩家是否在短时间内投掷了终界珍珠
            Long pearlTime = lastPearlThrowTime.get(uuid);
            if (pearlTime != null && System.currentTimeMillis() - pearlTime < PEARL_IGNORE_THRESHOLD_MS) {
                scheduledTask.cancel();
                monitoringTasks.remove(uuid);
                return;
            }

            if (player.isGliding() || player.isFlying()) {
                scheduledTask.cancel();
                monitoringTasks.remove(uuid);
                return;
            }

            Location currentLocation = player.getLocation();
            double distance = currentLocation.distance(data.previousLocation);

            if (distance >= TELEPORT_THRESHOLD) {
                // 计算水平位移
                double deltaX = currentLocation.getX() - data.previousLocation.getX();
                double deltaZ = currentLocation.getZ() - data.previousLocation.getZ();
                double horizontal = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

                // 如果水平位移明显，则判断为传送
                if (horizontal >= 1.0) {
                    lastTeleportLocation.put(uuid, initialLocation);
                    scheduledTask.cancel();
                    monitoringTasks.remove(uuid);
                    return;
                } else {
                    // 如果水平位移不明显，则可能是纯垂直变化（正常下落）
                    // 正常下落时，fallDistance 应该持续增加；
                    // 如果突然下降（例如从较大落距降为较小值）且玩家未着地，则可能是传送（垂直传送）
                    double currentFall = player.getFallDistance();
                    if (currentFall < data.previousFallDistance && data.previousFallDistance >= 1.0 && !player.isOnGround()) {
                        lastTeleportLocation.put(uuid, initialLocation);
                        scheduledTask.cancel();
                        monitoringTasks.remove(uuid);
                        return;
                    }
                }
            }

            data.previousLocation = currentLocation;
            data.previousFallDistance = player.getFallDistance();

            if (data.ticks >= MONITORING_DURATION) {
                scheduledTask.cancel();
                monitoringTasks.remove(uuid);
            }
        }, 2L, 1L);
        monitoringTasks.put(uuid, task);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location initialLocation = player.getLocation().clone();
        lastTeleportLocation.put(uuid, initialLocation);
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            if (event.getEntity().getShooter() instanceof Player player) {
                UUID uuid = player.getUniqueId();
                lastPearlThrowTime.put(uuid, System.currentTimeMillis());
                cancelMonitoring(uuid);
            }
        }
    }

    @EventHandler
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.CHORUS_FRUIT) {
            cancelMonitoring(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (event.isGliding()) {
                cancelMonitoring(player.getUniqueId());
            }
        }
    }

    public Location getLastTeleportLocation(Player player) {
        return lastTeleportLocation.get(player.getUniqueId());
    }

    /**
     * 辅助类，用于在监控任务中保存上一次位置和已运行的 tick 数
     */
    private static class MonitoringData {
        long ticks = 0;
        Location previousLocation;
        double previousFallDistance;

        MonitoringData(Location initialLocation, double initialFallDistance) {
            this.previousLocation = initialLocation;
            this.previousFallDistance = initialFallDistance;
        }
    }
}
