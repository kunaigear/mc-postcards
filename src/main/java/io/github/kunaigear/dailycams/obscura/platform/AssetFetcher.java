/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.platform;

import java.util.concurrent.CompletableFuture;

public interface AssetFetcher {
    /**
     * Returns the raw bytes of an asset.
     *
     * @param path Full asset path, e.g. "assets/minecraft/textures/block/stone.png"
     * @return file content or null if not found / not yet initialized
     */
    byte[] getAsset(String path);

    CompletableFuture<Void> initialize();
}