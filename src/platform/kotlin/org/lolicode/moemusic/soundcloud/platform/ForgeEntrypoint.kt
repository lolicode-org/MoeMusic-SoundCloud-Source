package org.lolicode.moemusic.soundcloud.platform

import net.minecraftforge.fml.common.Mod
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.soundcloud.SoundCloudPlugin

/**
 * Minecraft Forge mod entrypoint.
 *
 * Instantiated by Forge FML during mod loading to register [SoundCloudPlugin]
 * with MoeMusic's public plugin API.
 */
@Mod(SoundCloudPlugin.MOD_ID)
class ForgeEntrypoint {
    init {
        MoeMusicApi.registerPlugin(SoundCloudPlugin)
    }
}
