package io.github.kunaigear.dailycams.obscura.renderer.entity.specific;

import io.github.kunaigear.dailycams.obscura.renderer.entity.ModelBakery;
import net.minecraft.world.entity.animal.camel.Camel;

public class CamelHuskRenderer extends CamelRenderer {
    private static final String HUSK_TEXTURE = "entity/camel/camel_husk";
    private ModelBakery.BakedPart cachedHuskModel;

    @Override
    public ModelBakery.BakedPart buildRoot(Camel entity) {
        if (cachedHuskModel == null) {
            cachedHuskModel = buildAdultModel(HUSK_TEXTURE);
        }
        return cachedHuskModel;
    }

}