package ddraig.net.azureframelib.util;

import ddraig.net.azureframelib.AzureFrameLib;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal safe player teleportation helper with dimension crossing,
 * warmup delay, and particle/sound integration.
 */
public class TeleportHelper {

    private static final Map<UUID, WarmupTask> ACTIVE_WARMUPS = new ConcurrentHashMap<>();

    public static class WarmupTask {
        public final ServerPlayer player;
        public final double startX, startY, startZ;
        public final double targetX, targetY, targetZ;
        public final String targetDimension;
        public int ticksRemaining;
        public final boolean cancelOnMove;
        public final Runnable onComplete;

        public WarmupTask(ServerPlayer player, double targetX, double targetY, double targetZ,
                          String targetDimension, int ticksRemaining, boolean cancelOnMove, Runnable onComplete) {
            this.player = player;
            this.startX = player.getX();
            this.startY = player.getY();
            this.startZ = player.getZ();
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.targetDimension = targetDimension;
            this.ticksRemaining = ticksRemaining;
            this.cancelOnMove = cancelOnMove;
            this.onComplete = onComplete;
        }
    }

    public static void startTeleport(ServerPlayer player, double targetX, double targetY, double targetZ,
                                     String targetDimension, int delayTicks, boolean cancelOnMove, Runnable onComplete) {
        if (player == null) return;
        if (delayTicks <= 0) {
            teleportDirect(player, targetX, targetY, targetZ, targetDimension);
            if (onComplete != null) onComplete.run();
            return;
        }

        ACTIVE_WARMUPS.put(player.getUUID(), new WarmupTask(player, targetX, targetY, targetZ, targetDimension, delayTicks, cancelOnMove, onComplete));
    }

    public static void tickPlayer(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        WarmupTask task = ACTIVE_WARMUPS.get(uuid);
        if (task == null) return;

        if (task.cancelOnMove) {
            double dx = player.getX() - task.startX;
            double dy = player.getY() - task.startY;
            double dz = player.getZ() - task.startZ;
            if ((dx * dx + dy * dy + dz * dz) > 0.5) {
                ACTIVE_WARMUPS.remove(uuid);
                return;
            }
        }

        task.ticksRemaining--;
        if (task.ticksRemaining <= 0) {
            ACTIVE_WARMUPS.remove(uuid);
            teleportDirect(player, task.targetX, task.targetY, task.targetZ, task.targetDimension);
            if (task.onComplete != null) {
                task.onComplete.run();
            }
        }
    }

    public static boolean teleportDirect(ServerPlayer player, double targetX, double targetY, double targetZ, String targetDimension) {
        if (player == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;

        ServerLevel targetLevel = player.serverLevel();
        if (targetDimension != null && !targetDimension.isEmpty()) {
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(targetDimension));
            ServerLevel resolvedLevel = server.getLevel(dimKey);
            if (resolvedLevel != null) {
                targetLevel = resolvedLevel;
            }
        }

        try {
            // Spawn departure particles and sound
            playTeleportEffects(player.serverLevel(), player.getX(), player.getY(), player.getZ());

            player.teleportTo(targetLevel, targetX, targetY, targetZ, player.getYRot(), player.getXRot());

            // Spawn arrival particles and sound
            playTeleportEffects(targetLevel, targetX, targetY, targetZ);
            return true;
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed teleporting player " + player.getName().getString(), e);
            return false;
        }
    }

    public static void playTeleportEffects(ServerLevel level, double x, double y, double z) {
        if (level == null) return;
        level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.PORTAL, x, y + 1.0, z, 32, 0.5, 0.5, 0.5, 0.1);
    }
}
