package io.github.kunaigear.dailycams.obscura.renderer.entity.specific;

import net.minecraft.world.entity.projectile.EyeOfEnder;

public class EyeOfEnderRenderer extends BillboardItemEntityRenderer<EyeOfEnder> {
    public EyeOfEnderRenderer() {
        super(EyeOfEnder::getItem, 1f);
    }
}
