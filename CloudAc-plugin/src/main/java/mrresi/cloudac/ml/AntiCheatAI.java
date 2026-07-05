package mrresi.cloudac.ml;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AntiCheatAI {

    private final NeuralNetwork network;
    private final PlayerDataCollector dataCollector;
    private final Map<UUID, Double> playerScores;
    private final Map<UUID, Integer> violationCounts;
    private final Map<UUID, Integer> cleanStreaks;
    private final Map<UUID, Integer> cheatStreaks;
    private final Map<UUID, Double> vlBuffer;
    private final Map<UUID, Boolean> playerMarks;
    private volatile TrainingMode trainingMode;
    private final List<TrainingData> trainingDataset;
    private static final int SEQUENCE_SIZE = 20;
    private static final int FEATURES_PER_TICK = 8;
    private static final int TOTAL_FEATURES = SEQUENCE_SIZE * FEATURES_PER_TICK;
    private static final double CHEAT_THRESHOLD = 0.70;
    private static final double[] TICK_FEAT_MIN = { -180, -90, -180, -90, -360, -180, 0, 0 };
    private static final double[] TICK_FEAT_MAX = { 180, 90, 180, 90, 360, 180, 0.5, 0.5 };
    private static final String[] FEAT_NAMES = {
        "deltaYaw", "deltaPitch", "accelYaw", "accelPitch",
        "jerkYaw", "jerkPitch", "gcdErrorYaw", "gcdErrorPitch"
    };

    public enum TrainingMode { OFF, SOFT, ANSOFT, AUTO }

    public AntiCheatAI() {
        this.network = new NeuralNetwork(TOTAL_FEATURES, 64, 32, 1, 0.008);
        this.dataCollector = new PlayerDataCollector();
        this.playerScores = new ConcurrentHashMap<>();
        this.violationCounts = new ConcurrentHashMap<>();
        this.cleanStreaks = new ConcurrentHashMap<>();
        this.cheatStreaks = new ConcurrentHashMap<>();
        this.vlBuffer = new ConcurrentHashMap<>();
        this.playerMarks = new ConcurrentHashMap<>();
        this.trainingMode = TrainingMode.OFF;
        this.trainingDataset = new ArrayList<>();
    }

    public double[] normalizeFeatures(double[] raw) {
        double[] norm = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int featIdx = i % FEATURES_PER_TICK;
            double min = TICK_FEAT_MIN[featIdx];
            double max = TICK_FEAT_MAX[featIdx];
            double range = max - min;
            if (range <= 0) {
                norm[i] = 0;
            } else {
                norm[i] = Math.max(0.0, Math.min(1.0, (raw[i] - min) / range));
            }
        }
        return norm;
    }

    public CheckResult analyzePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        return analyzePlayer(player, dataCollector.extractFeatures(uuid));
    }

    public CheckResult analyzePlayer(Player player, double[] rawFeatures) {
        UUID uuid = player.getUniqueId();
        PlayerDataCollector.PlayerData playerData = dataCollector.getPlayerData(uuid);
        if (playerData == null) {
            return new CheckResult(player.getName(), 0.0, false, 0, 0, 0, "LEGIT");
        }
        if (trainingMode != TrainingMode.OFF) {
            return new CheckResult(player.getName(), 0.0, false, 0,
                    playerData.getTotalHits(), playerData.getSessionHits(), "LEGIT");
        }
        double[] norm = normalizeFeatures(rawFeatures);
        double aiScore = network.predict(norm);

        double currentVl = vlBuffer.getOrDefault(uuid, 0.0);
        if (aiScore > 0.90) {
            currentVl += (aiScore - 0.85) * 20.0;
        } else if (aiScore < 0.10) {
            currentVl -= 2.0;
        } else {
            currentVl -= 0.5;
        }
        currentVl = Math.max(0.0, Math.min(100.0, currentVl));
        vlBuffer.put(uuid, currentVl);
        double previousScore = playerScores.getOrDefault(uuid, 0.0);
        double runningScore;
        
        if (aiScore >= CHEAT_THRESHOLD) {
            int cheat = cheatStreaks.getOrDefault(uuid, 0) + 1;
            cleanStreaks.put(uuid, 0);
            cheatStreaks.put(uuid, cheat);
            double rate = 0.35 + 0.05 * Math.min(5, cheat);
            runningScore = previousScore + (aiScore - previousScore) * rate;
        } else if (aiScore < 0.45) {
            int clean = cleanStreaks.getOrDefault(uuid, 0) + 1;
            cheatStreaks.put(uuid, 0);
            cleanStreaks.put(uuid, clean);
            double decay = 0.02 + 0.015 * clean;
            runningScore = Math.max(0.0, previousScore - decay);
        } else {
            runningScore = Math.max(0.0, previousScore - 0.01);
        }
        
        playerScores.put(uuid, runningScore);
        String alertType = determineAlertType(runningScore, playerData);
        boolean isCheat = !alertType.equals("LEGIT");
        if (isCheat) {
            int v = violationCounts.getOrDefault(uuid, 0) + 1;
            violationCounts.put(uuid, v);
            return new CheckResult(player.getName(), runningScore, true, v, playerData.getTotalHits(), playerData.getSessionHits(), alertType);
        } else {
            int v = violationCounts.getOrDefault(uuid, 0);
            if (v > 0) violationCounts.put(uuid, v - 1);
            return new CheckResult(player.getName(), runningScore, false, Math.max(0, v - 1), playerData.getTotalHits(), playerData.getSessionHits(), alertType);
        }
    }

    private String determineAlertType(double runningScore, PlayerDataCollector.PlayerData playerData) {
        boolean aiSuspect = runningScore >= CHEAT_THRESHOLD;
        int hardViolations = 0;
        if (playerData.getReachDistance() > 3.4) hardViolations++;
        if (playerData.getCPS() > 18) hardViolations++;
        if (playerData.getSnapRatio() > 0.4 && playerData.getAngleToTarget() < 3.0) hardViolations++;
        if (playerData.getJitter() < 0.1 && playerData.getYawDelta() > 15.0) hardViolations++;
        if (playerData.getClickSync() >= 0.95 && playerData.getCPS() > 8 && playerData.getHitRate() > 0.9) hardViolations++;
        boolean hardSuspect = hardViolations > 0;
        if (aiSuspect && hardSuspect) {
            return "COMBINED";
        } else if (aiSuspect) {
            return "AI";
        } else if (hardSuspect) {
            return "HARD";
        } else {
            return "LEGIT";
        }
    }

    public double getVlBuffer(UUID uuid) {
        return vlBuffer.getOrDefault(uuid, 0.0);
    }

    public double getDamageMultiplier(UUID uuid) {
        double score = playerScores.getOrDefault(uuid, 0.0);
        if (score < 0.65) return 1.0;
        if (score >= 0.95) return 0.25;
        double t = (score - 0.65) / (0.95 - 0.65);
        return 1.0 - t * 0.75;
    }

    public void recordHit(Player attacker, Entity target) {
        dataCollector.recordHit(attacker, target);
        
        if (trainingMode != TrainingMode.OFF) {
            UUID uuid = attacker.getUniqueId();
            PlayerDataCollector.PlayerData playerData = dataCollector.getPlayerData(uuid);
            if (playerData != null && playerData.getTotalHits() % 3 == 0) {
                Boolean isCheatMark = playerMarks.get(uuid);
                Double label = null;

                if (trainingMode == TrainingMode.AUTO) {
                    if (isCheatMark != null) label = isCheatMark ? 1.0 : 0.0;
                } else if (trainingMode == TrainingMode.SOFT) {
                    if (isCheatMark == null || isCheatMark) label = 1.0;
                } else if (trainingMode == TrainingMode.ANSOFT) {
                    if (isCheatMark == null || !isCheatMark) label = 0.0;
                }

                if (label != null) {
                    double[] rawFeatures = dataCollector.extractFeatures(uuid);
                    synchronized (trainingDataset) {
                        trainingDataset.add(new TrainingData(rawFeatures.clone(), label));
                    }
                }
            }
        }
    }

    public PlayerDataCollector getDataCollector() {
        return dataCollector;
    }

    public double trainOnDataset() {
        List<TrainingData> snapshot;
        synchronized (trainingDataset) {
            if (trainingDataset.isEmpty()) return 0.0;
            snapshot = new ArrayList<>(trainingDataset);
        }
        double lastLoss = 0.0;
        for (int epoch = 0; epoch < 150; epoch++) {
            Collections.shuffle(snapshot);
            double epochLoss = 0.0;
            for (TrainingData td : snapshot) {
                double[] norm = normalizeFeatures(td.features);
                network.train(norm, new double[]{ td.label });
                double pred = Math.max(1e-9, Math.min(1 - 1e-9, network.predict(norm)));
                epochLoss += -(td.label * Math.log(pred) + (1 - td.label) * Math.log(1 - pred));
            }
            lastLoss = epochLoss / snapshot.size();
        }
        return lastLoss;
    }

    public void addTrainingData(UUID uuid, boolean isCheat) {
        double[] features = dataCollector.extractFeatures(uuid);
        synchronized (trainingDataset) {
            trainingDataset.add(new TrainingData(features.clone(), isCheat ? 1.0 : 0.0));
        }
        network.train(normalizeFeatures(features), new double[]{ isCheat ? 1.0 : 0.0 });
    }

    public void setTrainingMode(TrainingMode mode) {
        if (mode == TrainingMode.OFF && this.trainingMode != TrainingMode.OFF) {
            clearPlayerMarks();
        }
        this.trainingMode = mode;
    }

    public void setPlayerMark(UUID uuid, boolean isCheat) {
        playerMarks.put(uuid, isCheat);
    }

    public Boolean getPlayerMark(UUID uuid) {
        return playerMarks.get(uuid);
    }

    public void clearPlayerMarks() {
        playerMarks.clear();
    }

    public TrainingMode getTrainingMode() {
        return trainingMode;
    }

    public boolean isTrainingEnabled() {
        return trainingMode != TrainingMode.OFF;
    }

    public void clearPlayerData(UUID uuid) {
        dataCollector.clearPlayerData(uuid);
        playerScores.remove(uuid);
        violationCounts.remove(uuid);
        cleanStreaks.remove(uuid);
        cheatStreaks.remove(uuid);
        vlBuffer.remove(uuid);
    }

    public double getPlayerScore(UUID uuid) {
        return playerScores.getOrDefault(uuid, 0.0);
    }

    public int getViolationCount(UUID uuid) {
        return violationCounts.getOrDefault(uuid, 0);
    }

    public int getDatasetSize() {
        synchronized (trainingDataset) {
            return trainingDataset.size();
        }
    }

    public int getLegitCount() {
        synchronized (trainingDataset) {
            return (int) trainingDataset.stream().filter(d -> d.label == 0.0).count();
        }
    }

    public int getCheatCount() {
        synchronized (trainingDataset) {
            return (int) trainingDataset.stream().filter(d -> d.label == 1.0).count();
        }
    }

    public void saveWeights(File file) throws IOException {
        network.saveWeights(file);
    }

    public boolean loadWeights(File file) throws IOException {
        return network.loadWeights(file);
    }

    public void saveDataset(File file) throws IOException {
        List<TrainingData> snapshot;
        synchronized (trainingDataset) {
            snapshot = new ArrayList<>(trainingDataset);
        }
        if (file.exists()) {
            File backup = new File(file.getParent(), file.getName() + ".backup");
            if (backup.exists()) backup.delete();
            file.renameTo(backup);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create();
        List<TrainingRecordJson> records = new ArrayList<>();
        for (TrainingData td : snapshot) {
            TrainingRecordJson tr = new TrainingRecordJson();
            tr.label = td.label;
            List<Double> seq = new ArrayList<>(td.features.length);
            for (double f : td.features) seq.add(f);
            if (td.label == 0.0) {
                tr.legit_features = seq;
                tr.soft_features = null;
            } else {
                tr.legit_features = null;
                tr.soft_features = seq;
            }
            records.add(tr);
        }

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            gson.toJson(records, writer);
        }
    }

    public void loadDataset(File file) throws IOException {
        if (!file.exists()) return;
        List<TrainingData> loaded = new ArrayList<>();

        com.google.gson.Gson gson = new com.google.gson.Gson();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<List<TrainingRecordJson>>(){}.getType();
            List<TrainingRecordJson> records = gson.fromJson(reader, listType);
            if (records != null) {
                for (TrainingRecordJson record : records) {
                    List<Double> seq = record.legit_features;
                    if (seq == null) {
                        seq = record.soft_features;
                    }
                    if (seq != null && seq.size() == TOTAL_FEATURES) {
                        double[] features = new double[TOTAL_FEATURES];
                        for (int i = 0; i < TOTAL_FEATURES; i++) {
                            features[i] = seq.get(i);
                        }
                        loaded.add(new TrainingData(features, record.label));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        synchronized (trainingDataset) {
            trainingDataset.clear();
            trainingDataset.addAll(loaded);
        }
    }

    private static class TrainingRecordJson {
        double label;
        List<Double> legit_features;
        List<Double> soft_features;
    }

    public static class CheckResult {

        private final String playerName;
        private final double score;
        private final boolean isCheat;
        private final int violations;
        private final int totalHits;
        private final int sessionHits;
        private final String alertType;

        public CheckResult(String playerName, double score, boolean isCheat, int violations, int totalHits, int sessionHits, String alertType) {
            this.playerName = playerName;
            this.score = score;
            this.isCheat = isCheat;
            this.violations = violations;
            this.totalHits = totalHits;
            this.sessionHits = sessionHits;
            this.alertType = alertType;
        }

        public String getPlayerName() { return playerName; }
        public double getScore() { return score; }
        public boolean isCheat() { return !alertType.equals("LEGIT"); }
        public String getAlertType() { return alertType; }
        public int getViolations() { return violations; }
        public int getTotalHits() { return totalHits; }
        public int getSessionHits() { return sessionHits; }

        public String getVerdict() {
            switch (alertType) {
                case "COMBINED": return mrresi.cloudac.utils.LanguageManager.getMessage("checks.verdict_combined");
                case "AI":       return mrresi.cloudac.utils.LanguageManager.getMessage("checks.verdict_ai");
                case "HARD":     return mrresi.cloudac.utils.LanguageManager.getMessage("checks.verdict_hard");
                default:         return mrresi.cloudac.utils.LanguageManager.getMessage("checks.verdict_legit");
            }
        }
    }

    private static class TrainingData implements Serializable {
        private static final long serialVersionUID = 3L;
        final double[] features;
        final double label;

        TrainingData(double[] features, double label) {
            this.features = features;
            this.label = label;
        }
    }
}
