package io.github.kunaigear.dailycams.obscura.util.resource.model;

import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;

public class BellModel {
    public static RPModel.View get() {
        RPModel rpModel = RPHelper.loadModel(BellModel.class.getResourceAsStream("/builtin/bell.json"));
        return new RPModel.View(rpModel);
    }
}
