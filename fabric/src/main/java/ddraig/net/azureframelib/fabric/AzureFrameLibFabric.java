package ddraig.net.azureframelib.fabric;

import ddraig.net.azureframelib.AzureFrameLib;
import net.fabricmc.api.ModInitializer;

public class AzureFrameLibFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AzureFrameLib.init();
    }
}
