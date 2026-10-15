package ddraig.net.azureframelib.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public class AzureDynamicPackResources extends AbstractAzureDynamicPackResources {
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        if (serializer.getMetadataSectionName().equals("pack")) {
            PackMetadataSection section = new PackMetadataSection(
                    Component.literal("AzureFrameLib Dynamic Resources"),
                    15 // 1.20.1 Client Resource Pack format is 15
            );
            return (T) section;
        }
        return null;
    }

    @Override
    public String packId() {
        return PACK_ID;
    }
}
