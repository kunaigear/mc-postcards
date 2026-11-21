/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura;

import com.mojang.math.Axis;
import io.github.kunaigear.dailycams.obscura.renderer.entity.ItemStackRenderer;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.DrawCommand;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import net.minecraft.util.Mth;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.List;

public class PolymerHolderRenderer {
    public static void render(ElementHolder holder, List<DrawCommand> commands, double yOffset) {
        for (VirtualElement virtualElement : holder.getElements()) {
            if (virtualElement instanceof ItemDisplayElement itemDisplayElement) {
                var item = itemDisplayElement.getItem();
                if (!item.isEmpty()) {
                    var transform = new Matrix4d().translate(itemDisplayElement.getCurrentPos().toVector3f().add(0, (float) yOffset, 0));

                    transform
                            .rotateY(Mth.DEG_TO_RAD * (-itemDisplayElement.getYaw() - 360f))
                            .rotateX(Mth.DEG_TO_RAD * (itemDisplayElement.getPitch()));

                    transform.translate(itemDisplayElement.getTranslation());
                    transform.rotate(itemDisplayElement.getLeftRotation());
                    transform.scale(itemDisplayElement.getScale().get(new Vector3d()));
                    transform.rotate(itemDisplayElement.getRightRotation());

                    transform.rotate(Axis.YP.rotation((float) Math.PI));

                    ItemStackRenderer.renderDeferred(commands, item, itemDisplayElement.getItemDisplayContext(), transform);
                }
            }
        }
    }
}
