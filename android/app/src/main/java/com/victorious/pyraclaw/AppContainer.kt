package com.victorious.pyraclaw

import android.content.Context
import com.victorious.pyraclaw.data.DefaultDirectEsp32
import com.victorious.pyraclaw.data.DefaultHubRepository
import com.victorious.pyraclaw.data.DirectEsp32
import com.victorious.pyraclaw.data.HubDiscovery
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.LiveHub
import com.victorious.pyraclaw.data.SecureSettingsStore
import com.victorious.pyraclaw.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI: one OkHttp client, one settings store, one live socket for the whole process. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    val settings: SettingsStore = SecureSettingsStore(context)
    val repository: HubRepository = DefaultHubRepository(settings, http)
    val live = LiveHub(settings, http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(), appScope)
    val discovery = HubDiscovery(context, http)
    val direct: DirectEsp32 = DefaultDirectEsp32(http)
}
