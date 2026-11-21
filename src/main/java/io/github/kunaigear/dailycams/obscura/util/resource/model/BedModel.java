/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.util.resource.model;

import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.joml.Vector3f;

import java.util.Optional;

public class BedModel {
    public static RPModel.View get(BlockState blockState, Optional<DyeColor> color) {
        RPModel rpModel = RPHelper.loadModel(ChestModel.class.getResourceAsStream(blockState.getValue(BedBlock.PART) == BedPart.HEAD ? "/dailycams/obscura/builtin/bed_top.json" : "/dailycams/obscura/builtin/bed_bottom.json"));

        color.ifPresentOrElse(
                dyeColor -> rpModel.textures.put("0", RPModel.TextureEntry.of("minecraft:entity/bed/" + dyeColor.getName())),
                () -> rpModel.textures.put("0", RPModel.TextureEntry.of("minecraft:entity/bed/red"))
        );

        return new RPModel.View(rpModel, new Vector3f(0, (blockState.getValue(BedBlock.FACING).toYRot() + 180) % 360, 0));
    }
}
