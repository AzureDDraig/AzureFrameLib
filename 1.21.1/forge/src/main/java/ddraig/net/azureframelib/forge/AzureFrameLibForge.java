package ddraig.net.azureframelib.forge;

import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.client.AzureFrameLibClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(AzureFrameLib.MOD_ID)
public class AzureFrameLibForge {
    public AzureFrameLibForge() {
        AzureFrameLib.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientRegistrar.register();
        }
    }

    private static final class ClientRegistrar {
        static void register() {
            MinecraftForge.EVENT_BUS.addListener((final RegisterClientCommandsEvent event) ->
                    AzureFrameLibClient.registerBrigadier(
                            event.getDispatcher(),
                            (src, msg) -> src.sendSuccess(() -> msg, false),
                            (src, msg) -> src.sendFailure(msg)
                    )
            );
        }
    }
}
