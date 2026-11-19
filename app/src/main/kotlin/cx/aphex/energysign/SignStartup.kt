package cx.aphex.energysign

internal class SignStartup(private val warn: (String) -> Unit) {
    fun start(
        prepareMessages: () -> Unit,
        launchWarmUp: (Runnable) -> Unit,
        warmUp: () -> Unit,
        startBluetooth: () -> Unit,
        subscribeToWrites: () -> Unit,
        startDnsSd: () -> Unit,
        startLink: () -> Unit,
    ) {
        prepareMessages()
        launchWarmUp(Runnable(warmUp))
        startBluetooth()
        subscribeToWrites()
        startDnsSd()
        startLink()
    }
}
