package org.relay.extensions.fma

import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.contract.RelaySourceContractTest

class FreeMusicArchiveSourceContractTest : RelaySourceContractTest() {
    override fun createFactory(): RelaySourceFactory = FreeMusicArchiveSourceFactory()
}
