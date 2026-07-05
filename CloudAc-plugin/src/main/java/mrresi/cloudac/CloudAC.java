package mrresi.cloudac;

import mrresi.cloudac.checks.CheckManager;
import mrresi.cloudac.commands.CloudACCommand;
import mrresi.cloudac.listeners.CombatListener;
import mrresi.cloudac.listeners.PacketListener;
import mrresi.cloudac.ml.AntiCheatAI;
import mrresi.cloudac.utils.LanguageManager;
import org.bstats.bukkit.Metrics;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public final class CloudAC extends JavaPlugin {

    private AntiCheatAI ai;
    private PacketListener packetListener;
    private CheckManager checkManager;
    private FileConfiguration punishmentsConfig;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("packetevents") == null && getServer().getPluginManager().getPlugin("PacketEvents") == null) {
            getLogger().severe(LanguageManager.getMessage("logs.pe_not_found"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        LanguageManager.init(this);
        loadPunishmentsConfig();
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        ai = new AntiCheatAI();
        checkManager = new CheckManager(this);
        File datasetFile = new File(getDataFolder(), "dataset.json");
        if (datasetFile.exists()) {
            try {
                ai.loadDataset(datasetFile);
            } catch (IOException e) {
                getLogger().warning(LanguageManager.getMessage("logs.dataset_load_fail", "%error%", e.getMessage()));
            }
        }
        File weightsFile = new File(getDataFolder(), "network.weights");
        boolean weightsLoaded = false;
        try {
            weightsLoaded = ai.loadWeights(weightsFile);
        } catch (IOException e) {
            getLogger().warning(LanguageManager.getMessage("logs.weights_load_fail", "%error%", e.getMessage()));
        }
        if (!weightsLoaded) {
            if (ai.getDatasetSize() >= 20) {
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    ai.trainOnDataset();
                    try {
                        ai.saveWeights(weightsFile);
                    } catch (IOException e) {
                        getLogger().warning(LanguageManager.getMessage("logs.weights_save_fail", "%error%", e.getMessage()));
                    }
                });
            }
        }
        getServer().getPluginManager().registerEvents(new CombatListener(this, ai), this);
        
        Metrics metrics = new Metrics(this, 32381);
        
        getServer().getScheduler().runTaskTimer(this, () -> checkManager.decayAll(), 20L, 20L);
        packetListener = new PacketListener(this, ai.getDataCollector());
        packetListener.register();
        CloudACCommand commandExecutor = new CloudACCommand(this, ai);
        getCommand("cloudac").setExecutor(commandExecutor);
        getCommand("cloudac").setTabCompleter(commandExecutor);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (ai.getDatasetSize() > 0) {
                try {
                    ai.saveDataset(new File(getDataFolder(), "dataset.json"));
                    ai.saveWeights(new File(getDataFolder(), "network.weights"));
                } catch (IOException e) {
                    getLogger().warning(LanguageManager.getMessage("logs.auto_save_fail", "%error%", e.getMessage()));
                }
            }
        }, 1200L, 1200L);
        getLogger().info(LanguageManager.getMessage("logs.enabled"));
    }

    @Override
    public void onDisable() {
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (ai == null) return;
        try {
            File datasetFile = new File(getDataFolder(), "dataset.json");
            ai.saveDataset(datasetFile);
            getLogger().info(LanguageManager.getMessage("logs.dataset_saved", "%size%", String.valueOf(ai.getDatasetSize()), "%file%", datasetFile.getName()));
            getLogger().info(LanguageManager.getMessage("logs.legit_count", "%count%", String.valueOf(ai.getLegitCount())));
            getLogger().info(LanguageManager.getMessage("logs.cheat_count", "%count%", String.valueOf(ai.getCheatCount())));
        } catch (IOException e) {
            getLogger().severe(LanguageManager.getMessage("logs.dataset_save_fail", "%error%", e.getMessage()));
        }
        try {
            File weightsFile = new File(getDataFolder(), "network.weights");
            ai.saveWeights(weightsFile);
            getLogger().info(LanguageManager.getMessage("logs.weights_saved", "%file%", weightsFile.getName()));
        } catch (IOException e) {
            getLogger().severe(LanguageManager.getMessage("logs.weights_save_fail", "%error%", e.getMessage()));
        }
        getLogger().info(LanguageManager.getMessage("logs.disabled"));
    }

    public AntiCheatAI getAI() {
        return ai;
    }

    public PacketListener getPacketListener() {
        return packetListener;
    }

    public CheckManager getCheckManager() {
        return checkManager;
    }

    public FileConfiguration getPunishmentsConfig() {
        return punishmentsConfig;
    }

    public void loadPunishmentsConfig() {
        File file = new File(getDataFolder(), "punishments.yml");
        if (!file.exists()) {
            saveResource("punishments.yml", false);
        }
        punishmentsConfig = YamlConfiguration.loadConfiguration(file);
    }
}
