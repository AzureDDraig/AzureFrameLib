package ddraig.net.azureframelib.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.resource.AzureResourceManager;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal Blockbench-exported Java model and animation parser.
 * Dynamically constructs HierarchicalModel instances and AnimationDefinition trees from .java files.
 */
public class JavaModelLoader {
    private static final Map<String, LayerDefinition> LAYER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, AnimationDefinition>> ANIM_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> FAILED_MODELS = ConcurrentHashMap.newKeySet();

    public static void clearCaches() {
        LAYER_CACHE.clear();
        ANIM_CACHE.clear();
        FAILED_MODELS.clear();
    }

    public static <T extends Entity> HierarchicalModel<T> getModel(String modelId) {
        if (modelId == null || modelId.trim().isEmpty() || FAILED_MODELS.contains(modelId)) {
            return null;
        }

        if (!LAYER_CACHE.containsKey(modelId)) {
            loadFromDisk(modelId);
        }

        LayerDefinition layerDef = LAYER_CACHE.get(modelId);
        if (layerDef != null) {
            ModelPart rootPart = layerDef.bakeRoot();
            return new DynamicHierarchicalModel<>(rootPart);
        }
        return null;
    }

    public static Map<String, AnimationDefinition> getAnimations(String modelId) {
        if (modelId == null || modelId.trim().isEmpty() || FAILED_MODELS.contains(modelId)) {
            return Collections.emptyMap();
        }

        if (!ANIM_CACHE.containsKey(modelId)) {
            loadFromDisk(modelId);
        }

        return ANIM_CACHE.getOrDefault(modelId, Collections.emptyMap());
    }

