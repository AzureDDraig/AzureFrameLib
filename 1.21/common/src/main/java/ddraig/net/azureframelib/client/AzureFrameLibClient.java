package ddraig.net.azureframelib.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import ddraig.net.azureframelib.resource.AzureResourceManager;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent.ClientCommandSourceStack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client-only setup: registers the {@code /azureframelib} helper command.
 * <ul>
 *     <li>{@code /azureframelib animations [filter]} - which animation file each model uses and the animation names inside.</li>
 *     <li>{@code /azureframelib stats} - how many assets are indexed.</li>
 *     <li>{@code /azureframelib selftest} - quick speed check of the lookups.</li>
 *     <li>{@code /azureframelib reload} - same as F3+T.</li>
 * </ul>
 */
public final class AzureFrameLibClient {
    private static final int CHAT_LINES = 30;
    private static boolean initialized;

    private AzureFrameLibClient() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            ClientCommandRegistrationEvent.EVENT.register((dispatcher, context) -> dispatcher.register(
                    ClientCommandRegistrationEvent.literal("azureframelib")
                            .then(ClientCommandRegistrationEvent.literal("animations")
                                    .executes(ctx -> animations(ctx, null))
                                    .then(ClientCommandRegistrationEvent.argument("filter", StringArgumentType.greedyString())
                                            .executes(ctx -> animations(ctx, StringArgumentType.getString(ctx, "filter")))))
                            .then(ClientCommandRegistrationEvent.literal("stats").executes(AzureFrameLibClient::stats))
                            .then(ClientCommandRegistrationEvent.literal("selftest").executes(AzureFrameLibClient::selftest))
                            .then(ClientCommandRegistrationEvent.literal("reload").executes(AzureFrameLibClient::reload))
            ));
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.warn("[AzureFrameLib] Could not register the /azureframelib command: {}", t.toString());
        }
    }

    private static void say(CommandContext<ClientCommandSourceStack> ctx, String text) {
        ctx.getSource().arch$sendSuccess(() -> Component.literal(text), false);
    }

    private static int animations(CommandContext<ClientCommandSourceStack> ctx, String filter) {
        try {
            List<String> lines = AzureAssetIndex.describeAnimationLinks(filter);
            AzureFrameLib.LOGGER.info("[AzureFrameLib] Animation links ({} models{}):", lines.size(),
                    filter == null ? "" : ", filter \"" + filter + "\"");
            for (String line : lines) AzureFrameLib.LOGGER.info("[AzureFrameLib]   {}", line);

            if (lines.isEmpty()) {
                say(ctx, "No models found" + (filter == null ? "." : " matching \"" + filter + "\"."));
                return 0;
            }
            for (int i = 0; i < Math.min(CHAT_LINES, lines.size()); i++) say(ctx, lines.get(i));
            if (lines.size() > CHAT_LINES) {
                say(ctx, (lines.size() - CHAT_LINES) + " more - see the game log (latest.log) for the full list.");
            }
            return lines.size();
        } catch (Throwable t) {
            ctx.getSource().arch$sendFailure(Component.literal("Could not list animations: " + t));
            return 0;
        }
    }

    private static int stats(CommandContext<ClientCommandSourceStack> ctx) {
        try {
            if (!AzureAssetIndex.isIndexReady()) {
                say(ctx, "AzureFrameLib is still scanning its folders, try again in a moment.");
                return 0;
            }
            int[] c = AzureAssetIndex.getCounts();
            say(ctx, "AzureFrameLib index #" + AzureAssetIndex.getIndexVersion() + ": " + c[0] + " models, " + c[1]
                    + " textures, " + c[2] + " animations, " + c[3] + " sounds (" + c[4] + " entries total).");
            return 1;
        } catch (Throwable t) {
            ctx.getSource().arch$sendFailure(Component.literal("Could not read stats: " + t));
            return 0;
        }
    }

    private static int selftest(CommandContext<ClientCommandSourceStack> ctx) {
        try {
            Set<String> models = AzureResourceManager.getDiscoveredModels();
            String probe = models.isEmpty() ? "dragon" : models.iterator().next();

            AzureAssetIndex.findModel(probe); // warm-up
            long start = System.nanoTime();
            int found = 0;
            for (int i = 0; i < 10_000; i++) {
                if (AzureAssetIndex.findModel(probe).isPresent()) found++;
            }
            double lookupMs = (System.nanoTime() - start) / 1_000_000.0;

            List<String> input = new ArrayList<>(5_000);
            for (int i = 0; i < 5_000; i++) input.add("selftest:textures/entry_" + i + ".png");
            CopyOnWriteArrayList<String> target = new CopyOnWriteArrayList<>(input);
            start = System.nanoTime();
            ClientSuggestionsHelper.addClientTextures(target);
            double suggestMs = (System.nanoTime() - start) / 1_000_000.0;

            say(ctx, String.format("10,000 x findModel(\"%s\"): %.2f ms (%s, goal: under 5 ms)",
                    probe, lookupMs, found > 0 ? "found" : "not found"));
            say(ctx, String.format("addClientTextures into a 5,000 entry list: %.2f ms (goal: under 50 ms)", suggestMs));
            AzureFrameLib.LOGGER.info("[AzureFrameLib] Self-test: findModel x10000 = {} ms, addClientTextures(5000) = {} ms",
                    String.format("%.2f", lookupMs), String.format("%.2f", suggestMs));
            return 1;
        } catch (Throwable t) {
            ctx.getSource().arch$sendFailure(Component.literal("Self-test failed: " + t));
            return 0;
        }
    }

    private static int reload(CommandContext<ClientCommandSourceStack> ctx) {
        say(ctx, "Reloading resources (same as F3+T)...");
        Minecraft mc = Minecraft.getInstance();
        mc.execute(mc::reloadResourcePacks);
        return 1;
    }
}
