package celeste.titanium.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menuが存在する実クライアントだけへCloth Config画面を公開する。 */
public final class TitaniumModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TitaniumConfigScreen::create;
    }
}
