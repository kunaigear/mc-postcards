/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.util.resource.model;

import io.github.kunaigear.postcards.obscura.model.resource.RPModel;
import io.github.kunaigear.postcards.obscura.util.resource.RPHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import org.joml.Vector3f;

public class SignModel {
    public static RPModel.View get(BlockState blockState) {
        RPModel rpModel;
        if (blockState.is(BlockTags.SIGNS))
            rpModel = RPHelper.loadModel(SignModel.class.getResourceAsStream("/postcards/obscura/builtin/sign.json"));
        else
            rpModel = RPHelper.loadModel(SignModel.class.getResourceAsStream("/postcards/obscura/builtin/hanging_sign.json"));

        var rot = blockState.hasProperty(StandingSignBlock.ROTATION) ? (RotationSegment.convertToDegrees(blockState.getValue(StandingSignBlock.ROTATION)) + 180) % 360 :
                blockState.hasProperty(HorizontalDirectionalBlock.FACING) ? (blockState.getValue(HorizontalDirectionalBlock.FACING).toYRot() + 180) % 360 : 0;
        return new RPModel.View(rpModel, new Vector3f(0, rot, 0), new Vector3f(0, 0.0f, 0));
    }
}
