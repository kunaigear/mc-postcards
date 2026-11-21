package io.github.kunaigear.dailycams.obscura.renderer.entity.specific.block;

import io.github.kunaigear.dailycams.obscura.json.CachedIdentifierDeserializer;
import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.model.triangle.ModelTesselator;
import io.github.kunaigear.dailycams.obscura.renderer.BlockStateRenderer;
import io.github.kunaigear.dailycams.obscura.renderer.entity.EntityRenderer;
import io.github.kunaigear.dailycams.obscura.sore.model.Model;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.DrawCommand;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.RenderPipeline;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.RenderType;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4d;

public class TntRenderer implements EntityRenderer<PrimedTnt> {
    private static Model whiteCube;

    public static Model get() {
        if (whiteCube != null) {
            return whiteCube;
        }

        RPModel rpModel = new RPModel();
        rpModel.parent = CachedIdentifierDeserializer.get("minecraft:block/cube_all");
        rpModel.textures = new Object2ObjectOpenHashMap<>();
        rpModel.textures.put("all", RPModel.TextureEntry.of("minecraft:block/white_concrete"));
        var view = new RPModel.View(rpModel);
        var tri = new ModelTesselator(view);
        whiteCube = new Model(tri.build());
        return whiteCube;
    }

    @Override
    public void render(RenderPipeline pipeline, PrimedTnt entity) {
        Matrix4d transform = new Matrix4d().translate(entity.position().toVector3f());

        var block = entity.level().getBrightness(LightLayer.BLOCK, entity.blockPosition()) / 15f;
        var sky = entity.level().getBrightness(LightLayer.SKY, entity.blockPosition()) / 15f;

        double fuseRemainingInTicks = entity.getFuse() + 1;
        if (fuseRemainingInTicks < 10.0F) {
            double g = 1.0F - fuseRemainingInTicks / 10.0F;
            g = Mth.clamp(g, 0.0F, 1.0F);
            g *= g;
            g *= g;
            double s = 1.0F + g * 0.3F;
            transform.scale(s, s, s);
        }

        boolean white = (int) fuseRemainingInTicks / 5 % 2 == 0;
        if (!white) BlockStateRenderer.render(pipeline, entity.getBlockState(), transform, block, sky);
        else {
            pipeline.draw(new DrawCommand(RenderType.ENTITY, get(), transform.translate(-.5f, 0f, -.5f), IntList.of(0xFFFFFFFF)));
        }
    }
}