package mrresi.cloudac.utils;

public class OptifineFastMath {
    private static final float[] SIN = new float[4096];

    static {
        for (int i = 0; i < 4096; i++) {
            SIN[i] = roundToFloat(StrictMath.sin(i * Math.PI * 2d / 4096d));
        }
    }

    public static float sin(float value) {
        return SIN[(int) (value * 651.8986f) & 4095];
    }

    public static float cos(float value) {
        return SIN[(int) (value * 651.8986f + 1024f) & 4095];
    }

    public static float roundToFloat(double value) {
        return (float) ((double) Math.round(value * 1.0E8d) / 1.0E8d);
    }
}
