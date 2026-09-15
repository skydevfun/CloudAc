package mrresi.cloudac.checks;

import mrresi.cloudac.CloudAC;
import mrresi.cloudac.utils.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AlertManager {

    private static final long ALERT_COOLDOWN_MS = 2000L;

    private final CloudAC plugin;
    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> alertFocus = new ConcurrentHashMap<>();
    private final Map<UUID, FocusStats> focusStatsMap = new ConcurrentHashMap<>();

    private static class FocusStats {
        int hits = 0;
        double lastAlertScore = -1.0;
    }

    public AlertManager(CloudAC plugin) {
        this.plugin = plugin;
    }

    public void sendAlert(Player player, Check check, int vl, String detail) {
        String cooldownKey = player.getUniqueId().toString() + ":" + check.getName();
        long now = System.currentTimeMillis();
        Long lastAlert = alertCooldowns.get(cooldownKey);
        if (lastAlert != null && (now - lastAlert) < ALERT_COOLDOWN_MS) {
            return;
        }
        alertCooldowns.put(cooldownKey, now);

        String vlColor;
        if (vl >= 20) {
            vlColor = "§4";
        } else if (vl >= 10) {
            vlColor = "§c";
        } else {
            vlColor = "§e";
        }

        String detailStr = (detail != null && !detail.isEmpty()) ? " §8│ §7" + detail : "";
        String msg = LanguageManager.getMessage("alerts.check-alert",
            "%player%", player.getName(),
            "%check%", check.getDisplayName(),
            "%vl_color%", vlColor,
            "%vl%", String.valueOf(vl),
            "%detail%", detailStr
        );

        sendToStaff(msg, player.getUniqueId());
        checkPunishment(player, check.getName(), vl);
    }

    public void sendCancelAlert(Player player, Check check, int vl, String detail) {
        String cooldownKey = player.getUniqueId().toString() + ":" + check.getName() + ":cancel";
        long now = System.currentTimeMillis();
        Long lastAlert = alertCooldowns.get(cooldownKey);
        if (lastAlert != null && (now - lastAlert) < ALERT_COOLDOWN_MS) {
            return;
        }
        alertCooldowns.put(cooldownKey, now);

        String detailStr = (detail != null && !detail.isEmpty()) ? " §8│ §7" + detail : "";
        String msg = LanguageManager.getMessage("alerts.cancel-alert",
            "%player%", player.getName(),
            "%check%", check.getDisplayName(),
            "%vl%", String.valueOf(vl),
            "%detail%", detailStr
        );

        sendToStaff(msg, player.getUniqueId());
    }

    public void checkPunishment(Player player, String checkName, int vl) {
        String cooldownKey = player.getUniqueId().toString() + ":punishment:" + checkName;
        long now = System.currentTimeMillis();
        Long lastAlert = alertCooldowns.get(cooldownKey);
        if (lastAlert != null && (now - lastAlert) < ALERT_COOLDOWN_MS) {
            return;
        }

        FileConfiguration config = plugin.getPunishmentsConfig();
        if (config == null) return;
        String path = "vl-punishments." + checkName.toLowerCase();
        if (!config.getBoolean(path + ".enabled", false)) return;
        int threshold = config.getInt(path + ".threshold", 100);
        if (vl >= threshold) {
            alertCooldowns.put(cooldownKey, now);
            executeActions(player, config.getStringList(path + ".actions"), config.getString(path + ".kick-message"), config.getStringList(path + ".commands"));
        }
    }

    public void checkAiPunishment(Player player, double confidence) {
        String cooldownKey = player.getUniqueId().toString() + ":ai_punishment";
        long now = System.currentTimeMillis();
        Long lastAlert = alertCooldowns.get(cooldownKey);
        if (lastAlert != null && (now - lastAlert) < ALERT_COOLDOWN_MS) {
            return;
        }
        
        FileConfiguration config = plugin.getPunishmentsConfig();
        if (config == null) return;
        String path = "ai-punishments";
        if (!config.getBoolean(path + ".enabled", false)) return;
        double threshold = config.getDouble(path + ".threshold", 1.0);
        if (confidence >= threshold) {
            alertCooldowns.put(cooldownKey, now);
            executeActions(player, config.getStringList(path + ".actions"), config.getString(path + ".kick-message"), config.getStringList(path + ".commands"));
        }
    }

    private void executeActions(Player player, List<String> actions, String kickMessage, List<String> commands) {
        if (actions == null || actions.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            for (String action : actions) {
                if (action.equalsIgnoreCase("KICK")) {
                    player.kickPlayer(kickMessage != null ? kickMessage.replace("&", "§") : "Kicked by CloudAC");
                } else if (action.equalsIgnoreCase("COMMAND") && commands != null) {
                    for (String cmd : commands) {
                        String formattedCmd = cmd.replace("%player%", player.getName());
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formattedCmd);
                    }
                }
            }
        });
    }

    public void clearCooldowns(UUID uuid) {
        String prefix = uuid.toString() + ":";
        alertCooldowns.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    public void setAlertFocus(UUID staffUuid, UUID targetUuid) {
        alertFocus.put(staffUuid, targetUuid);
    }

    public void clearAlertFocus(UUID staffUuid) {
        alertFocus.remove(staffUuid);
    }

    public UUID getAlertFocus(UUID staffUuid) {
        return alertFocus.get(staffUuid);
    }

    public void handleFocusAlert(Player player, double currentScore) {
        UUID uuid = player.getUniqueId();
        if (!alertFocus.containsValue(uuid)) {
            return;
        }

        FocusStats stats = focusStatsMap.computeIfAbsent(uuid, k -> new FocusStats());
        stats.hits++;

        double lastScore = stats.lastAlertScore;
        boolean scoreChangedSignificant = lastScore >= 0.0 && Math.abs(currentScore - lastScore) >= 0.10;
        boolean hitThresholdReached = stats.hits >= 2;

        if (hitThresholdReached || scoreChangedSignificant) {
            stats.hits = 0;
            stats.lastAlertScore = currentScore;

            double pct = currentScore * 100.0;
            String scoreColor;
            String checkLabel;
            if (pct >= 90) {
                scoreColor = "§4";
                checkLabel = LanguageManager.getMessage("checks.killaura_aimbot");
            } else if (pct >= 80) {
                scoreColor = "§c";
                checkLabel = LanguageManager.getMessage("checks.aimassist_killaura");
            } else if (pct >= 70) {
                scoreColor = "§e";
                checkLabel = LanguageManager.getMessage("checks.aimassist");
            } else {
                scoreColor = "§e";
                checkLabel = LanguageManager.getMessage("checks.ai_suspicion");
            }

            String msg = LanguageManager.getMessage("alerts.ai-alert",
                "%player%", player.getName(),
                "%score_color%", scoreColor,
                "%confidence%", String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", pct),
                "%check%", checkLabel
            );

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("cloudac.alerts")) {
                    UUID focus = alertFocus.get(p.getUniqueId());
                    if (uuid.equals(focus)) {
                        p.sendMessage(msg);
                    }
                }
            }
        }
    }

    public void sendToStaff(String msg, UUID targetUuid) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("cloudac.alerts")) {
                UUID focus = alertFocus.get(p.getUniqueId());
                if (focus != null) {
                    if (focus.equals(targetUuid)) {
                        p.sendMessage(msg);
                    }
                } else {
                    p.sendMessage(msg);
                }
            }
        }
    }
}
