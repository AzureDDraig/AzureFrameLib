package net.minecraft.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.function.Function;

/**
 * Compatibility bridge for Minecraft 1.21.11+ where HierarchicalModel was merged into Model.
 * Keeps JavaModelLoader and downstream mods source-compatible across all versions.
 */
public abstract class HierarchicalModel<E extends Entity> {
    private final Function<Identifier, RenderType> renderType;

    protected HierarchicalModel() {
        this(RenderTypes::entityCutoutNoCull);
    }

    protected HierarchicalModel(Function<Identifier, RenderType> renderType) {
        this.renderType = renderType;
    }

    public final RenderType renderType(Identifier location) {
        return this.renderType.apply(location);
    }

    public abstract ModelPart root();

    public abstract void setupAnim(E entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch);

    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
        this.root().render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }

    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        int r = Math.max(0, Math.min(255, Math.round(red * 255.0f)));
        int g = Math.max(0, Math.min(255, Math.round(green * 255.0f)));
        int b = Math.max(0, Math.min(255, Math.round(blue * 255.0f)));
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        int color = (a << 24) | (r << 16) | (g << 8) | b;
        this.root().render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }

    public Optional<ModelPart> getAnyDescendantWithName(String name) {
        if ("root".equals(name)) {
            return Optional.of(this.root());
        }
        return this.root().getAllParts().stream()
                .filter(part -> part.hasChild(name))
                .findFirst()
                .map(part -> part.getChild(name));
    }
}
