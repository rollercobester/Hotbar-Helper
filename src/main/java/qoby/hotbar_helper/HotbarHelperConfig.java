package qoby.hotbar_helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Configuration for Hotbar Helper's auto-refill feature.
 * Each method of emptying a hotbar slot can be individually enabled or disabled.
 * {@link #refillWhitelist} limits which items may refill. A blank list refills every item.
 */
public class HotbarHelperConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "hotbar-helper.json";

    public boolean refillOnPlace = true;
    public boolean refillOnDrop = true;
    public boolean refillOnUse = true;
    public boolean refillSound = true;
    public boolean autoToolSwitch = true;
    public boolean preserveSlotAfterMining = true;
    /** Comma, semicolon, or whitespace separated item ids. Blank allows every item. */
    public String refillWhitelist = "";

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
            else if (obj.has("replaceOnEat")) config.refillOnUse = obj.get("replaceOnEat").getAsBoolean();
            else if (obj.has("refillOnThrow")) config.refillOnUse = obj.get("refillOnThrow").getAsBoolean();
            else if (obj.has("replaceOnThrow")) config.refillOnUse = obj.get("replaceOnThrow").getAsBoolean();
            if (obj.has("autoToolSwitch")) config.autoToolSwitch = obj.get("autoToolSwitch").getAsBoolean();
            if (obj.has("refillSound")) config.refillSound = obj.get("refillSound").getAsBoolean();
            if (obj.has("preserveSlotAfterMining")) config.preserveSlotAfterMining = obj.get("preserveSlotAfterMining").getAsBoolean();
            if (obj.has("refillWhitelist")) config.refillWhitelist = whitelistFromJson(obj.get("refillWhitelist"));
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
            obj.addProperty("refillSound", refillSound);
            obj.addProperty("autoToolSwitch", autoToolSwitch);
            obj.addProperty("preserveSlotAfterMining", preserveSlotAfterMining);
            obj.addProperty("refillWhitelist", refillWhitelist == null ? "" : refillWhitelist);
            Files.writeString(configPath, GSON.toJson(obj));
        } catch (IOException e) {
            HotbarHelper.LOGGER.error("Failed to save config", e);
        }
    }

    public void setRefillWhitelist(String text) {
        refillWhitelist = text == null ? "" : text;
    }

    /**
     * @return {@code true} when this item may be pulled into the hotbar.
     *         A blank whitelist allows every item.
     */
    public boolean allowsRefill(Item item) {
        if (item == null) return false;
        String text = refillWhitelist;
        if (text == null || text.isBlank()) return true;

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return false;

        boolean sawToken = false;
        for (String part : text.split("[,;\\s]+")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            sawToken = true;
            ResourceLocation id = parseItemId(token);
            if (itemId.equals(id)) return true;
        }
        return !sawToken;
    }

    private static ResourceLocation parseItemId(String token) {
        if (token.indexOf(':') < 0) {
            token = "minecraft:" + token;
        }
        return ResourceLocation.tryParse(token);
    }

    private static String whitelistFromJson(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonElement entry : element.getAsJsonArray()) {
                if (!entry.isJsonPrimitive()) continue;
                String token = entry.getAsString().trim();
                if (token.isEmpty()) continue;
                if (joined.length() > 0) joined.append(", ");
                joined.append(token);
            }
            return joined.toString();
        }
        if (element.isJsonPrimitive()) return element.getAsString();
        return "";
    }
}
