package mrresi.cloudac.utils;

public class ModernVanillaMath {
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; ++i) {
            SIN[i] = (float) StrictMath.sin(i / 10430.378350470453);
        }
    }

    public static float sin(double value) {
        return SIN[(int) ((long) (value * 10430.378350470453) & 65535L)];
    }

    public static float cos(double value) {
        return SIN[(int) ((long) (value * 10430.378350470453 + 16384.0) & 65535L)];
    }
}
