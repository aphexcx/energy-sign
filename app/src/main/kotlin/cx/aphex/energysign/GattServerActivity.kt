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
import cx.aphex.energysign.databinding.ActivityServerBinding
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.observeNonNull
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GattServerActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    /* Local UI */
    private lateinit var logAdapter: LogAdapter
    private lateinit var binding: ActivityServerBinding
    private var appStartTime: Long = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Kotpref.init(applicationContext)

        timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        dateFormat = DateFormat.getMediumDateFormat(this)
//        appStartTime = System.currentTimeMillis()

        // Initialize the local UI
        updateTimeView()

        logAdapter = LogAdapter(this)
        binding.logList.adapter = logAdapter

        // Devices with a display should not go to sleep
//        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Intent(this, BeatLinkDataConsumerServerService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        viewModel.currentString.observeNonNull(this) {
            binding.currentString.text = STRING_PREFIX + it
        }

        viewModel.btAdvertiseStatus.observeNonNull(this) {
            binding.advertiserStatus.text = it
        }
        viewModel.btDeviceStatus.observeNonNull(this) {
            binding.deviceStatus.text = it.btDevice.toUiString()
            binding.mtuStatus.text = "Ⓜ️${it.mtu ?: "20?"}"
        }

        ScreenLogger.logLines
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { logLine ->
                updateTimeView() //TODO mebbe run on a timer
                logAdapter.add(logLine)
                binding.logList.smoothScrollToPosition(logAdapter.itemCount)
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
                .subscribe { str ->
                    viewModel.newPostedUserMessage(str)
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
                event.keyCode == KeyEvent.KEYCODE_ESCAPE -> viewModel.escapeKey()
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
        val currentTime = System.currentTimeMillis()
        val date = Date(currentTime)
        val displayDate = dateFormat.format(date)
        val displayTime = timeFormat.format(date)

        // Calculate the app's uptime
        val appUptime = currentTime - appStartTime

        // Calculate uptime in hours, minutes, and seconds
        val hours = TimeUnit.MILLISECONDS.toHours(appUptime)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(appUptime) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(appUptime) % 60

        // Format the uptime manually as a string
        val displayUptime =
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)

        binding.localTime.text = "🆙 $displayUptime 📅 $displayDate 🕰 $displayTime"
    }

    companion object {
        private const val STRING_PREFIX: String = "🔠"
    }
}
