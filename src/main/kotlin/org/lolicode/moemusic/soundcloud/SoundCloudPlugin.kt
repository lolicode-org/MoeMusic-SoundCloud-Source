package org.lolicode.moemusic.soundcloud

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.PluginConfigSpec
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.pluginConfigSpec

object SoundCloudPlugin : Plugin {
    const val PLUGIN_ID = "soundcloud-source"
    const val CONFIG_ID = "soundcloud-source"
    const val SOURCE_ID = "soundcloud"

    override val id: String = PLUGIN_ID
    override val configId: String = CONFIG_ID
    override val displayName: LocalizedText = LocalizedText.key("plugin.soundcloud.source")
    override val version: String = "1.1.0"
    override val supportedApiVersions: String = ">=2.2.0 <3.0.0"

    override val configSpec: PluginConfigSpec<SoundCloudConfig> =
        pluginConfigSpec(::SoundCloudConfig) {
            boolean(
                key = "enabled",
                getter = { it.enabled },
                updater = { config, value -> config.copy(enabled = value) },
            )
            string(
                key = "client_id",
                getter = { it.clientId },
                updater = { config, value -> config.copy(clientId = value) },
                validator = { _, value ->
                    if (value.isBlank() || Regex("[A-Za-z0-9_-]{8,128}").matches(value.trim())) {
                        null
                    } else {
                        LocalizedText.key("config.soundcloud.source.validation.client_id")
                    }
                },
            )
        }

    override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
        val source = SoundCloudSource(ctx.loadConfig(configSpec))
        ctx.registerMusicSource(source)
        ctx.onConfigChanged(configSpec) { source.updateConfig(it) }
        ctx.logger.info("Registered SoundCloud source '{}'.", SOURCE_ID)
    }
}
