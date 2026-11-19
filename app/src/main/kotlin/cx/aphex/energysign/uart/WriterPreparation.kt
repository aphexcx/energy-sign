package cx.aphex.energysign.uart

import cx.aphex.energysign.SignStartup

internal class WriterPreparation(
    private val startup: SignStartup,
    private val prepare: () -> Unit,
    private val post: (() -> Unit) -> Unit,
) {
    fun beforeFirstRead() { startup.optional("JSON writer preparation", prepare) }
    fun onForeground() = Unit
}
