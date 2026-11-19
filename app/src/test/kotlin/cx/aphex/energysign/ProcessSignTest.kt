package cx.aphex.energysign

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.jupiter.api.Test
import org.objenesis.ObjenesisStd
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProcessSignTest {
    @Test
    fun `two activity launches in one application open and start the sign once`() {
        val app = MainApplication()
        var opens = 0
        var starts = 0
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                opens++
                SignStartup { error(it) }.start(
                    prepareMessages = {},
                    startBluetooth = {}, startBeatLink = {}, startLink = { starts++ },
                )
                // Android Things opens UART6 in the real model constructor. Stand in only for that model.
                return ObjenesisStd().newInstance(modelClass)
            }
        }
        val firstActivityModel = app.signViewModel(factory)
        val secondActivityModel = app.signViewModel(factory)
        assertEquals(1, opens)
        assertEquals(1, starts)
        assertSame(firstActivityModel, secondActivityModel)
    }
}
