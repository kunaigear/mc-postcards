package io.github.kunaigear.dailycams.obscura.util.resource.model;

import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;
import net.minecraft.world.phys.Vec3;

public class ConduitModel {
    public static RPModel.View get() {
        RPModel rpModel = RPHelper.loadModel(ChestModel.class.getResourceAsStream("/builtin/conduit.json"));
        return new RPModel.View(rpModel, Vec3.ZERO.toVector3f());
    }
}
