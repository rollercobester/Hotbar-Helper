package qoby.hotbar_helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for Hotbar Helper's auto-refill feature.
 * Each method of emptying a hotbar slot can be individually enabled or disabled.
 */
public class HotbarHelperConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "hotbar-helper.json";

    public boolean refillOnPlace = true;
    public boolean refillOnDrop = true;
    public boolean refillOnUse = true;
    public boolean autoToolSwitch = true;

    public static HotbarHelperConfig load(Path configDir) {
        Path configPath = configDir.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(configPath)) {
            HotbarHelperConfig config = new HotbarHelperConfig();
            config.save(configDir);
            return config;
        }
        try {
            String json = Files.readString(configPath);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            HotbarHelperConfig config = new HotbarHelperConfig();
            if (obj.has("refillOnPlace")) config.refillOnPlace = obj.get("refillOnPlace").getAsBoolean();
            else if (obj.has("replaceOnPlace")) config.refillOnPlace = obj.get("replaceOnPlace").getAsBoolean();
            if (obj.has("refillOnDrop")) config.refillOnDrop = obj.get("refillOnDrop").getAsBoolean();
            else if (obj.has("replaceOnDrop")) config.refillOnDrop = obj.get("replaceOnDrop").getAsBoolean();
            if (obj.has("refillOnUse")) config.refillOnUse = obj.get("refillOnUse").getAsBoolean();
            else if (obj.has("refillOnEat")) config.refillOnUse = obj.get("refillOnEat").getAsBoolean();
            else if (obj.has("refillOnThrow")) config.refillOnUse = obj.get("refillOnThrow").getAsBoolean();
            else if (obj.has("replaceOnThrow")) config.refillOnUse = obj.get("replaceOnThrow").getAsBoolean();
            if (obj.has("autoToolSwitch")) config.autoToolSwitch = obj.get("autoToolSwitch").getAsBoolean();
            return config;
        } catch (IOException e) {
            HotbarHelper.LOGGER.warn("Failed to load config, using defaults", e);
            return new HotbarHelperConfig();
        }
    }

    public void save(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path configPath = configDir.resolve(CONFIG_FILE_NAME);
            JsonObject obj = new JsonObject();
            obj.addProperty("refillOnPlace", refillOnPlace);
            obj.addProperty("refillOnDrop", refillOnDrop);
            obj.addProperty("refillOnUse", refillOnUse);
            obj.addProperty("autoToolSwitch", autoToolSwitch);
            Files.writeString(configPath, GSON.toJson(obj));
        } catch (IOException e) {
            HotbarHelper.LOGGER.error("Failed to save config", e);
        }
    }
}
