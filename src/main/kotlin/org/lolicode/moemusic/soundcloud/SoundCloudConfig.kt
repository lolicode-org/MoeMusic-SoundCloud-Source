package org.lolicode.moemusic.soundcloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SoundCloudConfig(
    @SerialName("enabled")
    val enabled: Boolean = true,
    @SerialName("client_id")
    val clientId: String = "",
)
