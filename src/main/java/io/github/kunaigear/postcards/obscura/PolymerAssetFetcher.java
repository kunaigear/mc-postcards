/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura;

import io.github.kunaigear.postcards.obscura.platform.AssetFetcher;
import eu.pb4.polymer.autohost.impl.AutoHost;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;

import java.util.concurrent.CompletableFuture;

public class PolymerAssetFetcher implements AssetFetcher {
    private final ResourcePackBuilder builder;

    public PolymerAssetFetcher(ResourcePackBuilder resourcePackBuilder) {
        this.builder = resourcePackBuilder;
    }

    public static boolean isEnabled() {
        return AutoHost.config == null || AutoHost.config.enabled;
    }

    @Override
    public byte[] getAsset(String path) {
        return builder.getDataOrSource(path);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.completedFuture(null);
    }
}
