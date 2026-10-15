package ddraig.net.azureframelib.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public final class VersionBridge {
    private VersionBridge() {
    }

    public static void renderModelPart(ModelPart root, PoseStack poseStack, VertexConsumer vertexConsumer,
                                       int packedLight, int packedOverlay,
                                       float r, float g, float b, float a, int argbColor) {
        root.render(poseStack, vertexConsumer, packedLight, packedOverlay, r, g, b, a);
    }

    public static DynamicTexture createDynamicTexture(String label, NativeImage image) {
        return new DynamicTexture(image);
    }

    public static int getPixelABGR(NativeImage image, int x, int y) {
        return image.getPixelRGBA(x, y);
    }

    public static void setPixelABGR(NativeImage image, int x, int y, int abgr) {
        image.setPixelRGBA(x, y, abgr);
    }

    public static ServerLevel getServerLevel(ServerPlayer player) {
        return player.serverLevel();
    }

    public static void teleportPlayer(ServerPlayer player, ServerLevel targetLevel,
                                      double x, double y, double z, float yRot, float xRot) {
        player.teleportTo(targetLevel, x, y, z, yRot, xRot);
    }

    public static <E> Optional<Registry<E>> getRegistry(RegistryAccess access, ResourceKey<? extends Registry<? extends E>> key) {
        return access.registry(key);
    }
}
