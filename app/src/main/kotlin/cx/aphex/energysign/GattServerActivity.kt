/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cx.aphex.energysign

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.chibatching.kotpref.Kotpref
import cx.aphex.energysign.beatlinkdata.BeatLinkDataConsumerServerService
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.observeNonNull
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_server.*
import java.text.SimpleDateFormat
import java.util.*

class GattServerActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    /* Local UI */
    private lateinit var logAdapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)
        Kotpref.init(applicationContext)

        timeFormat = SimpleDateFormat("HH:mm:ss")
        dateFormat = DateFormat.getMediumDateFormat(this)

        // Initialize the local UI
        updateTimeView()

        logAdapter = LogAdapter(this)
        log_list.adapter = logAdapter

        // Devices with a display should not go to sleep
//        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Intent(this, BeatLinkDataConsumerServerService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

//        val profileManager = BluetoothProfileManager.getInstance()
//        val enabledProfiles = profileManager.enabledProfiles
//        bluetoothAdapter.startDiscovery()

        viewModel.currentString.observeNonNull(this) {
            current_string.text = STRING_PREFIX + it
        }

        viewModel.btAdvertiseStatus.observeNonNull(this) {
            advertiser_status.text = it
        }
        viewModel.btDeviceStatus.observeNonNull(this) {
            device_status.text = it.btDevice.toUiString()
            mtu_status.text = "Ⓜ️${it.mtu ?: "20?"}"
        }

        ScreenLogger.logLines
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { logLine ->
                updateTimeView() //TODO mebbe run on a timer
                logAdapter.add(logLine)
                log_list.smoothScrollToPosition(logAdapter.itemCount)
            }

    }

    var nowPlayingTrackSubscription: Disposable? = null
    var newPostedUserMessageSubscription: Disposable? = null

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as BeatLinkDataConsumerServerService.ServerBinder
            nowPlayingTrackSubscription = binder.getService().nowPlayingTrack
                .subscribe { track ->
                    viewModel.nowPlaying(track)
                }

            newPostedUserMessageSubscription = binder.getService().newUserMessages
                .subscribe { userMessage ->
                    viewModel.newPostedUserMessage(userMessage)
                }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            nowPlayingTrackSubscription?.dispose()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key: Char = event.keyCharacterMap.get(event.keyCode, event.metaState).toChar()
        if (event.action == ACTION_DOWN) {
            logD("Keypress! $key")
            when {
                event.keyCode == KeyEvent.KEYCODE_ENTER -> viewModel.submitKeyboardInput()
                event.keyCode == KeyEvent.KEYCODE_DEL -> viewModel.deleteKey()
                event.keyCode == KeyEvent.KEYCODE_SPACE -> viewModel.processNewKeyboardKey(' ')
                event.isPrintingKey -> viewModel.processNewKeyboardKey(key)
                else -> return true // super.dispatchKeyEvent(event)
            }
        }
        return true
    }

    private fun BluetoothDevice?.toUiString(): String {
        return this?.let { "📲${it.name ?: ""}${it}" } ?: "📴"
    }

    private lateinit var timeFormat: SimpleDateFormat
    private lateinit var dateFormat: java.text.DateFormat

    /**
     * Update graphical UI on devices that support it with the current time.
     */
    private fun updateTimeView() {
        val date = Date(System.currentTimeMillis())
        val displayDate = dateFormat.format(date)
        val displayTime = timeFormat.format(date)
        local_time.text = "$displayDate 🕰 $displayTime"
    }

    companion object {
        private const val STRING_PREFIX: String = "🔠"
    }
}
