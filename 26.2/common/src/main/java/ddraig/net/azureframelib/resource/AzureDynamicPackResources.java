package ddraig.net.azureframelib.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.util.InclusiveRange;
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
    public <T> T getMetadataSection(MetadataSectionType<T> type) throws IOException {
        if (type == PackMetadataSection.CLIENT_TYPE || type == PackMetadataSection.SERVER_TYPE || "pack".equals(type.name())) {
            PackFormat minFormat = PackFormat.of(15);
            PackFormat maxFormat = PackFormat.of(999);
            PackMetadataSection section = new PackMetadataSection(
                    Component.literal("AzureFrameLib Dynamic Resources"),
                    new InclusiveRange<>(minFormat, maxFormat)
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
