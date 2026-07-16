package mrresi.cloudac.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import mrresi.cloudac.CloudAC;
import mrresi.cloudac.ml.PlayerDataCollector;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InfoTagManager {

    private final CloudAC plugin;
    private final Map<UUID, Map<UUID, ViewerHologram>> activeHolograms = new ConcurrentHashMap<>();
    private final Set<UUID> enabledViewers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger entityIdSeq = new AtomicInteger(2000000000);
    private org.bukkit.scheduler.BukkitTask teleportTask;

    public InfoTagManager(CloudAC plugin) {
        this.plugin = plugin;
    }

    public static class ViewerHologram {
        private final int topId;
        private final int bottomId;
        private Location lastLocation;

        public ViewerHologram(int topId, int bottomId, Location lastLocation) {
            this.topId = topId;
            this.bottomId = bottomId;
            this.lastLocation = lastLocation;
        }

        public int getTopId() {
            return topId;
        }

        public int getBottomId() {
            return bottomId;
        }

        public Location getLastLocation() {
            return lastLocation;
        }

        public void setLastLocation(Location lastLocation) {
            this.lastLocation = lastLocation;
        }
    }

    public boolean togglePlayerInfo(Player player) {
        UUID uuid = player.getUniqueId();
        if (enabledViewers.contains(uuid)) {
            removeViewer(player);
            return false;
        } else {
            enabledViewers.add(uuid);
            for (Player p : Bukkit.getOnlinePlayers()) {
                spawnHologram(player, p);
            }
            startTask();
            return true;
        }
    }

    private void removeViewer(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        enabledViewers.remove(viewerId);
        Map<UUID, ViewerHologram> targets = activeHolograms.remove(viewerId);
        if (targets != null) {
            for (ViewerHologram holo : targets.values()) {
                destroyHologram(viewer, holo);
            }
        }
        if (enabledViewers.isEmpty()) {
            stopTask();
        }
    }

    private void spawnHologram(Player viewer, Player target) {
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (viewerId.equals(targetId)) return;
        if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;

        Map<UUID, ViewerHologram> viewerMap = activeHolograms.computeIfAbsent(viewerId, k -> new ConcurrentHashMap<>());
        if (viewerMap.containsKey(targetId)) return;

        Location targetLoc = target.getLocation();
        int bottomId = entityIdSeq.incrementAndGet();
        int topId = entityIdSeq.incrementAndGet();

        ViewerHologram hologram = new ViewerHologram(topId, bottomId, targetLoc.clone());
        viewerMap.put(targetId, hologram);

        sendSpawnPacket(viewer, bottomId, targetLoc.clone().add(0, 2.4, 0));
        sendSpawnPacket(viewer, topId, targetLoc.clone().add(0, 2.65, 0));

        updateHologramTextForViewer(viewer, target, hologram);
    }

    private void sendSpawnPacket(Player viewer, int entityId, Location loc) {
        WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
            entityId,
            UUID.randomUUID(),
            EntityTypes.ARMOR_STAND,
            new com.github.retrooper.packetevents.protocol.world.Location(loc.getX(), loc.getY(), loc.getZ(), 0f, 0f),
            0f,
            0,
            new Vector3d(0, 0, 0)
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnPacket);
    }

    private void updateHologramTextForViewer(Player viewer, Player target, ViewerHologram hologram) {
        UUID targetId = target.getUniqueId();
        String topText = getTopText(targetId);
        String bottomText = getBottomText(targetId);

        sendMetadataPacket(viewer, hologram.getTopId(), topText);
        sendMetadataPacket(viewer, hologram.getBottomId(), bottomText);
    }

    private String getTopText(UUID targetId) {
        double f1 = 0.0, f2 = 0.0, f3 = 0.0, f4 = 0.0, f5 = 0.0, f6 = 0.0;
        PlayerDataCollector.PlayerData data = plugin.getAI().getDataCollector().getPlayerData(targetId);
        if (data != null) {
            f1 = Math.max(0.0, Math.min(1.0, data.getJitter() / 8.0));
            f2 = Math.max(0.0, Math.min(1.0, data.getYawDelta() / 30.0));
            f3 = Math.max(0.0, Math.min(1.0, data.getPitchDelta() / 20.0));
            f4 = Math.max(0.0, Math.min(1.0, data.getAngleToTarget() / 15.0));
            f5 = Math.max(0.0, Math.min(1.0, data.getYawAcceleration() / 45.0));
            f6 = Math.max(0.0, Math.min(1.0, data.getLastAttackInterval() / 500.0));
        }
        return
            getColorForScore(f1) + String.format(Locale.US, "%.3f", f1).replace('.', ',') + " " +
            getColorForScore(f2) + String.format(Locale.US, "%.3f", f2).replace('.', ',') + " " +
            getColorForScore(f3) + String.format(Locale.US, "%.3f", f3).replace('.', ',') + " " +
            getColorForScore(f4) + String.format(Locale.US, "%.3f", f4).replace('.', ',') + " " +
            getColorForScore(f5) + String.format(Locale.US, "%.3f", f5).replace('.', ',') + " " +
            getColorForScore(f6) + String.format(Locale.US, "%.3f", f6).replace('.', ',');
    }

    private String getBottomText(UUID targetId) {
        double f1 = 0.0, f2 = 0.0, f3 = 0.0, f4 = 0.0, f5 = 0.0, f6 = 0.0;
        PlayerDataCollector.PlayerData data = plugin.getAI().getDataCollector().getPlayerData(targetId);
        if (data != null) {
            f1 = Math.max(0.0, Math.min(1.0, data.getJitter() / 8.0));
            f2 = Math.max(0.0, Math.min(1.0, data.getYawDelta() / 30.0));
            f3 = Math.max(0.0, Math.min(1.0, data.getPitchDelta() / 20.0));
            f4 = Math.max(0.0, Math.min(1.0, data.getAngleToTarget() / 15.0));
            f5 = Math.max(0.0, Math.min(1.0, data.getYawAcceleration() / 45.0));
            f6 = Math.max(0.0, Math.min(1.0, data.getLastAttackInterval() / 500.0));
        }
        double avg = (f1 + f2 + f3 + f4 + f5 + f6) / 6.0;
        int ping = plugin.getPacketListener().getPlayerPing(targetId);
        String pingColor = getColorForPing(ping);
        return getColorForScore(avg) + "AVG: " + String.format(Locale.US, "%.3f", avg).replace('.', ',') + " " + pingColor + ping + "ms";
    }

    private void sendMetadataPacket(Player viewer, int entityId, String text) {
        List<EntityData<?>> metadata = new ArrayList<>();
        metadata.add(new EntityData(0, EntityDataTypes.BYTE, (byte) 0x20));
        Component component = LegacyComponentSerializer.legacySection().deserialize(text);
        String json = GsonComponentSerializer.gson().serialize(component);
        metadata.add(new EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(json)));
        metadata.add(new EntityData(3, EntityDataTypes.BOOLEAN, true));
        metadata.add(new EntityData(15, EntityDataTypes.BYTE, (byte) 0x19));

        WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(entityId, metadata);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
    }

    private void destroyHologram(Player viewer, ViewerHologram hologram) {
        WrapperPlayServerDestroyEntities destroyPacket = new WrapperPlayServerDestroyEntities(hologram.getTopId(), hologram.getBottomId());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroyPacket);
    }

    private void destroyHologram(Player viewer, UUID targetId) {
        UUID viewerId = viewer.getUniqueId();
        Map<UUID, ViewerHologram> viewerMap = activeHolograms.get(viewerId);
        if (viewerMap != null) {
            ViewerHologram hologram = viewerMap.remove(targetId);
            if (hologram != null) {
                destroyHologram(viewer, hologram);
            }
        }
    }

    private void updateHologramsForTarget(Player target) {
        UUID targetId = target.getUniqueId();
        for (UUID viewerId : enabledViewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                Map<UUID, ViewerHologram> viewerMap = activeHolograms.get(viewerId);
                if (viewerMap != null) {
                    ViewerHologram hologram = viewerMap.get(targetId);
                    if (hologram != null) {
                        updateHologramTextForViewer(viewer, target, hologram);
                    }
                }
            }
        }
    }

    public void recordPrediction(Player target, double score) {
        updateHologramsForTarget(target);
    }

    private void startTask() {
        if (teleportTask != null) return;
        teleportTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (enabledViewers.isEmpty()) {
                stopTask();
                return;
            }
            for (UUID viewerId : enabledViewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null || !viewer.isOnline()) {
                    enabledViewers.remove(viewerId);
                    activeHolograms.remove(viewerId);
                    continue;
                }
                Map<UUID, ViewerHologram> viewerMap = activeHolograms.computeIfAbsent(viewerId, k -> new ConcurrentHashMap<>());
                for (Player target : Bukkit.getOnlinePlayers()) {
                    UUID targetId = target.getUniqueId();
                    if (targetId.equals(viewerId)) {
                        destroyHologram(viewer, targetId);
                        continue;
                    }
                    if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                        destroyHologram(viewer, targetId);
                        continue;
                    }
                    ViewerHologram hologram = viewerMap.get(targetId);
                    if (hologram == null) {
                        spawnHologram(viewer, target);
                    } else {
                        Location targetLoc = target.getLocation();
                        if (hologram.getLastLocation().distanceSquared(targetLoc) >= 3.61) {
                            hologram.setLastLocation(targetLoc.clone());
                            WrapperPlayServerEntityTeleport teleportBottom = new WrapperPlayServerEntityTeleport(
                                hologram.getBottomId(),
                                new com.github.retrooper.packetevents.protocol.world.Location(targetLoc.getX(), targetLoc.getY() + 2.4, targetLoc.getZ(), 0f, 0f),
                                false
                            );
                            WrapperPlayServerEntityTeleport teleportTop = new WrapperPlayServerEntityTeleport(
                                hologram.getTopId(),
                                new com.github.retrooper.packetevents.protocol.world.Location(targetLoc.getX(), targetLoc.getY() + 2.65, targetLoc.getZ(), 0f, 0f),
                                false
                            );
                            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleportBottom);
                            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleportTop);
                        }
                    }
                }
            }
        }, 1L, 1L);
    }

    private void stopTask() {
        if (teleportTask != null) {
            teleportTask.cancel();
            teleportTask = null;
        }
    }

    public void handleJoin(Player joiner) {
        for (UUID viewerId : enabledViewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                spawnHologram(viewer, joiner);
            }
        }
    }

    public void handleQuit(Player quitter) {
        removeViewer(quitter);
        UUID quitterId = quitter.getUniqueId();
        for (UUID viewerId : enabledViewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                destroyHologram(viewer, quitterId);
            }
        }
    }

    public void clearAll() {
        stopTask();
        for (UUID viewerId : enabledViewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                Map<UUID, ViewerHologram> viewerMap = activeHolograms.get(viewerId);
                if (viewerMap != null) {
                    for (ViewerHologram hologram : viewerMap.values()) {
                        destroyHologram(viewer, hologram);
                    }
                }
            }
        }
        activeHolograms.clear();
        enabledViewers.clear();
    }

    public boolean isInfoTag(org.bukkit.entity.Entity entity) {
        return false;
    }

    private String getColorForScore(double score) {
        if (score < 0.35) return "§a";
        if (score < 0.70) return "§e";
        if (score < 0.85) return "§6";
        return "§c";
    }

    private String getColorForPing(int ping) {
        if (ping < 60) return "§a";
        if (ping < 120) return "§e";
        if (ping < 200) return "§6";
        return "§c";
    }
}
