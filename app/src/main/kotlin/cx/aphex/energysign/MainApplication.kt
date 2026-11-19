package cx.aphex.energysign

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MainApplication : Application() {
    internal fun signViewModel(
        factory: ViewModelProvider.Factory = ViewModelProvider.AndroidViewModelFactory.getInstance(this),
    ): MainViewModel = ViewModelProvider(ViewModelStore(), factory)[MainViewModel::class.java]

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MainApplication)
            modules(appModule)
        }
    }
}
