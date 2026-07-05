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

        sendToStaff(msg);
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

        sendToStaff(msg);
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

    private void sendToStaff(String msg) {
        Bukkit.broadcast(msg, "cloudac.alerts");
    }
}
