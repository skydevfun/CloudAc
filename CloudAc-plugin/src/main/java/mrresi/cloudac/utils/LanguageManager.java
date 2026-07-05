package mrresi.cloudac.utils;

import mrresi.cloudac.CloudAC;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LanguageManager {

    private static FileConfiguration messages;
    private static String prefix;

    public static void init(CloudAC plugin) {
        String lang = plugin.getConfig().getString("language", "ru").toLowerCase();
        String fileName = "messages_" + lang + ".yml";
        File file = new File(plugin.getDataFolder(), fileName);

        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        messages = YamlConfiguration.loadConfiguration(file);

        InputStream defaultStream = plugin.getResource(fileName);
        if (defaultStream != null) {
            YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaultMessages);
        }

        prefix = messages.getString("prefix", "").replace("&", "§");
    }

    public static String getMessage(String path, Object... replacements) {
        if (messages == null) return "";
        String msg = messages.getString(path);
        if (msg == null) return path;

        msg = msg.replace("%prefix%", prefix);
        msg = msg.replace("&", "§");

        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i].toString(), replacements[i + 1].toString());
        }

        return msg;
    }
}
