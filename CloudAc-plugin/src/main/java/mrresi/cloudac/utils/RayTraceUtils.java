package mrresi.cloudac.utils;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.util.Vector;

public class RayTraceUtils {
    private static final double EPSILON = 1.0000000116860974E-7D;

    public static Vector getLookVanilla(float yaw, float pitch) {
        float pitchRadians = pitch * 0.017453292F;
        float yawRadians = -yaw * 0.017453292F;
        float pitchCos = (float) Math.cos(pitchRadians);
        float x = (float) Math.sin(yawRadians);
        float y = (float) Math.sin(pitchRadians);
        float z = (float) Math.cos(yawRadians);
        return new Vector(x * pitchCos, -y, z * pitchCos);
    }

    public static Vector getLookGrim(ClientVersion version, float yaw, float pitch, boolean isVanillaMath) {
        if (version.isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
            float yawRadians = (yaw * -0.017453292F) - (float) Math.PI;
            float pitchRadians = -pitch * 0.017453292F;

            float pitchCos;
            float x;
            float y;
            float z;
            if (isVanillaMath) {
                pitchCos = -VanillaMath.cos(pitchRadians);
                x = VanillaMath.sin(yawRadians);
                y = VanillaMath.sin(pitchRadians);
                z = VanillaMath.cos(yawRadians);
            } else {
                if (version.isNewerThanOrEquals(ClientVersion.V_1_8)) {
                    pitchCos = -OptifineFastMath.cos(pitchRadians);
                    x = OptifineFastMath.sin(yawRadians);
                    y = OptifineFastMath.sin(pitchRadians);
                    z = OptifineFastMath.cos(yawRadians);
                } else {
                    pitchCos = -LegacyFastMath.cos(pitchRadians);
                    x = LegacyFastMath.sin(yawRadians);
                    y = LegacyFastMath.sin(pitchRadians);
                    z = LegacyFastMath.cos(yawRadians);
                }
            }
            return new Vector(x * pitchCos, y, z * pitchCos);
        }

        float pitchRadians = pitch * 0.017453292F;
        float yawRadians = -yaw * 0.017453292F;

        float pitchCos;
        float x;
        float y;
        float z;
        if (isVanillaMath) {
            if (version.isNewerThanOrEquals(ClientVersion.V_1_21_11)) {
                pitchCos = ModernVanillaMath.cos(pitchRadians);
                x = ModernVanillaMath.sin(yawRadians);
                y = ModernVanillaMath.sin(pitchRadians);
                z = ModernVanillaMath.cos(yawRadians);
            } else {
                pitchCos = VanillaMath.cos(pitchRadians);
                x = VanillaMath.sin(yawRadians);
                y = VanillaMath.sin(pitchRadians);
                z = VanillaMath.cos(yawRadians);
            }
        } else {
            pitchCos = OptifineFastMath.cos(pitchRadians);
            x = OptifineFastMath.sin(yawRadians);
            y = OptifineFastMath.sin(pitchRadians);
            z = OptifineFastMath.cos(yawRadians);
        }
        return new Vector(x * pitchCos, -y, z * pitchCos);
    }

    public static Vector calculateIntercept(SimpleCollisionBox box, Vector origin, Vector end) {
        Vector minX = getIntermediateWithXValue(origin, end, box.minX);
        Vector maxX = getIntermediateWithXValue(origin, end, box.maxX);
        Vector minY = getIntermediateWithYValue(origin, end, box.minY);
        Vector maxY = getIntermediateWithYValue(origin, end, box.maxY);
        Vector minZ = getIntermediateWithZValue(origin, end, box.minZ);
        Vector maxZ = getIntermediateWithZValue(origin, end, box.maxZ);

        if (!isVecInYZ(box, minX)) minX = null;
        if (!isVecInYZ(box, maxX)) maxX = null;
        if (!isVecInXZ(box, minY)) minY = null;
        if (!isVecInXZ(box, maxY)) maxY = null;
        if (!isVecInXY(box, minZ)) minZ = null;
        if (!isVecInXY(box, maxZ)) maxZ = null;

        Vector best = null;
        if (minX != null) best = minX;
        if (maxX != null && (best == null || origin.distanceSquared(maxX) < origin.distanceSquared(best))) best = maxX;
        if (minY != null && (best == null || origin.distanceSquared(minY) < origin.distanceSquared(best))) best = minY;
        if (maxY != null && (best == null || origin.distanceSquared(maxY) < origin.distanceSquared(best))) best = maxY;
        if (minZ != null && (best == null || origin.distanceSquared(minZ) < origin.distanceSquared(best))) best = minZ;
        if (maxZ != null && (best == null || origin.distanceSquared(maxZ) < origin.distanceSquared(best))) best = maxZ;

        return best;
    }

