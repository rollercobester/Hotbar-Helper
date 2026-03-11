package qoby.hotbar_helper;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Config screen for Hotbar Helper, shown when the user clicks the config button
 * in Mod Menu.
 */
public class HotbarHelperConfigScreen extends Screen {

    private final Screen parent;
    private final HotbarHelperConfig config;

    public HotbarHelperConfigScreen(Screen parent) {
        super(Component.literal("Hotbar Helper Config"));
        this.parent = parent;
        this.config = HotbarHelper.getConfig();
    }

    @Override
    protected void init() {
        super.init();

        int left = this.width / 2 - 155;
        int y = 30;
        int spacing = 25;

        addRenderableWidget(
                Checkbox.builder(Component.literal("Refill on block place"), this.font)
                        .pos(left, y)
                        .selected(config.refillOnPlace)
                        .onValueChange((checkbox, value) -> config.refillOnPlace = value)
                        .build());
        y += spacing;

        addRenderableWidget(
                Checkbox.builder(Component.literal("Refill on drop"), this.font)
                        .pos(left, y)
                        .selected(config.refillOnDrop)
                        .onValueChange((checkbox, value) -> config.refillOnDrop = value)
                        .build());
        y += spacing;

        addRenderableWidget(
                Checkbox.builder(Component.literal("Refill on use"), this.font)
                        .pos(left, y)
                        .selected(config.refillOnUse)
                        .onValueChange((checkbox, value) -> config.refillOnUse = value)
                        .build());
        y += spacing;

        addRenderableWidget(
                Checkbox.builder(Component.literal("Auto tool switch when mining"), this.font)
                        .pos(left, y)
                        .selected(config.autoToolSwitch)
                        .onValueChange((checkbox, value) -> config.autoToolSwitch = value)
                        .build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void onClose() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        config.save(configDir);
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
