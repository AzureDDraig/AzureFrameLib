package ddraig.net.azureframelib.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Small client-only helpers. Only call these after checking that the game is running on a client
 * (for example {@code Platform.getEnvironment() == Env.CLIENT}); this class must never load on a
 * dedicated server.
 */
public final class ClientTaskHelper {

    private ClientTaskHelper() {
    }

    /** Runs the task on the main client thread: right away if we're already on it (or the game isn't up yet), otherwise queued. */
    public static void runOnMainThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    /** True when called from the main client (render) thread. */
    public static boolean isMainThread() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.isSameThread();
    }

    /** Reads a text file from the loaded client resources (resource packs and mod jars). */
    public static Optional<String> readResourceText(ResourceLocation location) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getResourceManager() == null || location == null) return Optional.empty();
            Optional<Resource> res = mc.getResourceManager().getResource(location);
            if (res.isEmpty()) return Optional.empty();
            try (InputStream in = res.get().open()) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
