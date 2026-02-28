package qoby.hotbar_helper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class HotbarHelperClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        HotbarHelperConfig config = HotbarHelperConfig.load(configDir);
        HotbarHelper.setConfig(config);
        HotbarRefill.refillHandler = new HotbarRefillClient();
        HotbarHelperEvents.register(config);
        HotbarToolSwitch.register(config);
    }
}
