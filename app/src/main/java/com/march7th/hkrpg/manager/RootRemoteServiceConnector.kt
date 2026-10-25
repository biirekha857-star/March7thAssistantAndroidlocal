package com.march7th.hkrpg.manager

import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.remote.RemoteServiceImpl

object RootRemoteServiceConnector : ProcessServiceConnectorBackend(SuSpawner) {

    override val backend = RemoteBackend.ROOT
    override val eventPrefix = "ROOT"
    override val processNameSuffix = "root_service"
    override val serviceClass: Class<*> = RemoteServiceImpl::class.java
    override val logFileName = "root_launch_debug.log"
    override val keepRoot: Boolean get() = keepRootForInputInjection
}
