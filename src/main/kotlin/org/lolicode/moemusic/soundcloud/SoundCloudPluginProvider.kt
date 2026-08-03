package org.lolicode.moemusic.soundcloud

import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.PluginProvider

class SoundCloudPluginProvider : PluginProvider {
    override fun plugins(): Iterable<Plugin> = listOf(SoundCloudPlugin)
}
