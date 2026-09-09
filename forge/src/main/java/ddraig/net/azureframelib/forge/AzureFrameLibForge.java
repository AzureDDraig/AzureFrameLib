package ddraig.net.azureframelib.forge;

import ddraig.net.azureframelib.AzureFrameLib;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AzureFrameLib.MOD_ID)
public class AzureFrameLibForge {
    public AzureFrameLibForge() {
        EventBuses.registerModEventBus(AzureFrameLib.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        AzureFrameLib.init();
    }
}
