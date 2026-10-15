package ddraig.net.azureframelib.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;

public final class VersionBridge {
    private VersionBridge() {
    }

    public static void renderModelPart(ModelPart root, PoseStack poseStack, VertexConsumer vertexConsumer,
                                       int packedLight, int packedOverlay,
                                       float r, float g, float b, float a, int argbColor) {
        root.render(poseStack, vertexConsumer, packedLight, packedOverlay, r, g, b, a);
    }
}
