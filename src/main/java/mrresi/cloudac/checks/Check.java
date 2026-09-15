package mrresi.cloudac.checks;

import mrresi.cloudac.CloudAC;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Check {

    protected final String name;
    protected final String displayName;
    protected final CloudAC plugin;
    protected final AlertManager alertManager;
    protected final Map<UUID, Integer> violations = new ConcurrentHashMap<>();
    protected final int alertThreshold;
    protected final int maxVL;

    public Check(String name, String displayName, CloudAC plugin, AlertManager alertManager, int alertThreshold, int maxVL) {
        this.name = name;
        this.displayName = displayName;
        this.plugin = plugin;
        this.alertManager = alertManager;
        this.alertThreshold = alertThreshold;
        this.maxVL = maxVL;
    }

    protected void flag(Player player, int amount, String detail) {
        UUID uuid = player.getUniqueId();
        int current = violations.getOrDefault(uuid, 0);
        int newVL = Math.min(current + amount, maxVL);
        violations.put(uuid, newVL);
        if (newVL >= alertThreshold) {
            alertManager.sendAlert(player, this, newVL, detail);
        }
    }

    public int getVL(UUID uuid) {
        return violations.getOrDefault(uuid, 0);
    }

    public void decayVL(UUID uuid) {
        int current = violations.getOrDefault(uuid, 0);
        if (current > 0) {
            violations.put(uuid, current - 1);
        }
    }

    public void decayAllVL() {
        violations.replaceAll((uuid, vl) -> Math.max(0, vl - 1));
        violations.values().removeIf(vl -> vl <= 0);
    }

    public void resetVL(UUID uuid) {
        violations.remove(uuid);
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }
}
