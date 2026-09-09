package ddraig.net.azureframelib;

import ddraig.net.azureframelib.resource.AzureResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AzureFrameLib {
    public static final String MOD_ID = "azureframelib";
    public static final Logger LOGGER = LoggerFactory.getLogger("AzureFrameLib");

    public static void init() {
        LOGGER.info("[AzureFrameLib] Initializing shared framework library...");
        AzureResourceManager.init();
        LOGGER.info("[AzureFrameLib] Shared resource system initialized successfully.");
    }
}
