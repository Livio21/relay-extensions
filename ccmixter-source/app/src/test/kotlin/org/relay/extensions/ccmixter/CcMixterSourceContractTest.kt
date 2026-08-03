package org.relay.extensions.ccmixter

import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.contract.RelaySourceContractTest

class CcMixterSourceContractTest : RelaySourceContractTest() {
    override fun createFactory(): RelaySourceFactory = CcMixterSourceFactory()
}
