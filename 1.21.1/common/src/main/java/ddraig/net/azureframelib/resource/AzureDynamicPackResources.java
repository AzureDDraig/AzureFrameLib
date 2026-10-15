package ddraig.net.azureframelib.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;

public class AzureDynamicPackResources extends AbstractAzureDynamicPackResources {
    private final PackLocationInfo locationInfo;

    public AzureDynamicPackResources() {
        this(new PackLocationInfo(
                PACK_ID,
                Component.literal("AzureFrameLib Dynamic Resources"),
                PackSource.BUILT_IN,
                Optional.empty()
        ));
    }

    public AzureDynamicPackResources(PackLocationInfo locationInfo) {
        this.locationInfo = locationInfo;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        if (serializer.getMetadataSectionName().equals("pack")) {
            PackMetadataSection section = new PackMetadataSection(
                    Component.literal("AzureFrameLib Dynamic Resources"),
                    34, // 1.21+ Client Resource Pack format
                    Optional.empty()
            );
            return (T) section;
        }
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return this.locationInfo;
    }

    @Override
    public String packId() {
        return this.locationInfo.id();
    }
}
