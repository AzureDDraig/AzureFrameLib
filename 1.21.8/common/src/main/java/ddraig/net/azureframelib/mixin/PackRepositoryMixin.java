package ddraig.net.azureframelib.mixin;

import ddraig.net.azureframelib.resource.AzureDynamicPackResources;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mixin(PackRepository.class)
public class PackRepositoryMixin {
    @Inject(method = "discoverAvailable", at = @At("RETURN"), cancellable = true)
    private void injectDynamicPack(CallbackInfoReturnable<Map<String, Pack>> cir) {
        Map<String, Pack> original = cir.getReturnValue();
        if (original.containsKey(AzureDynamicPackResources.PACK_ID)) {
            return;
        }

        PackLocationInfo locationInfo = new PackLocationInfo(
                AzureDynamicPackResources.PACK_ID,
                Component.literal("AzureFrameLib Dynamic Resources"),
                PackSource.BUILT_IN,
                Optional.empty()
        );

        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return new AzureDynamicPackResources(info);
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return new AzureDynamicPackResources(info);
            }
        };

        PackSelectionConfig selectionConfig = new PackSelectionConfig(true, Pack.Position.TOP, false);

        Pack pack = Pack.readMetaAndCreate(
                locationInfo,
                supplier,
                PackType.CLIENT_RESOURCES,
                selectionConfig
        );

        if (pack != null) {
            Map<String, Pack> modified = new HashMap<>(original);
            modified.put(AzureDynamicPackResources.PACK_ID, pack);
            cir.setReturnValue(Map.copyOf(modified));
        }
    }

    @ModifyVariable(
        method = "setSelected",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Collection<String> modifySelected(Collection<String> selected) {
        if (selected != null && !selected.contains(AzureDynamicPackResources.PACK_ID)) {
            List<String> list = new ArrayList<>(selected);
            list.add(AzureDynamicPackResources.PACK_ID);
            return list;
        }
        return selected;
    }
}
