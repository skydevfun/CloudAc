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
    private volatile long lastLoadedModified = 0L;
    private volatile long lastLoadedSize = 0L;
    private volatile boolean datasetDirty = false;
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
                    double interval = playerData.getLastAttackInterval();
                    int ping = 100;
                    try {
                        org.bukkit.plugin.Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin("CloudAC");
                        if (p instanceof mrresi.cloudac.CloudAC) {
                            ping = ((mrresi.cloudac.CloudAC) p).getPacketListener().getPlayerPing(uuid);
                        }
                    } catch (Exception ignored) {}
                    synchronized (trainingDataset) {
                        trainingDataset.add(new TrainingData(rawFeatures.clone(), label, interval, ping));
                        datasetDirty = true;
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
            datasetDirty = true;
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

    public boolean hasExternalFileChanged(File file) {
        if (file == null || !file.exists()) return false;
        long currentMod = file.lastModified();
        long currentLen = file.length();
        return (lastLoadedModified > 0 && Math.abs(currentMod - lastLoadedModified) > 1000L) ||
               (lastLoadedSize > 0 && currentLen != lastLoadedSize);
    }

    public boolean isDatasetModifiedSinceSave() {
        return datasetDirty;
    }

    public void setDatasetClean(File file) {
        this.datasetDirty = false;
        if (file != null && file.exists()) {
            this.lastLoadedModified = file.lastModified();
            this.lastLoadedSize = file.length();
        }
    }

    public void clearDataset() {
        synchronized (trainingDataset) {
            trainingDataset.clear();
            datasetDirty = true;
        }
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

        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write("[\n");
            for (int i = 0; i < snapshot.size(); i++) {
                TrainingData td = snapshot.get(i);
                writer.write("  {\n");
                writer.write("    \"label\": " + td.label + ",\n");
                if (td.attackInterval > 0) {
                    writer.write("    \"attack_interval\": " + String.format(java.util.Locale.US, "%.1f", td.attackInterval) + ",\n");
                }
                if (td.ping > 0) {
                    writer.write("    \"ping\": " + td.ping + ",\n");
                }
                writer.write("    \"features\": [");
                for (int j = 0; j < td.features.length; j++) {
                    writer.write(String.format(java.util.Locale.US, "%.4f", td.features[j]));
                    if (j < td.features.length - 1) writer.write(", ");
                }
                writer.write("]\n");
                writer.write("  }" + (i < snapshot.size() - 1 ? ",\n" : "\n"));
            }
            writer.write("]\n");
        }

        this.lastLoadedModified = file.lastModified();
        this.lastLoadedSize = file.length();
        this.datasetDirty = false;
    }

    public synchronized int loadDataset(File file) throws IOException {
        if (!file.exists()) return 0;
        List<TrainingData> loaded = new ArrayList<>();

        com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
        try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            com.google.gson.JsonElement element = parser.parse(reader);
            parseJsonElement(element, loaded);
        } catch (Exception e) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;
                    try {
                        com.google.gson.JsonElement el = parser.parse(line);
                        parseJsonElement(el, loaded);
                    } catch (Exception ignored) {}
                }
            }
        }

        synchronized (trainingDataset) {
            trainingDataset.clear();
            trainingDataset.addAll(loaded);
        }

        this.lastLoadedModified = file.lastModified();
        this.lastLoadedSize = file.length();
        this.datasetDirty = false;

        return loaded.size();
    }

    private void parseJsonElement(com.google.gson.JsonElement element, List<TrainingData> loaded) {
        if (element == null) return;
        if (element.isJsonArray()) {
            com.google.gson.JsonArray array = element.getAsJsonArray();
            for (com.google.gson.JsonElement item : array) {
                if (item.isJsonObject()) {
                    TrainingData td = parseTrainingDataObj(item.getAsJsonObject());
                    if (td != null) loaded.add(td);
                } else if (item.isJsonArray()) {
                    double[] feats = parseFeaturesArray(item.getAsJsonArray());
                    if (feats != null) {
                        loaded.add(new TrainingData(feats, 0.0));
                    }
                }
            }
        } else if (element.isJsonObject()) {
            com.google.gson.JsonObject obj = element.getAsJsonObject();
            if (obj.has("features") || obj.has("inputs")) {
                TrainingData td = parseTrainingDataObj(obj);
                if (td != null) {
                    loaded.add(td);
                    return;
                }
            }
            if (obj.has("legit") && obj.get("legit").isJsonArray()) {
                com.google.gson.JsonArray legitArray = obj.get("legit").getAsJsonArray();
                for (com.google.gson.JsonElement item : legitArray) {
                    if (item.isJsonArray()) {
                        double[] feats = parseFeaturesArray(item.getAsJsonArray());
                        if (feats != null) loaded.add(new TrainingData(feats, 0.0));
                    }
                }
            }
            if (obj.has("soft") && obj.get("soft").isJsonArray()) {
                com.google.gson.JsonArray softArray = obj.get("soft").getAsJsonArray();
                for (com.google.gson.JsonElement item : softArray) {
                    if (item.isJsonArray()) {
                        double[] feats = parseFeaturesArray(item.getAsJsonArray());
                        if (feats != null) loaded.add(new TrainingData(feats, 1.0));
                    }
                }
            }
            for (String key : new String[]{"samples", "data", "dataset", "records", "entries"}) {
                if (obj.has(key) && obj.get(key).isJsonArray()) {
                    parseJsonElement(obj.get(key), loaded);
                }
            }
        }
    }

    private TrainingData parseTrainingDataObj(com.google.gson.JsonObject obj) {
        double label = 0.0;
        for (String k : new String[]{"label", "target", "class", "is_cheat", "isCheat", "cheat"}) {
            if (obj.has(k) && !obj.get(k).isJsonNull()) {
                com.google.gson.JsonElement el = obj.get(k);
                if (el.isJsonPrimitive()) {
                    com.google.gson.JsonPrimitive prim = el.getAsJsonPrimitive();
                    if (prim.isBoolean()) {
                        label = prim.getAsBoolean() ? 1.0 : 0.0;
                    } else if (prim.isNumber()) {
                        label = prim.getAsDouble() > 0.5 ? 1.0 : 0.0;
                    } else {
                        String s = prim.getAsString().toLowerCase();
                        label = (s.contains("cheat") || s.contains("soft") || s.equals("1") || s.equals("true")) ? 1.0 : 0.0;
                    }
                }
                break;
            }
        }

        com.google.gson.JsonArray featArray = null;
        for (String k : new String[]{"features", "legit_features", "soft_features", "data", "inputs", "rotations"}) {
            if (obj.has(k) && !obj.get(k).isJsonNull() && obj.get(k).isJsonArray()) {
                featArray = obj.get(k).getAsJsonArray();
                break;
            }
        }

        if (featArray == null) return null;
        double[] feats = parseFeaturesArray(featArray);
        if (feats == null) return null;

        double attackInterval = 0.0;
        for (String k : new String[]{"attack_interval", "interval", "attackInterval"}) {
            if (obj.has(k) && obj.get(k).isJsonPrimitive() && obj.get(k).getAsJsonPrimitive().isNumber()) {
                attackInterval = obj.get(k).getAsDouble();
                break;
            }
        }

        int ping = 0;
        if (obj.has("ping") && obj.get("ping").isJsonPrimitive() && obj.get("ping").getAsJsonPrimitive().isNumber()) {
            ping = obj.get("ping").getAsInt();
        }

        return new TrainingData(feats, label, attackInterval, ping);
    }

    private double[] parseFeaturesArray(com.google.gson.JsonArray array) {
        if (array.size() == 0) return null;
        double[] feats = new double[TOTAL_FEATURES];
        int idx = 0;

        if (array.get(0).isJsonArray()) {
            for (com.google.gson.JsonElement tickEl : array) {
                if (tickEl.isJsonArray()) {
                    for (com.google.gson.JsonElement numEl : tickEl.getAsJsonArray()) {
                        if (idx < TOTAL_FEATURES && numEl.isJsonPrimitive() && numEl.getAsJsonPrimitive().isNumber()) {
                            feats[idx++] = numEl.getAsDouble();
                        }
                    }
                }
            }
        } else {
            int size = array.size();
            if (size <= TOTAL_FEATURES) {
                int pad = TOTAL_FEATURES - size;
                for (int i = 0; i < size; i++) {
                    com.google.gson.JsonElement numEl = array.get(i);
                    if (numEl.isJsonPrimitive() && numEl.getAsJsonPrimitive().isNumber()) {
                        feats[pad + i] = numEl.getAsDouble();
                    }
                }
                idx = TOTAL_FEATURES;
            } else {
                int offset = size - TOTAL_FEATURES;
                for (int i = 0; i < TOTAL_FEATURES; i++) {
                    com.google.gson.JsonElement numEl = array.get(offset + i);
                    if (numEl.isJsonPrimitive() && numEl.getAsJsonPrimitive().isNumber()) {
                        feats[i] = numEl.getAsDouble();
                    }
                }
                idx = TOTAL_FEATURES;
            }
        }

        return idx > 0 ? feats : null;
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

    public static class TrainingData implements Serializable {
        private static final long serialVersionUID = 3L;
        public final double[] features;
        public final double label;
        public double attackInterval;
        public int ping;

        public TrainingData(double[] features, double label) {
            this(features, label, 0.0, 0);
        }

        public TrainingData(double[] features, double label, double attackInterval, int ping) {
            this.features = features;
            this.label = label;
            this.attackInterval = attackInterval;
            this.ping = ping;
        }
    }
}
