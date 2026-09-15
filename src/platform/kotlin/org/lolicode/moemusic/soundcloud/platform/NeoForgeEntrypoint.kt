package org.lolicode.moemusic.soundcloud.platform

import net.neoforged.fml.common.Mod
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.soundcloud.SoundCloudPlugin

/**
 * NeoForge mod entrypoint.
 *
 * Instantiated by NeoForge FML during mod loading to register [SoundCloudPlugin]
 * with MoeMusic's public plugin API.
 */
@Mod(SoundCloudPlugin.MOD_ID)
class NeoForgeEntrypoint {
    init {
        MoeMusicApi.registerPlugin(SoundCloudPlugin)
    }
}
