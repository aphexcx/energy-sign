package cx.aphex.energysign

/** Optional services may fail independently. Required message preparation and UART startup propagate failures. */
internal class SignStartup(private val warn: (String) -> Unit) {
    fun start(
        prepareMessages: () -> Unit,
        startBluetooth: () -> Unit,
        startDnsSd: () -> Unit,
        startLink: () -> Unit,
    ) {
        prepareMessages()
        startBluetooth()
        optional("DNS-SD startup", startDnsSd)
        startLink()
    }

    fun <T> optional(step: String, action: () -> T): T? =
        try {
            action()
        } catch (failure: Throwable) {
            // Library exception messages can contain user data. Report only the type.
            failed(step, failure.javaClass.name)
            null
        }

    fun failed(step: String, reason: String) {
        runCatching { warn("$step failed ($reason), sign link continues") }
    }
}


// Construction, subscription and receiver registration must succeed in order. A later
// foreground event can repeat these steps without reopening the process-owned UART.
internal class BluetoothStartup<B : Any>(
    private val startup: SignStartup,
    private val create: () -> B,
    private val subscribe: (B) -> Unit,
    private val startManager: (B) -> Unit,
    private val stopManager: (B) -> Unit,
) {
    var manager: B? = null
        private set

    fun start() {
        if (manager != null) return
        val candidate = startup.optional("Bluetooth construction", create) ?: return
        if (startup.optional("Bluetooth write subscription") { subscribe(candidate) } != null &&
            startup.optional("Bluetooth start") { startManager(candidate) } != null
        ) {
            manager = candidate
        } else {
            startup.optional("Bluetooth cleanup") { stopManager(candidate) }
        }
    }
}
