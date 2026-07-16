package mrresi.cloudac.checks;

import mrresi.cloudac.CloudAC;
import mrresi.cloudac.checks.combat.KillAuraCheck;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class CheckManager {

    private final CloudAC plugin;
    private final AlertManager alertManager;
    private final Map<String, Check> checks = new LinkedHashMap<>();
    private KillAuraCheck killAuraCheck;

    public CheckManager(CloudAC plugin) {
        this.plugin = plugin;
        this.alertManager = new AlertManager(plugin);
        registerChecks();
    }

    private void registerChecks() {
        killAuraCheck = new KillAuraCheck(plugin, alertManager);
        checks.put(killAuraCheck.getName(), killAuraCheck);
    }

    public void decayAll() {
        for (Check check : checks.values()) {
            check.decayAllVL();
        }
    }

    public void removePlayer(UUID uuid) {
        alertManager.clearCooldowns(uuid);
        for (Check check : checks.values()) {
            check.resetVL(uuid);
        }
    }

    public KillAuraCheck getKillAuraCheck() { return killAuraCheck; }

    public AlertManager getAlertManager() { return alertManager; }

    public Check getCheck(String name) {
        return checks.get(name);
    }

    public Map<String, Check> getAllChecks() {
        return checks;
    }
}
