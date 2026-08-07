package org.relay.extensions.octavepoc

import dev.relay.music.source.api.RelaySource
import dev.relay.music.source.api.RelaySourceApi
import dev.relay.music.source.api.RelaySourceFactory

class OctavePocSourceFactory : RelaySourceFactory {
    override fun getApiVersion(): Int = RelaySourceApi.VERSION
    override fun createSources(): List<RelaySource> = listOf(OctavePocSource())
}