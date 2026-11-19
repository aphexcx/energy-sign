package cx.aphex.energysign

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MainApplication : Application() {
    // Losing the last activity must not close UART6. This store belongs to the process.
    private val signStore = ViewModelStore()

    @MainThread
    internal fun signViewModel(
        factory: ViewModelProvider.Factory = ViewModelProvider.AndroidViewModelFactory.getInstance(this),
    ): MainViewModel = ViewModelProvider(signStore, factory)[MainViewModel::class.java]

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MainApplication)
            modules(appModule)
        }
    }
}
