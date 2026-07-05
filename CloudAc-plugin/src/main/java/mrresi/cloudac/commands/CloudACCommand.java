package mrresi.cloudac.commands;

import mrresi.cloudac.CloudAC;
import mrresi.cloudac.ml.AntiCheatAI;
import mrresi.cloudac.utils.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CloudACCommand implements CommandExecutor, TabCompleter {

    private final CloudAC plugin;
    private final AntiCheatAI ai;

    public CloudACCommand(CloudAC plugin, AntiCheatAI ai) {
        this.plugin = plugin;
        this.ai = ai;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cloudac.admin")) {
            sender.sendMessage(LanguageManager.getMessage("commands.no_permission"));
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "training":
                if (args.length < 2) {
                    return true;
                }
                handleTraining(sender, args[1]);
                break;
            case "status":
                handleStatus(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "check":
                if (args.length < 2) {
                    return true;
                }
                handleCheck(sender, args[1]);
                break;
            case "mark":
                if (args.length < 3) {
                    return true;
                }
                handleMark(sender, args[1], args[2]);
                break;
            case "save":
                handleSave(sender);
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleTraining(CommandSender sender, String mode) {
        AntiCheatAI.TrainingMode newMode;
        switch (mode.toLowerCase()) {
            case "soft":
                newMode = AntiCheatAI.TrainingMode.SOFT;
                Bukkit.broadcastMessage(LanguageManager.getMessage("training.soft"));
                break;
            case "ansoft":
                newMode = AntiCheatAI.TrainingMode.ANSOFT;
                Bukkit.broadcastMessage(LanguageManager.getMessage("training.ansoft"));
                break;
            case "auto":
                newMode = AntiCheatAI.TrainingMode.AUTO;
                Bukkit.broadcastMessage(LanguageManager.getMessage("training.auto"));
                break;
            case "off":
                newMode = AntiCheatAI.TrainingMode.OFF;
                ai.setTrainingMode(newMode);
                sender.sendMessage(LanguageManager.getMessage("training.stopped"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        try {
                            File jsonFile = new File(plugin.getDataFolder(), "dataset.json");
                            ai.saveDataset(jsonFile);
                        } catch (Throwable e) {
                            plugin.getLogger().warning("Failed to save dataset (JSON): " + e.toString());
                            e.printStackTrace();
                        }
                        
                        double loss = ai.trainOnDataset();
                        File weightsFile = new File(plugin.getDataFolder(), "network.weights");
                        ai.saveWeights(weightsFile);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(LanguageManager.getMessage("training.finished", "%loss%", String.valueOf(loss), "%dataset%", String.valueOf(ai.getDatasetSize())));
                            sender.sendMessage(LanguageManager.getMessage("training.legit_count", "%count%", String.valueOf(ai.getLegitCount())));
                            sender.sendMessage(LanguageManager.getMessage("training.cheat_count", "%count%", String.valueOf(ai.getCheatCount())));
                            sender.sendMessage(LanguageManager.getMessage("training.weights_saved", "%file%", weightsFile.getName()));
                            Bukkit.broadcastMessage(LanguageManager.getMessage("training.ready"));
                        });
                    } catch (Throwable t) {
                        plugin.getLogger().severe("Critical error during training: " + t.toString());
                        t.printStackTrace();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(LanguageManager.getMessage("training.error"));
                        });
                    }
                });
                return;
            default:
                return;
        }
        ai.setTrainingMode(newMode);
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(LanguageManager.getMessage("commands.status_header"));
        String trainingStatus = ai.getTrainingMode() == AntiCheatAI.TrainingMode.OFF ? LanguageManager.getMessage("commands.mode_off") :
            (ai.getTrainingMode() == AntiCheatAI.TrainingMode.SOFT ? LanguageManager.getMessage("commands.mode_soft") : 
            (ai.getTrainingMode() == AntiCheatAI.TrainingMode.AUTO ? LanguageManager.getMessage("commands.mode_auto") : LanguageManager.getMessage("commands.mode_ansoft")));
        sender.sendMessage(LanguageManager.getMessage("commands.status_mode", "%mode%", trainingStatus));
        sender.sendMessage(LanguageManager.getMessage("commands.status_dataset", "%size%", String.valueOf(ai.getDatasetSize())));
        sender.sendMessage(LanguageManager.getMessage("training.legit_count", "%count%", String.valueOf(ai.getLegitCount())));
        sender.sendMessage(LanguageManager.getMessage("training.cheat_count", "%count%", String.valueOf(ai.getCheatCount())));
        File weightsFile = new File(plugin.getDataFolder(), "network.weights");
        sender.sendMessage(LanguageManager.getMessage("commands.status_weights", "%size%", (weightsFile.exists() ?
            String.format("%.1f KB", weightsFile.length() / 1024.0) : LanguageManager.getMessage("commands.weights_not_found"))));
        sender.sendMessage(LanguageManager.getMessage("commands.status_online", "%count%", String.valueOf(plugin.getServer().getOnlinePlayers().size())));
        sender.sendMessage(LanguageManager.getMessage("commands.status_footer"));
    }

    private void handleReload(CommandSender sender) {
        sender.sendMessage(LanguageManager.getMessage("commands.reload_start"));
        plugin.reloadConfig();
        plugin.loadPunishmentsConfig();
        LanguageManager.init(plugin);

        File weightsFile = new File(plugin.getDataFolder(), "network.weights");
        try {
            boolean weightsLoaded = ai.loadWeights(weightsFile);
            if (weightsLoaded) {
                sender.sendMessage(LanguageManager.getMessage("commands.reload_weights_success"));
            } else {
                sender.sendMessage(LanguageManager.getMessage("commands.reload_weights_default"));
            }
        } catch (IOException e) {
            sender.sendMessage(LanguageManager.getMessage("commands.reload_weights_error", "%error%", e.getMessage()));
        }
        sender.sendMessage(LanguageManager.getMessage("commands.reload_success"));
    }

    private void handleSave(CommandSender sender) {
        sender.sendMessage(LanguageManager.getMessage("commands.save_start"));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File jsonFile = new File(plugin.getDataFolder(), "dataset.json");
                ai.saveDataset(jsonFile);
                int datasetSize = ai.getDatasetSize();
                int legitCount = ai.getLegitCount();
                int cheatCount = ai.getCheatCount();
                long fileSize = jsonFile.length();
                String filePath = jsonFile.getAbsolutePath();
                String fileName = jsonFile.getName();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(LanguageManager.getMessage("commands.save_success", "%size%", String.valueOf(datasetSize)));
                    sender.sendMessage(LanguageManager.getMessage("training.legit_count", "%count%", String.valueOf(legitCount)));
                    sender.sendMessage(LanguageManager.getMessage("training.cheat_count", "%count%", String.valueOf(cheatCount)));
                    sender.sendMessage(LanguageManager.getMessage("commands.save_file", "%file%", fileName));
                    sender.sendMessage(LanguageManager.getMessage("commands.save_size", "%size%", String.format("%.1f KB", fileSize / 1024.0)));
                    sender.sendMessage(LanguageManager.getMessage("commands.save_path", "%path%", filePath));
                });
            } catch (IOException e) {
                String errorMsg = e.getMessage();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(LanguageManager.getMessage("commands.save_error", "%error%", errorMsg));
                });
            }
        });
    }

    private void handleCheck(CommandSender sender, String playerName) {
        Player target = plugin.getServer().getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(LanguageManager.getMessage("commands.not_found"));
            return;
        }
        AntiCheatAI.CheckResult result = ai.analyzePlayer(target);
        sender.sendMessage(LanguageManager.getMessage("commands.status_header"));
        sender.sendMessage(LanguageManager.getMessage("commands.check_player", "%player%", result.getPlayerName()));
        sender.sendMessage(LanguageManager.getMessage("commands.check_status", "%status%", result.getVerdict()));
        sender.sendMessage(LanguageManager.getMessage("commands.check_confidence", "%confidence%", String.format("%.1f", result.getScore() * 100)));
        sender.sendMessage(LanguageManager.getMessage("commands.check_violations", "%violations%", String.valueOf(result.getViolations())));
        sender.sendMessage(LanguageManager.getMessage("commands.status_footer"));
    }

    private void handleMark(CommandSender sender, String playerName, String type) {
        Player target = plugin.getServer().getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(LanguageManager.getMessage("commands.not_found"));
            return;
        }
        if (!ai.isTrainingEnabled()) {
            sender.sendMessage(LanguageManager.getMessage("commands.mark_not_enabled"));
            return;
        }
        boolean isCheat;
        if (type.equalsIgnoreCase("cheat")) {
            isCheat = true;
        } else if (type.equalsIgnoreCase("legit")) {
            isCheat = false;
        } else {
            return;
        }
        ai.setPlayerMark(target.getUniqueId(), isCheat);
        String status = isCheat ? LanguageManager.getMessage("commands.mark_cheat") : LanguageManager.getMessage("commands.mark_legit");
        sender.sendMessage(LanguageManager.getMessage("commands.mark_marked", "%player%", target.getName(), "%status%", status));
        sender.sendMessage(LanguageManager.getMessage("commands.mark_info"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(LanguageManager.getMessage("commands.status_header"));
        sender.sendMessage(LanguageManager.getMessage("commands.help_training"));
        sender.sendMessage(LanguageManager.getMessage("commands.help_mark"));
        sender.sendMessage(LanguageManager.getMessage("commands.help_status"));
        sender.sendMessage(LanguageManager.getMessage("commands.help_check"));
        sender.sendMessage(LanguageManager.getMessage("commands.help_save"));
        sender.sendMessage(LanguageManager.getMessage("commands.help_reload"));
        sender.sendMessage(LanguageManager.getMessage("commands.status_footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("cloudac.admin")) {
            return completions;
        }
        if (args.length == 1) {
            List<String> subcommands = Arrays.asList("training", "status", "reload", "check", "mark", "save");
            String input = args[0].toLowerCase();
            for (String sub : subcommands) {
                if (sub.startsWith(input)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("training")) {
                List<String> modes = Arrays.asList("soft", "ansoft", "auto", "off");
                String input = args[1].toLowerCase();
                for (String m : modes) {
                    if (m.startsWith(input)) {
                        completions.add(m);
                    }
                }
            } else if (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("mark")) {
                String input = args[1].toLowerCase();
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(input)) {
                        completions.add(player.getName());
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("mark")) {
            List<String> types = Arrays.asList("legit", "cheat");
            String input = args[2].toLowerCase();
            for (String t : types) {
                if (t.startsWith(input)) {
                    completions.add(t);
                }
            }
        }
        return completions;
    }
}
