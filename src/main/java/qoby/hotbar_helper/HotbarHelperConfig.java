package qoby.hotbar_helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Configuration for Hotbar Helper's auto-refill feature.
 * Each method of emptying a hotbar slot can be individually enabled or disabled.
 * The refill filter is inactive while its item list is empty.
 * In whitelist mode only listed items refill. In blacklist mode listed items do not.
 */
public class HotbarHelperConfig {
    public enum RefillFilterMode {
        WHITELIST("Whitelist"),
        BLACKLIST("Blacklist");

        private final String label;

        RefillFilterMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static RefillFilterMode fromString(String value) {
            return value != null && value.equalsIgnoreCase("blacklist") ? BLACKLIST : WHITELIST;
        }
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "hotbar-helper.json";

    public boolean refillOnPlace = true;
    public boolean refillOnEntityPlace = true;
    public boolean refillOnDrop = true;
    public boolean refillOnUse = true;
    public boolean refillSound = true;
    public boolean autoToolSwitch = true;
    public boolean preserveSlotAfterMining = true;
    /** Item ids included in the refill filter. Empty means the filter is inactive. */
    private final List<String> refillWhitelist = new CopyOnWriteArrayList<>();
    private RefillFilterMode refillFilterMode = RefillFilterMode.WHITELIST;

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
            if (obj.has("refillOnEntityPlace")) config.refillOnEntityPlace = obj.get("refillOnEntityPlace").getAsBoolean();
            if (obj.has("refillOnDrop")) config.refillOnDrop = obj.get("refillOnDrop").getAsBoolean();
            else if (obj.has("replaceOnDrop")) config.refillOnDrop = obj.get("replaceOnDrop").getAsBoolean();
            if (obj.has("refillOnUse")) config.refillOnUse = obj.get("refillOnUse").getAsBoolean();
            else if (obj.has("refillOnEat")) config.refillOnUse = obj.get("refillOnEat").getAsBoolean();
            else if (obj.has("refillOnThrow")) config.refillOnUse = obj.get("refillOnThrow").getAsBoolean();
            else if (obj.has("replaceOnThrow")) config.refillOnUse = obj.get("replaceOnThrow").getAsBoolean();
            if (obj.has("autoToolSwitch")) config.autoToolSwitch = obj.get("autoToolSwitch").getAsBoolean();
            if (obj.has("refillSound")) config.refillSound = obj.get("refillSound").getAsBoolean();
            if (obj.has("preserveSlotAfterMining")) config.preserveSlotAfterMining = obj.get("preserveSlotAfterMining").getAsBoolean();
            if (obj.has("refillWhitelist")) config.readWhitelist(obj.get("refillWhitelist"));
            if (obj.has("refillFilterMode")) config.refillFilterMode = RefillFilterMode.fromString(obj.get("refillFilterMode").getAsString());
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
            obj.addProperty("refillOnEntityPlace", refillOnEntityPlace);
            obj.addProperty("refillOnDrop", refillOnDrop);
            obj.addProperty("refillOnUse", refillOnUse);
            obj.addProperty("refillSound", refillSound);
            obj.addProperty("autoToolSwitch", autoToolSwitch);
            obj.addProperty("preserveSlotAfterMining", preserveSlotAfterMining);
            JsonArray whitelist = new JsonArray();
            for (String id : refillWhitelist) whitelist.add(id);
            obj.add("refillWhitelist", whitelist);
            obj.addProperty("refillFilterMode", refillFilterMode == RefillFilterMode.BLACKLIST ? "blacklist" : "whitelist");
            Files.writeString(configPath, GSON.toJson(obj));
        } catch (IOException e) {
            HotbarHelper.LOGGER.error("Failed to save config", e);
        }
    }

    public RefillFilterMode refillFilterMode() {
        return refillFilterMode;
    }

    public void setRefillFilterMode(RefillFilterMode mode) {
        refillFilterMode = mode == null ? RefillFilterMode.WHITELIST : mode;
    }

    public String refillFilterSummary() {
        int count = refillWhitelist.size();
        if (count == 0) return "Item refill filter (inactive)";
        String word = refillFilterMode == RefillFilterMode.BLACKLIST ? "blacklisted" : "whitelisted";
        return "Item refill filter (" + count + " " + word + ")";
    }

    public int refillWhitelistSize() {
        return refillWhitelist.size();
    }

    public List<String> refillWhitelistIds() {
        return List.copyOf(refillWhitelist);
    }

    public boolean addRefillWhitelistId(String token) {
        ResourceLocation id = parseItemId(token);
        if (id == null) return false;
        String key = id.toString();
        if (refillWhitelist.contains(key)) return false;
        refillWhitelist.add(key);
        return true;
    }

    public void removeRefillWhitelistId(String token) {
        ResourceLocation id = parseItemId(token == null ? "" : token);
        if (id != null) refillWhitelist.remove(id.toString());
        else if (token != null) refillWhitelist.remove(token);
    }

    /**
     * @return {@code true} when this item may be pulled into the hotbar.
     *         An empty filter allows every item. Whitelist mode allows only listed items.
     *         Blacklist mode allows every item except the listed ones.
     */
    public boolean allowsRefill(Item item) {
        if (item == null) return false;
        if (refillWhitelist.isEmpty()) return true;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return false;
        boolean listed = refillWhitelist.contains(itemId.toString());
        return refillFilterMode == RefillFilterMode.BLACKLIST ? !listed : listed;
    }

    private void readWhitelist(JsonElement element) {
        refillWhitelist.clear();
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) addRefillWhitelistId(entry.getAsString());
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            for (String part : element.getAsString().split("[,;\\s]+")) {
                addRefillWhitelistId(part);
            }
        }
    }

    private static ResourceLocation parseItemId(String token) {
        if (token == null) return null;
        token = token.trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) return null;
        if (token.indexOf(':') < 0) token = "minecraft:" + token;
        return ResourceLocation.tryParse(token);
    }
}
