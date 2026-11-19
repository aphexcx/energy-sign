package cx.aphex.energysign

/** Optional services may fail independently. Required message preparation and UART startup propagate failures. */
internal class SignStartup(private val warn: (String) -> Unit) {
    fun <B : Any> start(
        prepareMessages: () -> Unit,
        launchWarmUp: (Runnable) -> Unit,
        warmUp: () -> Unit,
        createBluetooth: () -> B,
        startBluetooth: (B) -> Unit,
        subscribeToWrites: (B) -> Unit,
        startDnsSd: () -> Unit,
        startLink: () -> Unit,
    ): B? {
        prepareMessages()
        optional("JSON warm-up thread") {
            launchWarmUp(Runnable { optional("JSON warm-up", warmUp) })
        }
        val bluetooth = optional("Bluetooth construction", createBluetooth)?.takeIf { manager ->
            optional("Bluetooth start") { startBluetooth(manager) } != null
        }
        if (bluetooth != null) optional("Bluetooth write subscription") { subscribeToWrites(bluetooth) }
        optional("DNS-SD startup", startDnsSd)
        startLink()
        return bluetooth
    }

    fun <T> optional(step: String, action: () -> T): T? =
        try {
            action()
        } catch (failure: Throwable) {
            // Library exception messages can contain user data. Report only the type.
            runCatching { warn("$step failed (${failure.javaClass.name}), sign link continues") }
            null
        }
}
