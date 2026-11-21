/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.util.resource.model;

import io.github.kunaigear.postcards.obscura.model.resource.RPModel;
import io.github.kunaigear.postcards.obscura.util.resource.RPHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.Optional;

public class ShulkerModel {
    public static RPModel.View get(BlockState blockState, Optional<DyeColor> dyeColor) {
        RPModel rpModel = RPHelper.loadModel(ChestModel.class.getResourceAsStream("/postcards/obscura/builtin/shulker.json"));

        dyeColor.ifPresentOrElse(
                color -> rpModel.textures.put("0", RPModel.TextureEntry.of("minecraft:entity/shulker/shulker_" + color.getName())),
                () -> rpModel.textures.put("0", RPModel.TextureEntry.of("minecraft:entity/shulker/shulker")));

        var rot = blockState.getValue(ShulkerBoxBlock.FACING).getRotation();
        return new RPModel.View(rpModel, rot.getEulerAnglesZXY(new Vector3f()).mul(-Mth.RAD_TO_DEG));
    }
}
