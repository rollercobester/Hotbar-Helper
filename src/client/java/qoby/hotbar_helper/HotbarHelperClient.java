package qoby.hotbar_helper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import qoby.hotbar_helper.network.EnchantBlocksSyncPayload;

import java.nio.file.Path;

public class HotbarHelperClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Payload type already registered by HotbarHelper (main, env *)
        ClientPlayNetworking.registerGlobalReceiver(EnchantBlocksSyncPayload.TYPE, (payload, context) -> {
            var registryAccess = context.client().getConnection().registryAccess();
            EnchantBlockRegistry.applySyncedBlocks(registryAccess, payload.fortuneBlocks(), payload.silkTouchBlocks());
        });

        Path configDir = FabricLoader.getInstance().getConfigDir();
        HotbarHelperConfig config = HotbarHelperConfig.load(configDir);
        HotbarHelper.setConfig(config);
        HotbarRefill.refillHandler = new HotbarRefillClient();
        HotbarHelperEvents.register(config);
        HotbarToolSwitch.register(config);
        HotbarHelperKeybinds.register();

        ClientPlayNetworking.registerGlobalReceiver(EnchantBlocksSyncPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                var registryAccess = context.player().registryAccess();
                EnchantBlockRegistry.applySyncedBlocks(registryAccess, payload.fortuneBlocks(), payload.silkTouchBlocks());
            });
        });
    }
}
