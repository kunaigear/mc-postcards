package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.LoadedChunks;
import io.github.kunaigear.dailycams.render.RenderFlags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.tomalbrc.cameraobscura.FabricPlatform;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Camera Obscura asks Polymer for block-bound virtual entities at every visible block it meshes. Polymer finds the
 * chunk with {@code Level.getChunk}, which off the server thread hands the lookup to the server thread and waits for
 * it: one round trip per block. On our render thread, take the chunk from Camera Obscura's own map of loaded chunks.
 */
@Mixin(value = FabricPlatform.class, remap = false)
public abstract class FabricPlatformMixin {
    @WrapOperation(method = "renderBlock", at = @At(value = "INVOKE",
            target = "Leu/pb4/polymer/virtualentity/api/attachment/BlockAwareAttachment;get(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Leu/pb4/polymer/virtualentity/api/attachment/BlockAwareAttachment;"))
    private BlockAwareAttachment dailycams$getWithoutWaiting(Level level, BlockPos pos, Operation<BlockAwareAttachment> original) {
        if (!RenderFlags.rendering()) return original.call(level, pos);
        return LoadedChunks.of(level).get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4)) instanceof LevelChunk chunk
                ? BlockAwareAttachment.get(chunk, pos) : null;
    }
}
