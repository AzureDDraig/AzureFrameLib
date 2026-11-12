package ddraig.net.azureframelib;

import ddraig.net.azureframelib.resource.AzureResourceManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AzureFrameLib {
    public static final String MOD_ID = "azureframelib";
    public static final Logger LOGGER = LoggerFactory.getLogger("AzureFrameLib");

    public static void init() {
        LOGGER.info("[AzureFrameLib] Initializing shared framework library...");
        AzureResourceManager.init();
        // Client-only extras (debug command). Never loaded on a dedicated server.
        EnvExecutor.runInEnv(Env.CLIENT, () -> () -> ddraig.net.azureframelib.client.AzureFrameLibClient.init());
        LOGGER.info("[AzureFrameLib] Shared resource system initialized successfully.");
    }
}