    private static synchronized void loadFromDisk(String modelId) {
        if (LAYER_CACHE.containsKey(modelId) || FAILED_MODELS.contains(modelId)) {
            return;
        }

        File file = AzureResourceManager.findModelFile(modelId);
        if (file == null || !file.exists()) {
            FAILED_MODELS.add(modelId);
            return;
        }

        // If file is in a folder with an anim java file or sibling file
        File modelFile = file;
        File animFile = null;

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".java")) {
                        if (name.contains("anim")) {
                            animFile = f;
                        } else {
                            modelFile = f;
                        }
                    }
                }
            }
        } else if (file.getParentFile() != null) {
            File[] siblings = file.getParentFile().listFiles();
            if (siblings != null) {
                for (File f : siblings) {
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".java") && name.contains("anim")) {
                        animFile = f;
                        break;
                    }
                }
            }
        }

        if (modelFile != null && modelFile.isFile() && modelFile.getName().toLowerCase(Locale.ROOT).endsWith(".java")) {
            try {
                String content = Files.readString(modelFile.toPath());
                LayerDefinition layer = parseModelLayer(content);
                if (layer != null) {
                    LAYER_CACHE.put(modelId, layer);
                } else {
                    FAILED_MODELS.add(modelId);
                }
            } catch (Exception e) {
                AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed parsing Java model: " + modelId, e);
                FAILED_MODELS.add(modelId);
            }
        } else {
            FAILED_MODELS.add(modelId);
        }

        if (animFile != null && animFile.isFile()) {
            try {
                String animContent = Files.readString(animFile.toPath());
                Map<String, AnimationDefinition> anims = parseAnimations(animContent);
                ANIM_CACHE.put(modelId, anims);
            } catch (Exception e) {
                AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed parsing Java animations for: " + modelId, e);
                ANIM_CACHE.put(modelId, Collections.emptyMap());
            }
        } else {
            ANIM_CACHE.put(modelId, Collections.emptyMap());
        }
    }

    public static LayerDefinition parseModelLayer(String content) {
        try {
            content = cleanCommentsAndWhitespace(content);

            int texW = 64;
            int texH = 64;
            Pattern texPattern = Pattern.compile("LayerDefinition\\.create\\s*\\(\\s*(\\w+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
            Matcher texMatcher = texPattern.matcher(content);
            if (texMatcher.find()) {
                texW = Integer.parseInt(texMatcher.group(2));
                texH = Integer.parseInt(texMatcher.group(3));
            }

            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            Map<String, PartDefinition> parts = new HashMap<>();
            parts.put("root", root);
            parts.put("partdefinition", root);

            Pattern addPartPattern = Pattern.compile("PartDefinition\\s+(\\w+)\\s*=\\s*(\\w+)\\.addOrReplaceChild\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*(.*?)\\s*,\\s*PartPose\\.offsetAndRotation\\s*\\((.*?)\\)\\s*\\);");
            Matcher partMatcher = addPartPattern.matcher(content);

            while (partMatcher.find()) {
                String varName = partMatcher.group(1);
                String parentVar = partMatcher.group(2);
                String partName = partMatcher.group(3);
                String cubeBuilderCode = partMatcher.group(4);
                String poseCode = partMatcher.group(5);

                PartDefinition parent = parts.getOrDefault(parentVar, root);
                CubeListBuilder cubeBuilder = parseCubeBuilder(cubeBuilderCode);
                PartPose pose = parsePartPose(poseCode);

                PartDefinition newPart = parent.addOrReplaceChild(partName, cubeBuilder, pose);
                parts.put(varName, newPart);
            }

            // Also check for child definitions without offsetAndRotation (offset only)
            Pattern offsetOnlyPattern = Pattern.compile("PartDefinition\\s+(\\w+)\\s*=\\s*(\\w+)\\.addOrReplaceChild\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*(.*?)\\s*,\\s*PartPose\\.offset\\s*\\((.*?)\\)\\s*\\);");
            Matcher offsetMatcher = offsetOnlyPattern.matcher(content);
            while (offsetMatcher.find()) {
                String varName = offsetMatcher.group(1);
                if (parts.containsKey(varName)) continue;

                String parentVar = offsetMatcher.group(2);
                String partName = offsetMatcher.group(3);
                String cubeBuilderCode = offsetMatcher.group(4);
                String poseCode = offsetMatcher.group(5);

                PartDefinition parent = parts.getOrDefault(parentVar, root);
                CubeListBuilder cubeBuilder = parseCubeBuilder(cubeBuilderCode);
                PartPose pose = parseOffsetPose(poseCode);

                PartDefinition newPart = parent.addOrReplaceChild(partName, cubeBuilder, pose);
                parts.put(varName, newPart);
            }

            return LayerDefinition.create(mesh, texW, texH);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Error constructing LayerDefinition from Java code", e);
            return null;
        }
    }

    private static CubeListBuilder parseCubeBuilder(String code) {
        CubeListBuilder builder = CubeListBuilder.create();
        if (code.contains("CubeDeformation")) {
            Pattern cubePattern = Pattern.compile("texOffs\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\.addBox\\(\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*new\\s+CubeDeformation\\(\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*\\)\\)");
            Matcher matcher = cubePattern.matcher(code);
            while (matcher.find()) {
                int u = Integer.parseInt(matcher.group(1));
                int v = Integer.parseInt(matcher.group(2));
                float x = parseFloat(matcher.group(3));
                float y = parseFloat(matcher.group(4));
                float z = parseFloat(matcher.group(5));
                float dx = parseFloat(matcher.group(6));
                float dy = parseFloat(matcher.group(7));
                float dz = parseFloat(matcher.group(8));
                float def = parseFloat(matcher.group(9));
                builder.texOffs(u, v).addBox(x, y, z, dx, dy, dz, new CubeDeformation(def));
            }
        } else {
            Pattern cubePattern = Pattern.compile("texOffs\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\.addBox\\(\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*\\)");
            Matcher matcher = cubePattern.matcher(code);
            while (matcher.find()) {
                int u = Integer.parseInt(matcher.group(1));
                int v = Integer.parseInt(matcher.group(2));
                float x = parseFloat(matcher.group(3));
                float y = parseFloat(matcher.group(4));
                float z = parseFloat(matcher.group(5));
                float dx = parseFloat(matcher.group(6));
                float dy = parseFloat(matcher.group(7));
                float dz = parseFloat(matcher.group(8));
                builder.texOffs(u, v).addBox(x, y, z, dx, dy, dz);
            }
        }
        return builder;
    }

    private static PartPose parsePartPose(String code) {
        String[] parts = code.split(",");
        if (parts.length >= 6) {
            float x = parseFloat(parts[0]);
            float y = parseFloat(parts[1]);
            float z = parseFloat(parts[2]);
            float rx = parseFloat(parts[3]);
            float ry = parseFloat(parts[4]);
            float rz = parseFloat(parts[5]);
            return PartPose.offsetAndRotation(x, y, z, rx, ry, rz);
        }
        return PartPose.ZERO;
    }

    private static PartPose parseOffsetPose(String code) {
        String[] parts = code.split(",");
        if (parts.length >= 3) {
            float x = parseFloat(parts[0]);
            float y = parseFloat(parts[1]);
            float z = parseFloat(parts[2]);
            return PartPose.offset(x, y, z);
        }
        return PartPose.ZERO;
    }

    public static Map<String, AnimationDefinition> parseAnimations(String content) {
        Map<String, AnimationDefinition> animations = new HashMap<>();
        try {
            content = cleanCommentsAndWhitespace(content);
            String[] statements = content.split(";");
            for (String stmt : statements) {
                stmt = stmt.trim();
                if (stmt.contains("AnimationDefinition.Builder")) {
                    Pattern defPattern = Pattern.compile("public\\s+static\\s+final\\s+AnimationDefinition\\s+([a-zA-Z0-9_\\.]+)\\s*=");
                    Matcher matcher = defPattern.matcher(stmt);
                    if (matcher.find()) {
                        String fullVarName = matcher.group(1);
                        String animName = fullVarName.substring(fullVarName.lastIndexOf('.') + 1);

                        float length = 1.0f;
                        Pattern lenPattern = Pattern.compile("withLength\\(\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*\\)");
                        Matcher lenMatcher = lenPattern.matcher(stmt);
                        if (lenMatcher.find()) {
                            length = parseFloat(lenMatcher.group(1));
                        }

                        boolean looping = stmt.contains(".looping()");
                        AnimationDefinition.Builder builder = AnimationDefinition.Builder.withLength(length);
                        if (looping) {
                            builder = builder.looping();
                        }

                        Pattern channelPattern = Pattern.compile("\\.addAnimation\\(\\s*\"([^\"]+)\"\\s*,\\s*new\\s+AnimationChannel\\(\\s*AnimationChannel\\.Targets\\.(\\w+)\\s*,(.*?)\\)\\s*\\)");
                        Matcher chanMatcher = channelPattern.matcher(stmt);
                        while (chanMatcher.find()) {
                            String partName = chanMatcher.group(1);
                            String targetStr = chanMatcher.group(2);
                            String keyframesCode = chanMatcher.group(3).trim();
                            if (!keyframesCode.endsWith(")")) keyframesCode += ")";

                            AnimationChannel.Target target = AnimationChannel.Targets.ROTATION;
                            if (targetStr.equals("POSITION")) target = AnimationChannel.Targets.POSITION;
                            else if (targetStr.equals("SCALE")) target = AnimationChannel.Targets.SCALE;

                            List<Keyframe> keyframes = new ArrayList<>();
                            Pattern keyframePattern = Pattern.compile("new\\s+Keyframe\\(\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*KeyframeAnimations\\.(\\w+)\\(\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?F?)\\s*\\)\\s*,\\s*AnimationChannel\\.Interpolations\\.(\\w+)\\s*\\)");
                            Matcher kfMatcher = keyframePattern.matcher(keyframesCode);
                            while (kfMatcher.find()) {
                                float time = parseFloat(kfMatcher.group(1));
                                String vecType = kfMatcher.group(2);
                                float vx = parseFloat(kfMatcher.group(3));
                                float vy = parseFloat(kfMatcher.group(4));
                                float vz = parseFloat(kfMatcher.group(5));
                                String interpStr = kfMatcher.group(6);

                                Vector3f vec;
                                if (vecType.equals("posVec")) vec = KeyframeAnimations.posVec(vx, vy, vz);
                                else if (vecType.equals("scaleVec")) vec = KeyframeAnimations.scaleVec(vx, vy, vz);
                                else vec = KeyframeAnimations.degreeVec(vx, vy, vz);

                                AnimationChannel.Interpolation interp = interpStr.equals("CATMULLROM") ?
                                        AnimationChannel.Interpolations.CATMULLROM : AnimationChannel.Interpolations.LINEAR;

                                keyframes.add(new Keyframe(time, vec, interp));
                            }

                            if (!keyframes.isEmpty()) {
                                AnimationChannel channel = new AnimationChannel(target, keyframes.toArray(new Keyframe[0]));
                                builder = builder.addAnimation(partName, channel);
                            }
                        }

                        animations.put(animName.toLowerCase(Locale.ROOT), builder.build());
                    }
                }
            }
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed parsing animations from Java code", e);
        }
        return animations;
    }

    public static class DynamicHierarchicalModel<T extends Entity> extends HierarchicalModel<T> {
        private final ModelPart root;

        public DynamicHierarchicalModel(ModelPart root) {
            this.root = root;
        }

        @Override
        public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
            this.root.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        }

        @Override
        public ModelPart root() {
            return this.root;
        }
    }

    private static String cleanCommentsAndWhitespace(String content) {
        content = content.replaceAll("//.*", "");
        content = content.replaceAll("/\\*(?s:.*?)\\*/", "");
        content = content.replaceAll("\\s+", " ");
        return content;
    }

    private static float parseFloat(String s) {
        s = s.trim();
        if (s.endsWith("F") || s.endsWith("f")) {
            s = s.substring(0, s.length() - 1);
        }
        return Float.parseFloat(s);
    }
}
