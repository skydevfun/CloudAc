package mrresi.cloudac.utils;

public class SimpleCollisionBox {
    public static final double COLLISION_EPSILON = 1.0E-7;

    public double minX, minY, minZ, maxX, maxY, maxZ;

    public SimpleCollisionBox() {
        this(0, 0, 0, 0, 0, 0);
    }

    public SimpleCollisionBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        sort();
    }

    public SimpleCollisionBox(org.bukkit.util.BoundingBox box) {
        this(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }

    public SimpleCollisionBox sort() {
        double sortedMinX = Math.min(this.minX, this.maxX);
        double sortedMinY = Math.min(this.minY, this.maxY);
        double sortedMinZ = Math.min(this.minZ, this.maxZ);
        double sortedMaxX = Math.max(this.minX, this.maxX);
        double sortedMaxY = Math.max(this.minY, this.maxY);
        double sortedMaxZ = Math.max(this.minZ, this.maxZ);

        this.minX = sortedMinX;
        this.minY = sortedMinY;
        this.minZ = sortedMinZ;
        this.maxX = sortedMaxX;
        this.maxY = sortedMaxY;
        this.maxZ = sortedMaxZ;
        return this;
    }

    public SimpleCollisionBox expand(double x, double y, double z) {
        this.minX -= x;
        this.minY -= y;
        this.minZ -= z;
        this.maxX += x;
        this.maxY += y;
        this.maxZ += z;
        return sort();
    }

    public SimpleCollisionBox expand(double value) {
        return expand(value, value, value);
    }

    public SimpleCollisionBox expandMin(double x, double y, double z) {
        this.minX += x;
        this.minY += y;
        this.minZ += z;
        return this;
    }

    public SimpleCollisionBox expandMax(double x, double y, double z) {
        this.maxX += x;
        this.maxY += y;
        this.maxZ += z;
        return this;
    }

    public SimpleCollisionBox offset(double x, double y, double z) {
        this.minX += x;
        this.minY += y;
        this.minZ += z;
        this.maxX += x;
        this.maxY += y;
        this.maxZ += z;
        return this;
    }

    public SimpleCollisionBox union(SimpleCollisionBox other) {
        this.minX = Math.min(this.minX, other.minX);
        this.minY = Math.min(this.minY, other.minY);
        this.minZ = Math.min(this.minZ, other.minZ);
        this.maxX = Math.max(this.maxX, other.maxX);
        this.maxY = Math.max(this.maxY, other.maxY);
        this.maxZ = Math.max(this.maxZ, other.maxZ);
        return this;
    }

    public boolean isCollided(SimpleCollisionBox other) {
        return other.maxX >= this.minX && other.minX <= this.maxX
            && other.maxY >= this.minY && other.minY <= this.maxY
            && other.maxZ >= this.minZ && other.minZ <= this.maxZ;
    }

    public boolean isIntersected(SimpleCollisionBox other) {
        return other.maxX - COLLISION_EPSILON > this.minX && other.minX + COLLISION_EPSILON < this.maxX
            && other.maxY - COLLISION_EPSILON > this.minY && other.minY + COLLISION_EPSILON < this.maxY
            && other.maxZ - COLLISION_EPSILON > this.minZ && other.minZ + COLLISION_EPSILON < this.maxZ;
    }

    public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return this.minX < maxX && this.maxX > minX
            && this.minY < maxY && this.maxY > minY
            && this.minZ < maxZ && this.maxZ > minZ;
    }

    public SimpleCollisionBox copy() {
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