    public static double distanceToBox(SimpleCollisionBox box, Vector origin) {
        double dx = clampDistance(origin.getX(), box.minX, box.maxX);
        double dy = clampDistance(origin.getY(), box.minY, box.maxY);
        double dz = clampDistance(origin.getZ(), box.minZ, box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clampDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0D;
    }

    private static Vector getIntermediateWithXValue(Vector self, Vector other, double x) {
        double deltaX = other.getX() - self.getX();
        double deltaY = other.getY() - self.getY();
        double deltaZ = other.getZ() - self.getZ();
        if (deltaX * deltaX < EPSILON) {
            return null;
        }
        double factor = (x - self.getX()) / deltaX;
        return factor >= 0.0D && factor <= 1.0D
            ? new Vector(self.getX() + deltaX * factor, self.getY() + deltaY * factor, self.getZ() + deltaZ * factor)
            : null;
    }

    private static Vector getIntermediateWithYValue(Vector self, Vector other, double y) {
        double deltaX = other.getX() - self.getX();
        double deltaY = other.getY() - self.getY();
        double deltaZ = other.getZ() - self.getZ();
        if (deltaY * deltaY < EPSILON) {
            return null;
        }
        double factor = (y - self.getY()) / deltaY;
        return factor >= 0.0D && factor <= 1.0D
            ? new Vector(self.getX() + deltaX * factor, self.getY() + deltaY * factor, self.getZ() + deltaZ * factor)
            : null;
    }

    private static Vector getIntermediateWithZValue(Vector self, Vector other, double z) {
        double deltaX = other.getX() - self.getX();
        double deltaY = other.getY() - self.getY();
        double deltaZ = other.getZ() - self.getZ();
        if (deltaZ * deltaZ < EPSILON) {
            return null;
        }
        double factor = (z - self.getZ()) / deltaZ;
        return factor >= 0.0D && factor <= 1.0D
            ? new Vector(self.getX() + deltaX * factor, self.getY() + deltaY * factor, self.getZ() + deltaZ * factor)
            : null;
    }

    private static boolean isVecInYZ(SimpleCollisionBox box, Vector vec) {
        return vec != null && vec.getY() >= box.minY && vec.getY() <= box.maxY && vec.getZ() >= box.minZ && vec.getZ() <= box.maxZ;
    }

    private static boolean isVecInXZ(SimpleCollisionBox box, Vector vec) {
        return vec != null && vec.getX() >= box.minX && vec.getX() <= box.maxX && vec.getZ() >= box.minZ && vec.getZ() <= box.maxZ;
    }

    private static boolean isVecInXY(SimpleCollisionBox box, Vector vec) {
        return vec != null && vec.getX() >= box.minX && vec.getX() <= box.maxX && vec.getY() >= box.minY && vec.getY() <= box.maxY;
    }

    public static boolean isVecInside(SimpleCollisionBox box, Vector vec) {
        return vec.getX() > box.minX && vec.getX() < box.maxX
            && vec.getY() > box.minY && vec.getY() < box.maxY
            && vec.getZ() > box.minZ && vec.getZ() < box.maxZ;
    }

    public static boolean hasIntersection(SimpleCollisionBox box, Vector origin, Vector direction, double maxDistance) {
        if (isVecInside(box, origin)) {
            return true;
        }
        Vector end = origin.clone().add(direction.clone().multiply(maxDistance));
        return calculateIntercept(box, origin, end) != null;
    }
}
