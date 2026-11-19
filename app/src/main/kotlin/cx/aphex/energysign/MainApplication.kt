package cx.aphex.energysign

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cx.aphex.energysign.beatlinkdata.BeatLinkDataConsumerServerService
import cx.aphex.energysign.ext.logW
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MainApplication : Application() {
    // Losing the last activity must not close UART6. This store belongs to the process.
    private val signStore = ViewModelStore()

    @MainThread
    internal fun signViewModel(
        factory: ViewModelProvider.Factory = ViewModelProvider.AndroidViewModelFactory.getInstance(this),
    ): MainViewModel = ViewModelProvider(signStore, factory)[MainViewModel::class.java]

    // The HTTP server belongs to the same process. Its binding must not retain an old activity
    // or recreate the listener on port 8080 after the last activity finishes.
    private var httpService: BeatLinkDataConsumerServerService? = null
    private val httpConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            httpService = (service as BeatLinkDataConsumerServerService.ServerBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            httpService = null // Android retains the binding and reconnects.
        }

        override fun onBindingDied(name: ComponentName) = release()
        override fun onNullBinding(name: ComponentName) = release()

        private fun release() {
            httpService = null
            httpBound = false
            SignStartup { logW(it) }.optional("BeatLink service unbinding") { unbindService(this) }
        }
    }
    private var httpBound = false

    @MainThread
    internal fun bindBeatLink() {
        if (httpBound) {
            httpService?.ensureListening()
            return
        }
        SignStartup { logW(it) }.optional("BeatLink service binding") {
            httpBound = bindService(Intent(this, BeatLinkDataConsumerServerService::class.java), httpConnection, BIND_AUTO_CREATE)
            check(httpBound)
        }
    }

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MainApplication)
            modules(appModule)
        }
    }
}
