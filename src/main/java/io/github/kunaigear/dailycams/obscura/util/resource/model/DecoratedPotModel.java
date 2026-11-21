package io.github.kunaigear.dailycams.obscura.util.resource.model;

import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;
import net.minecraft.world.phys.Vec3;

public class DecoratedPotModel {
    public static RPModel.View get() {
        RPModel rpModel = RPHelper.loadModel(ChestModel.class.getResourceAsStream("/builtin/decorated_pot.json"));
        return new RPModel.View(rpModel, Vec3.ZERO.toVector3f());
    }
}
