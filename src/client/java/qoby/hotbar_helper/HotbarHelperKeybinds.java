package qoby.hotbar_helper;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Registers and handles keybinds for Hotbar Helper.
 */
public final class HotbarHelperKeybinds {

    private static final String CATEGORY = "key.categories.hotbar_helper";

    public static final KeyMapping TOGGLE_AUTO_TOOL_SWITCH = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.hotbar_helper.toggle_auto_tool_switch",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_H,
                    CATEGORY));

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_AUTO_TOOL_SWITCH.consumeClick()) {
                var config = HotbarHelper.getConfig();
                if (config == null)
                    return;
                config.autoToolSwitch = !config.autoToolSwitch;
                config.save(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

                if (client.player == null)
                    return;
                String status = config.autoToolSwitch ? "§aON" : "§cOFF";
                client.player.displayClientMessage(
                        Component.literal("Auto tool switch: " + status),
                        true);
            }
        });
    }
}
