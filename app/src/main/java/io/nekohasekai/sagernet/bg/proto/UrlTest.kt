package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

class UrlTest(private val timeoutMs: Int = 5000) {

    val link = DataStore.connectionTestURL

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeoutMs).doTest()
    }

}