package qoby.hotbar_helper;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/**
 * Integrates Hotbar Helper with Mod Menu so the config button opens our config
 * screen.
 */
public class HotbarHelperModMenuApi implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return HotbarHelperConfigScreen::new;
    }
}
