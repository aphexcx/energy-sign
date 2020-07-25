package cx.aphex.energysign

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cx.aphex.energysign.beatlinkdata.DnsSdAnnouncer
import cx.aphex.energysign.bluetooth.BluetoothStatusUpdate
import cx.aphex.energysign.bluetooth.EnergySignBluetoothManager
import cx.aphex.energysign.ext.NonNullMutableLiveData
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.uart.EnergySignUartManager
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    val currentBytes: NonNullMutableLiveData<ByteArray> = NonNullMutableLiveData(byteArrayOf())

    val btAdvertiseStatus: NonNullMutableLiveData<String> = NonNullMutableLiveData("")
    val btDeviceStatus: NonNullMutableLiveData<BluetoothStatusUpdate> =
        NonNullMutableLiveData(BluetoothStatusUpdate(null, null))
    private val signStrings: MutableList<String>

    private var currentStringIdx: Int = 0

    private var chooserIdx: Int = 0

    // Message type state mess **
    private var shouldShowNewStringAlert: Boolean = true

    private var isChooserModeEnabled: Boolean = false
    private var keyboardInputStartedAtMs: Long = 0
    private var lastKeyboardInputReceivedAtMs: Long = 0
    private var isPaused: Boolean = true
    private var isMicOn: Boolean = true
    private var sendMicChange: Boolean = false
    /**/

    private var keyboardStringBuilder: StringBuilder = StringBuilder()


    private val energySignBluetoothManager: EnergySignBluetoothManager
    private val energySignUartManager: EnergySignUartManager
    private val dnsSdAnnouncer: DnsSdAnnouncer

    init {
        signStrings = loadStrings()

        currentBytes.value = when {
            signStrings.isNotEmpty() -> signStrings.first()
            else -> "TRONCE"
        }.toByteArray()

        energySignUartManager = EnergySignUartManager(this)

        energySignBluetoothManager = EnergySignBluetoothManager(context, this)
        energySignBluetoothManager.start()

        energySignBluetoothManager.receivedBytes.subscribe { value ->

            if (String(value).startsWith("!")) { //TODO workaround for services getting jumbled up, just use one service for now
                processUartCommand(value)

            } else {
                processNewReceivedString(value)
            }
        }

        dnsSdAnnouncer = DnsSdAnnouncer(context)
        dnsSdAnnouncer.start()
    }

    override fun onCleared() {
        super.onCleared()
        energySignUartManager.stop()

        energySignBluetoothManager.stop()
    }


    private fun fatal(): Nothing {
//        finish()
        throw RuntimeException("Fatal")
    }

    fun onReadyForNextMessage() {

//            if (shouldShowNewStringAlert) {
//                currentString = NEW_MSG_ALERT
//                shouldShowNewStringAlert = false
//                //TODO may need to send the alert with the string for display nicety rather than instead of the string
//            } else {
        //TODO maybe enqueue strings to be sent instead of deriving them here

        val timeElapsedSinceLastInput =
            System.currentTimeMillis() - lastKeyboardInputReceivedAtMs
        if (timeElapsedSinceLastInput > KEYBOARD_INPUT_TIMEOUT_MS) {
            keyboardStringBuilder.clear()
        }

        currentBytes.value = when {
            timeElapsedSinceLastInput < KEYBOARD_INPUT_TIMEOUT_MS -> {
                if (timeElapsedSinceLastInput > (KEYBOARD_INPUT_TIMEOUT_MS - KEYBOARD_INPUT_WARNING_MS)) {
                    // Running out of input time! Display this in input warning mode.
                    byteArrayOf(EOT) + keyboardStringBuilder.toString().plus('_').toByteArray()
                } else {
                    byteArrayOf(ENQ) + keyboardStringBuilder.toString().plus('_').toByteArray()
                }
            }
            signStrings.isEmpty() -> byteArrayOf()
            shouldShowNewStringAlert -> {
                shouldShowNewStringAlert = false
                logD("The next string will show the new string alert!")
                byteArrayOf(BEL) + signStrings[currentStringIdx].toByteArray()
            }
            isChooserModeEnabled -> {
                logD("The next string will show as chooser!")
                byteArrayOf(SOH) + "$chooserIdx/${signStrings.lastIndex}".toByteArray() +
                        byteArrayOf(DLE) + signStrings[chooserIdx].toByteArray()
            }
            sendMicChange -> {
                sendMicChange = false
                if (isMicOn) {
                    logD("Enabling beat detector microphone!")
                    byteArrayOf(STX) + signStrings[currentStringIdx].toByteArray()
                } else {
                    logD("Disabling beat detector microphone!")
                    byteArrayOf(ETX) + signStrings[currentStringIdx].toByteArray()
                }
            }
            else -> {
                logD("Reading signStrings[$currentStringIdx]")
                signStrings[currentStringIdx].toByteArray()
            }
        }

        energySignUartManager.write(currentBytes.value)

        if (!isPaused) { //if not paused, advance index
            currentStringIdx =
                if (currentStringIdx < signStrings.lastIndex) currentStringIdx + 1 else 0
        }
    }

    private fun processUartCommand(value: ByteArray) {
        logD("Procesing Uart command ${String(value)}")
        when (val cmd = String(value)) {
            "!choose" -> {
                chooserIdx = currentStringIdx
                isChooserModeEnabled = true
            }
            "!prev" -> {
                chooserIdx = if (chooserIdx > 0) chooserIdx - 1 else 0
                currentStringIdx = if (currentStringIdx > 0) currentStringIdx - 1 else 0
            }
            "!next" -> {
                chooserIdx =
                    if (chooserIdx < signStrings.lastIndex) chooserIdx + 1 else signStrings.lastIndex
                currentStringIdx =
                    if (currentStringIdx < signStrings.lastIndex) currentStringIdx + 1 else signStrings.lastIndex
            }
            "!first" -> {
                chooserIdx = 0
                currentStringIdx = chooserIdx
            }
            "!last" -> {
                chooserIdx = signStrings.lastIndex
                currentStringIdx = chooserIdx
            }
            "!delete" -> {
                signStrings.removeAt(chooserIdx)
                saveStrings(signStrings)
                //clamp //TODO
                chooserIdx =
                    if (chooserIdx >= signStrings.lastIndex) signStrings.lastIndex else chooserIdx
            }
            "!endchoose" -> {
                currentStringIdx = chooserIdx
                isChooserModeEnabled = false
            }
            "!pause" -> {
                isPaused = true
            }
            "!unpause" -> {
                isPaused = false
            }
            "!micOn" -> {
                isMicOn = true
                sendMicChange = true
            }
            "!micOff" -> {
                isMicOn = false
                sendMicChange = true
            }
        }
    }

    fun processNewReceivedString(value: ByteArray) {
        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
            pushStringOnList(it.replace('•', '*'))
        }

        saveStrings(signStrings)
        shouldShowNewStringAlert = true
        currentStringIdx = 0
    }

    /** Return the
     * //TODO last [MAX_SIGN_STRINGS]
     * strings from the sign strings file. */
    private fun loadStrings(): MutableList<String> =
        File(context.filesDir, SIGN_STRINGS_FILE_NAME).run {
            when (createNewFile()) {
                true -> logD("$SIGN_STRINGS_FILE_NAME does not exist; created new.")
                else -> logD("$SIGN_STRINGS_FILE_NAME exists. Reading...")
            }

            bufferedReader().use {
                it.lineSequence()
//                            .take(MAX_SIGN_STRINGS)
                    .toMutableList()
                    .asReversed() //TODO check if asreversed is doing the right thing here
            }.also {
                logD(
                    "Read ${it.size} lines from ${SIGN_STRINGS_FILE_NAME}! Here are the first 10: [${it.take(
                        10
                    ).joinToString(", ")}]"
                )
            }
        }

    /** Pushes a string onto the top of the signStrings list. */
    private fun pushStringOnList(value: String) {
        signStrings.add(0, value)
    }

    /** Write out the list of strings to the file */
    private fun saveStrings(strings: List<String>) {
        try {
            strings.reversed().toFile(File(context.filesDir, SIGN_STRINGS_FILE_NAME))
        } catch (e: Throwable) {
            logW("Exception when saving ${SIGN_STRINGS_FILE_NAME}! ${e.message}")
        }
    }

    internal fun processNewKeyboardKey(key: Char) {
        if (key.toInt() == 0) {
            logD("Invalid key!")
            return
        }
        if (keyboardStringBuilder.isEmpty()) {
            keyboardInputStartedAtMs = System.currentTimeMillis()
        }
        lastKeyboardInputReceivedAtMs = System.currentTimeMillis()
        keyboardStringBuilder.append(key)
    }

    internal fun deleteKey() {
        if (keyboardStringBuilder.isNotEmpty()) {
            lastKeyboardInputReceivedAtMs = System.currentTimeMillis()
            keyboardStringBuilder.deleteCharAt(keyboardStringBuilder.lastIndex)
        }
    }

    internal fun submitKeyboardInput() {
        val totalKeyboardInputTimeElapsed = System.currentTimeMillis() - keyboardInputStartedAtMs
//        if (keyboardStringBuilder.length < 4) {
//            // LONGER!\
//
//
//        } else if (keyboardStringBuilder.toString().split(' ').any { it in Dictionary.ENGLISH_DICT })
        // else if ! any words are in dict
        // NO SPAM!
        if (totalKeyboardInputTimeElapsed > MINIMUM_INPUT_ENTRY_PERIOD
            && keyboardStringBuilder.isNotBlank()
        ) {
            lastKeyboardInputReceivedAtMs = -1
            processNewReceivedString(keyboardStringBuilder.toString().toByteArray())
            keyboardStringBuilder.clear()
        }
    }

    fun updateAdvertiseStatus(status: String) {
        btAdvertiseStatus.postValue(status)

    }

    fun onBtStatusUpdate(bluetoothStatusUpdate: BluetoothStatusUpdate) {
        btDeviceStatus.postValue(bluetoothStatusUpdate)
    }

    companion object {
        private const val SIGN_STRINGS_FILE_NAME = "signstrings.txt"
        private const val MAX_SIGN_STRINGS: Int = 1000


        private const val BEL: Byte = 7
        private const val SOH: Byte = 1
        private const val STX: Byte = 2
        private const val ETX: Byte = 3
        private const val EOT: Byte = 4
        private const val ENQ: Byte = 5
        private const val DLE: Byte = 10

        private const val MINIMUM_INPUT_ENTRY_PERIOD: Int = 5_000
        private const val KEYBOARD_INPUT_TIMEOUT_MS: Int = 30_000
        private const val KEYBOARD_INPUT_WARNING_MS: Int = 7_000
    }
}
