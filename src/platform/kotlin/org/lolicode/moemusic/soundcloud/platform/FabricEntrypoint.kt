package org.lolicode.moemusic.soundcloud.platform

import net.fabricmc.api.ModInitializer
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.soundcloud.SoundCloudPlugin

/**
 * Fabric / Quilt mod entrypoint.
 *
 * Called by FabricLoader during game initialization to register [SoundCloudPlugin]
 * with MoeMusic's public plugin API.
 */
class FabricEntrypoint : ModInitializer {
    override fun onInitialize() {
        MoeMusicApi.registerPlugin(SoundCloudPlugin)
    }
}
