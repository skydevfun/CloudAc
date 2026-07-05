package mrresi.cloudac.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import mrresi.cloudac.CloudAC;
import mrresi.cloudac.ml.PlayerDataCollector;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import org.bukkit.util.BoundingBox;

public class PacketListener extends PacketListenerAbstract {

    private final CloudAC plugin;
    private final PlayerDataCollector dataCollector;
    private final Map<UUID, List<float[]>> rotationHistory = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> packetCounters = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> lastPosition = new ConcurrentHashMap<>();
    private final Map<UUID, float[]> lastRotation = new ConcurrentHashMap<>();
    private final Map<UUID, float[]> attackRotation = new ConcurrentHashMap<>();
    private final Map<UUID, List<float[]>> attackRotationHistory = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> attackPosition = new ConcurrentHashMap<>();
    
    private int currentServerTick = 0;
    private final Map<Integer, Map<UUID, BoundingBox>> serverSnapshots = new ConcurrentHashMap<>();
    private final Map<Integer, Long> pingTickTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastReceivedPingId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerPing = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> attackTick = new ConcurrentHashMap<>();

    public PacketListener(CloudAC plugin, PlayerDataCollector dataCollector) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.dataCollector = dataCollector;
    }

    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            currentServerTick++;
            Map<UUID, BoundingBox> snapshot = new java.util.HashMap<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (Player p : world.getEntitiesByClass(Player.class)) {
                    snapshot.put(p.getUniqueId(), p.getBoundingBox());
                }
            }
            pingTickTimestamps.put(currentServerTick, System.currentTimeMillis());
            for (Player p : Bukkit.getOnlinePlayers()) {
                WrapperPlayServerPing ping = new WrapperPlayServerPing(currentServerTick);
                PacketEvents.getAPI().getPlayerManager().sendPacket(p, ping);
            }
            serverSnapshots.put(currentServerTick, snapshot);
            serverSnapshots.remove(currentServerTick - 40);
            pingTickTimestamps.remove(currentServerTick - 40);
        }, 1L, 1L);
    }

    public int getCurrentServerTick() {
        return currentServerTick;
    }

    public Map<Integer, Map<UUID, BoundingBox>> getServerSnapshots() {
        return serverSnapshots;
    }

    public Integer getAttackTick(UUID uuid) {
        return attackTick.get(uuid);
    }

    public int getPlayerPing(UUID uuid) {
        return playerPing.getOrDefault(uuid, 100);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled()) return;
        Player player = (Player) event.getPlayer();
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Client.PONG) {
            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
            int pongId = (int) pong.getId();
            lastReceivedPingId.put(uuid, pongId);
            Long sendTime = pingTickTimestamps.get(pongId);
            if (sendTime != null) {
                int rtt = (int) (System.currentTimeMillis() - sendTime);
                playerPing.put(uuid, Math.max(0, Math.min(rtt, 1000)));
            }
            return;
        }

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            trackPacketRate(uuid);
            float[] currentRot = lastRotation.get(uuid);
            if (currentRot != null) {
                attackRotation.put(uuid, currentRot);
            } else {
                attackRotation.put(uuid, new float[]{player.getLocation().getYaw(), player.getLocation().getPitch()});
            }
            List<float[]> hist = rotationHistory.get(uuid);
            if (hist != null) {
                attackRotationHistory.put(uuid, new java.util.ArrayList<>(hist));
            }
            double[] currentPos = lastPosition.get(uuid);
            if (currentPos != null) {
                attackPosition.put(uuid, currentPos);
            } else {
                Location loc = player.getLocation();
                attackPosition.put(uuid, new double[]{loc.getX(), loc.getY(), loc.getZ()});
            }
            attackTick.put(uuid, lastReceivedPingId.getOrDefault(uuid, currentServerTick));
            return;
        }

        boolean hasPos = type == PacketType.Play.Client.PLAYER_POSITION || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
        boolean hasLook = type == PacketType.Play.Client.PLAYER_ROTATION || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;

        double x = 0;
        double y = 0;
        double z = 0;
        float yaw = 0;
        float pitch = 0;

        if (type == PacketType.Play.Client.PLAYER_POSITION) {
            WrapperPlayClientPlayerPosition pos = new WrapperPlayClientPlayerPosition(event);
            x = pos.getLocation().getX();
            y = pos.getLocation().getY();
            z = pos.getLocation().getZ();
        } else if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            WrapperPlayClientPlayerRotation rot = new WrapperPlayClientPlayerRotation(event);
            yaw = rot.getYaw();
            pitch = rot.getPitch();
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerPositionAndRotation posRot = new WrapperPlayClientPlayerPositionAndRotation(event);
            x = posRot.getLocation().getX();
            y = posRot.getLocation().getY();
            z = posRot.getLocation().getZ();
            yaw = posRot.getYaw();
            pitch = posRot.getPitch();
        }

        if (hasPos && (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y) || Double.isNaN(z) || Double.isInfinite(z))) {
            event.setCancelled(true);
            return;
        }

        if (hasPos) {
            lastPosition.put(uuid, new double[]{x, y, z});
        }

        if (hasLook) {
            lastRotation.put(uuid, new float[]{yaw, pitch});
            List<float[]> rotations = rotationHistory.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>());
            boolean hasPrev = !rotations.isEmpty();
            rotations.add(new float[]{yaw, pitch});
            if (rotations.size() > 5) {
                rotations.remove(0);
            }
            if (hasPrev) {
                trackPacketRate(uuid);
            }
            dataCollector.recordRotation(player, yaw, pitch);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) return;
        Player observer = (Player) event.getPlayer();
        if (observer == null) return;
        com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type = event.getPacketType();
        boolean spoofAll = plugin.getConfig().getBoolean("spoofing.hp.all-players", false);

        if (type == PacketType.Play.Server.UPDATE_SCORE) {
            WrapperPlayServerUpdateScore packet = new WrapperPlayServerUpdateScore(event);
            String entityName = packet.getEntityName();
            if (entityName != null) {
                Player targetPlayer = Bukkit.getPlayer(entityName);
                if (targetPlayer != null && targetPlayer != observer && (spoofAll || targetPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY))) {
                    packet.setValue(java.util.Optional.of((int) getSpoofedHealth(targetPlayer)));
                }
            }
        } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
            int entityId = packet.getEntityId();
            Player targetPlayer = null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getEntityId() == entityId) {
                    targetPlayer = p;
                    break;
                }
            }
            if (targetPlayer != null && targetPlayer != observer && (spoofAll || targetPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY))) {
                float spoofed = getSpoofedHealth(targetPlayer);
                List<EntityData<?>> dataList = packet.getEntityMetadata();
                if (dataList != null) {
                    for (EntityData<?> data : dataList) {
                        int idx = data.getIndex();
                        if ((idx == 9 || idx == 8 || idx == 7 || idx == 6) && data.getType() == EntityDataTypes.FLOAT) {
                            ((EntityData<Float>) data).setValue(spoofed);
                        }
                    }
                }
            }
        }
    }

    private void trackPacketRate(UUID uuid) {
        long now = System.currentTimeMillis();
        long[] counter = packetCounters.computeIfAbsent(uuid, k -> new long[]{0, now});
        counter[0]++;
        if (now - counter[1] >= 1000) {
            long packetsPerSec = counter[0];
            counter[0] = 0;
            counter[1] = now;
            if (packetsPerSec > 60) {
                dataCollector.recordPacketAnomaly(uuid, packetsPerSec);
            }
        }
    }

    public void cleanup(UUID uuid) {
        rotationHistory.remove(uuid);
        packetCounters.remove(uuid);
        lastPosition.remove(uuid);
        lastRotation.remove(uuid);
        attackRotation.remove(uuid);
        attackRotationHistory.remove(uuid);
        attackPosition.remove(uuid);
        lastReceivedPingId.remove(uuid);
        playerPing.remove(uuid);
        attackTick.remove(uuid);
    }

    public List<float[]> getRotationHistory(UUID uuid) {
        return rotationHistory.get(uuid);
    }

    public List<float[]> getAttackRotationHistory(UUID uuid) {
        return attackRotationHistory.get(uuid);
    }

    public float[] getAttackRotation(UUID uuid) {
        return attackRotation.get(uuid);
    }

    public double[] getAttackPosition(UUID uuid) {
        return attackPosition.get(uuid);
    }


    private float getSpoofedHealth(Player player) {
        if (!plugin.getConfig().getBoolean("spoofing.hp.enabled", false)) {
            return (float) player.getHealth();
        }
        String mode = plugin.getConfig().getString("spoofing.hp.mode", "static");
        if ("random".equalsIgnoreCase(mode)) {
            double min = plugin.getConfig().getDouble("spoofing.hp.min-value", 1.0);
            double max = plugin.getConfig().getDouble("spoofing.hp.max-value", 20.0);
            return (float) (min + Math.random() * (max - min));
        } else if ("percent".equalsIgnoreCase(mode)) {
            double multiplier = plugin.getConfig().getDouble("spoofing.hp.percent-multiplier", 0.5);
            return (float) (player.getHealth() * multiplier);
        } else {
            return (float) plugin.getConfig().getDouble("spoofing.hp.static-value", 20.0);
        }
    }
}
