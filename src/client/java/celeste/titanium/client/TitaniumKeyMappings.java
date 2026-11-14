package celeste.titanium.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

final class TitaniumKeyMappings {
    // 既定キーは未割り当てとし、Minecraftのキー設定から利用者が選択できるよう登録する。
    static final KeyMapping OPEN_CONFIG = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.titanium.open_config", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("titanium", "main"))));

    private TitaniumKeyMappings() {}

    /**
     * クライアントtickへキー入力処理を登録し、他の画面が開かれていない場合に設定画面を開く。
     */
    static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Consume queued repeats too; do not interrupt chat, inventories or key assignment.
            while (OPEN_CONFIG.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(TitaniumConfigScreen.create(null));
                }
            }
        });
    }
}
