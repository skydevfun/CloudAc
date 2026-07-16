package mrresi.cloudac.ml;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataCollector {

    private final Map<UUID, PlayerData> playerDataMap;
    private static final int SEQUENCE_SIZE = 20;

    public PlayerDataCollector() {
        this.playerDataMap = new ConcurrentHashMap<>();
    }

    public void recordRotation(Player player, float yaw, float pitch) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.computeIfAbsent(uuid, k -> new PlayerData());
        data.recordRotation(yaw, pitch);
    }

    public void recordHit(Player player, Entity target) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.computeIfAbsent(uuid, k -> new PlayerData());
        data.recordAttack();
        if (target != null) {
            double radius = target.getWidth() / 2.0;
            double height = target.getHeight();
            double minX = target.getLocation().getX() - radius;
            double maxX = target.getLocation().getX() + radius;
            double minY = target.getLocation().getY();
            double maxY = target.getLocation().getY() + height;
            double minZ = target.getLocation().getZ() - radius;
            double maxZ = target.getLocation().getZ() + radius;
            double ex = player.getEyeLocation().getX();
            double ey = player.getEyeLocation().getY();
            double ez = player.getEyeLocation().getZ();
            double dx = Math.max(0.0, Math.max(minX - ex, ex - maxX));
            double dy = Math.max(0.0, Math.max(minY - ey, ey - maxY));
            double dz = Math.max(0.0, Math.max(minZ - ez, ez - maxZ));
            double actualReach = Math.sqrt(dx * dx + dy * dy + dz * dz);
            data.setReachDistance(actualReach);
            org.bukkit.util.Vector look = player.getEyeLocation().getDirection();
            org.bukkit.util.Vector toTarget = target.getLocation().toVector().subtract(player.getEyeLocation().toVector());
            double angle = Math.toDegrees(look.angle(toTarget));
            data.setAngleToTarget(angle);
        }
    }

    public void recordAttack(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.computeIfAbsent(uuid, k -> new PlayerData());
        data.recordAttack();
    }

    public void recordRawClick(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.computeIfAbsent(uuid, k -> new PlayerData());
        data.recordRawClick();
    }

    public double[] extractFeatures(UUID uuid) {
        PlayerData data = playerDataMap.get(uuid);
        if (data == null || data.getTickHistorySize() < SEQUENCE_SIZE) {
            return new double[SEQUENCE_SIZE * 8];
        }
        return data.getSerializedSequence();
    }

    public PlayerData getPlayerData(UUID uuid) {
        return playerDataMap.get(uuid);
    }

    public void recordPacketAnomaly(UUID uuid, long packetsPerSec) {
        PlayerData data = playerDataMap.computeIfAbsent(uuid, k -> new PlayerData());
        data.addPacketAnomaly(packetsPerSec);
    }

    public void clearPlayerData(UUID uuid) {
        playerDataMap.remove(uuid);
    }

    public static class PlayerData {

        private final LinkedList<float[]> tickHistory;
        private final LinkedList<Long> attacks;
        private final LinkedList<Long> rawClicks;
        private final RunningMode xRotMode;
        private final RunningMode yRotMode;
        private double lastXRot = 0.0;
        private double lastYRot = 0.0;
        private float lastDeltaYaw = 0.0f;
        private float lastDeltaPitch = 0.0f;
        private float lastYawAccel = 0.0f;
        private float lastPitchAccel = 0.0f;
        private float currentYawAccel = 0.0f;
        private float currentPitchAccel = 0.0f;
        private double modeX = 0.0;
        private double modeY = 0.0;
        private float lastYaw = 0.0f;
        private float lastPitch = 0.0f;
        private boolean hasLastRotation = false;
        private int totalHits;
        private int totalMisses;
        private double lastReachDistance;
        private double angleToTarget = 0.0;
        private int sessionHits;
        private long lastAttackTime;
        private long lastPacketAnomaly;
        private int packetAnomalyCount;

        public PlayerData() {
            this.tickHistory = new LinkedList<>();
            this.attacks = new LinkedList<>();
            this.rawClicks = new LinkedList<>();
            this.xRotMode = new RunningMode(80);
            this.yRotMode = new RunningMode(80);
            this.totalHits = 0;
            this.totalMisses = 0;
            this.lastReachDistance = 0.0;
            this.sessionHits = 0;
            this.lastAttackTime = 0;
            this.lastPacketAnomaly = 0;
            this.packetAnomalyCount = 0;
        }

        public synchronized void recordRotation(float yaw, float pitch) {
            float deltaYaw = hasLastRotation ? angleDiff(yaw, lastYaw) : 0f;
            float deltaPitch = hasLastRotation ? (pitch - lastPitch) : 0f;
            double deltaYawAbs = Math.abs(deltaYaw);
            double deltaPitchAbs = Math.abs(deltaPitch);
            lastYawAccel = currentYawAccel;
            lastPitchAccel = currentPitchAccel;
            currentYawAccel = (float) (deltaYawAbs - Math.abs(lastDeltaYaw));
            currentPitchAccel = (float) (deltaPitchAbs - Math.abs(lastDeltaPitch));
            lastDeltaYaw = deltaYaw;
            lastDeltaPitch = deltaPitch;
            double divisorX = gcd(deltaYawAbs, lastXRot);
            if (deltaYawAbs > 0 && deltaYawAbs < 5 && divisorX > 0.0086) {
                xRotMode.add(divisorX);
                lastXRot = deltaYawAbs;
            }
            double divisorY = gcd(deltaPitchAbs, lastYRot);
            if (deltaPitchAbs > 0 && deltaPitchAbs < 5 && divisorY > 0.0086) {
                yRotMode.add(divisorY);
                lastYRot = deltaPitchAbs;
            }
            if (xRotMode.size() > 15) {
                xRotMode.updateMode();
                if (xRotMode.getModeCount() > 15) {
                    modeX = xRotMode.getModeValue();
                }
            }
            if (yRotMode.size() > 15) {
                yRotMode.updateMode();
                if (yRotMode.getModeCount() > 15) {
                    modeY = yRotMode.getModeValue();
                }
            }
            float jerkYaw = currentYawAccel - lastYawAccel;
            float jerkPitch = currentPitchAccel - lastPitchAccel;
            float gcdErrorYaw = 0f;
            if (modeX > 0) {
                double errorX = deltaYawAbs % modeX;
                gcdErrorYaw = (float) Math.min(errorX, modeX - errorX);
            }
            float gcdErrorPitch = 0f;
            if (modeY > 0) {
                double errorY = deltaPitchAbs % modeY;
                gcdErrorPitch = (float) Math.min(errorY, modeY - errorY);
            }
            float[] tickFeatures = new float[]{
                deltaYaw,
                deltaPitch,
                currentYawAccel,
                currentPitchAccel,
                jerkYaw,
                jerkPitch,
                gcdErrorYaw,
                gcdErrorPitch
            };
            tickHistory.add(tickFeatures);
            while (tickHistory.size() > SEQUENCE_SIZE) {
                tickHistory.removeFirst();
            }
            lastYaw = yaw;
            lastPitch = pitch;
            hasLastRotation = true;
        }

        public synchronized void clearRotationHistory() {
            tickHistory.clear();
        }

        public synchronized int getTickHistorySize() {
            return tickHistory.size();
        }

        public synchronized double[] getSerializedSequence() {
            double[] serialized = new double[SEQUENCE_SIZE * 8];
            int index = 0;
            int missing = SEQUENCE_SIZE - tickHistory.size();
            for (int i = 0; i < missing; i++) {
                index += 8;
            }
            for (float[] tick : tickHistory) {
                for (float val : tick) {
                    serialized[index++] = val;
                }
            }
            return serialized;
        }

        private long lastAttackInterval = 0;

        public synchronized void recordAttack() {
            long timestamp = System.currentTimeMillis();
            if (lastAttackTime > 0) {
                lastAttackInterval = timestamp - lastAttackTime;
            } else {
                lastAttackInterval = 0;
            }
            if (timestamp - lastAttackTime > 4000) {
                sessionHits = 0;
            }
            sessionHits++;
            lastAttackTime = timestamp;
            attacks.add(timestamp);
            totalHits++;
            while (!attacks.isEmpty() && attacks.getFirst() < timestamp - 1000) {
                attacks.removeFirst();
            }
        }

        public synchronized long getLastAttackInterval() {
            return lastAttackInterval;
        }

        public synchronized void recordRawClick() {
            long timestamp = System.currentTimeMillis();
            rawClicks.add(timestamp);
            while (!rawClicks.isEmpty() && rawClicks.getFirst() < timestamp - 1000) {
                rawClicks.removeFirst();
            }
        }

        public synchronized void addMiss() {
            totalMisses++;
        }

        public synchronized void setReachDistance(double distance) {
            this.lastReachDistance = distance;
        }

        public synchronized boolean isInCombat() {
            return System.currentTimeMillis() - lastAttackTime < 2500;
        }

        public synchronized int getSessionHits() {
            if (System.currentTimeMillis() - lastAttackTime > 4000) {
                sessionHits = 0;
            }
            return sessionHits;
        }

        public synchronized double getYawDelta() {
            if (tickHistory.size() < 2) return 0.0;
            double sum = 0.0;
            for (float[] tick : tickHistory) {
                sum += Math.abs(tick[0]);
            }
            return sum / tickHistory.size();
        }

        public synchronized double getPitchDelta() {
            if (tickHistory.size() < 2) return 0.0;
            double sum = 0.0;
            for (float[] tick : tickHistory) {
                sum += Math.abs(tick[1]);
            }
            return sum / tickHistory.size();
        }

        public synchronized double getYawAcceleration() {
            if (tickHistory.size() < 2) return 0.0;
            double sum = 0.0;
            for (float[] tick : tickHistory) {
                sum += Math.abs(tick[2]);
            }
            return sum / tickHistory.size();
        }

        public synchronized double getPitchAcceleration() {
            if (tickHistory.size() < 2) return 0.0;
            double sum = 0.0;
            for (float[] tick : tickHistory) {
                sum += Math.abs(tick[3]);
            }
            return sum / tickHistory.size();
        }

        public synchronized double getAngleToTarget() {
            return angleToTarget;
        }

        public synchronized void setAngleToTarget(double angle) {
            this.angleToTarget = angle;
        }

        public synchronized double getSnapRatio() {
            if (tickHistory.size() < 2) return 0.0;
            int snaps = 0;
            for (float[] tick : tickHistory) {
                float accelYaw = Math.abs(tick[2]);
                if (accelYaw > 10.0f) {
                    snaps++;
                }
            }
            return (double) snaps / tickHistory.size();
        }

        public synchronized double getJitter() {
            if (tickHistory.size() < 2) return 0.0;
            double sum = 0.0;
            for (float[] tick : tickHistory) {
                sum += Math.abs(tick[4]);
            }
            return sum / tickHistory.size();
        }

        public synchronized double getClickSync() {
            if (rawClicks.isEmpty()) return 1.0;
            return Math.min(1.0, (double) attacks.size() / rawClicks.size());
        }

        public synchronized double getCPS() {
            return rawClicks.size();
        }

        public synchronized double getHitRate() {
            if (totalHits + totalMisses == 0) return 0.0;
            return (double) totalHits / (totalHits + totalMisses);
        }

        public synchronized int getTotalHits() {
            return totalHits;
        }

        public synchronized double getReachDistance() {
            return lastReachDistance > 3.2 ? lastReachDistance : 0.0;
        }

        public synchronized double getLastReachDistance() {
            return lastReachDistance;
        }

        public static double gcd(double a, double b) {
            if (a == 0.0) return 0.0;
            if (a < b) {
                double temp = a;
                a = b;
                b = temp;
            }
            double minimumDivisor = 0.0086;
            while (b > minimumDivisor) {
                double temp = a - (Math.floor(a / b) * b);
                a = b;
                b = temp;
            }
            return a;
        }

        private float angleDiff(float angle1, float angle2) {
            float diff = angle1 - angle2;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            return diff;
        }

        public synchronized void addPacketAnomaly(long packetsPerSec) {
            this.lastPacketAnomaly = packetsPerSec;
            this.packetAnomalyCount++;
        }

        public synchronized int getPacketAnomalyCount() {
            return packetAnomalyCount;
        }

        public synchronized long getLastPacketAnomaly() {
            return lastPacketAnomaly;
        }
    }
}
