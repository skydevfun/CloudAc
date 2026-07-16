package mrresi.cloudac.ml;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
public class RunningMode {
    private final int maxSize;
    private final Queue<Double> addList;
    private final Map<Double, Integer> popularityMap = new HashMap<>();
    private double modeValue = 0.0;
    private int modeCount = 0;
    private static final double THRESHOLD = 1e-3;
    public RunningMode(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("There's no mode to a size <= 0 list!");
        }
        this.maxSize = maxSize;
        this.addList = new ArrayDeque<>(maxSize);
    }
    public int size() {
        return addList.size();
    }
    public void add(double value) {
        pop();
        for (Map.Entry<Double, Integer> entry : popularityMap.entrySet()) {
            if (Math.abs(entry.getKey() - value) < THRESHOLD) {
                entry.setValue(entry.getValue() + 1);
                addList.add(entry.getKey());
                return;
            }
        }
        popularityMap.put(value, 1);
        addList.add(value);
    }
    private void pop() {
        if (addList.size() >= maxSize) {
            Double type = addList.poll();
            if (type != null) {
                Integer popularity = popularityMap.get(type);
                if (popularity != null) {
                    if (popularity == 1) {
                        popularityMap.remove(type);
                    } else {
                        popularityMap.put(type, popularity - 1);
                    }
                }
            }
        }
    }
    public void updateMode() {
        int max = 0;
        double mostPopular = 0.0;
        for (Map.Entry<Double, Integer> entry : popularityMap.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                mostPopular = entry.getKey();
            }
        }
        this.modeValue = mostPopular;
        this.modeCount = max;
    }
    public double getModeValue() {
        return modeValue;
    }
    public int getModeCount() {
        return modeCount;
    }
}
