package ddraig.net.azureframelib.mixin;

import ddraig.net.azureframelib.resource.AzureResourceManager;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Tells AzureFrameLib when the client reloads its resources (game start, F3+T, resource pack
 * changes), so the shared folders are rescanned and new or changed files are picked up.
 */
@Mixin(ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin {

    @Shadow
    @Final
    private PackType type;

    @Inject(method = "createReload", at = @At("HEAD"))
    private void azureframelib$onReloadStarting(Executor backgroundExecutor, Executor gameExecutor,
                                                CompletableFuture<Unit> waitingFor, List<PackResources> packs,
                                                CallbackInfoReturnable<ReloadInstance> cir) {
        if (this.type != PackType.CLIENT_RESOURCES) return;
        try {
            AzureResourceManager.onClientResourceReloadStarting();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "createReload", at = @At("RETURN"))
    private void azureframelib$onReloadCreated(Executor backgroundExecutor, Executor gameExecutor,
                                               CompletableFuture<Unit> waitingFor, List<PackResources> packs,
                                               CallbackInfoReturnable<ReloadInstance> cir) {
        if (this.type != PackType.CLIENT_RESOURCES) return;
        try {
            ReloadInstance instance = cir.getReturnValue();
            if (instance == null) return;
            instance.done().whenComplete((result, error) -> gameExecutor.execute(() -> {
                try {
                    AzureResourceManager.onClientResourceReloadFinished();
                } catch (Throwable ignored) {
                }
            }));
        } catch (Throwable ignored) {
        }
    }
}
