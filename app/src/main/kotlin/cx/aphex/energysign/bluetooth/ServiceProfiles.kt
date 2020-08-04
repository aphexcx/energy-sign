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

package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.*
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ
import android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import java.util.*


/**
 * Implementation of the Bluetooth GATT Time Profile.
 * https://www.bluetooth.com/specifications/adopted-specifications
 */


val SERVER_CHARACTERISTIC_CONFIG_DESCRIPTOR: UUID =
    UUID.fromString("00002903-0000-1000-8000-00805f9b34fb")

/* Mandatory Client Characteristic Config Descriptor */
val CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
//val USER_DESCRIPTION_DESCRIPTOR: UUID = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")

//Services were getting jumbled up, just use one service (nordic) for now

object StringServiceProfile {
    val STRING_SERVICE_UUID: UUID = UUID.fromString("deadbeef-420d-4048-a24e-18e60180e23c")
    val CHARACTERISTIC_READER_UUID: UUID = UUID.fromString("31517c58-66bf-470c-b662-e352a6c80cba")
    val CHARACTERISTIC_INTERACTOR_UUID: UUID =
        UUID.fromString("0b89d2d4-0ea6-4141-86bb-0c5fb91ab14a")

    fun createStringService(): BluetoothGattService {
        val service = BluetoothGattService(STRING_SERVICE_UUID, SERVICE_TYPE_PRIMARY)

        // String read characteristic (read-only, supports subscriptions)
        val reader = BluetoothGattCharacteristic(
            CHARACTERISTIC_READER_UUID,
            PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
                    PERMISSION_READ or PERMISSION_WRITE
                )
            )
//            addDescriptor(BluetoothGattDescriptor(USER_DESCRIPTION_DESCRIPTOR, PERMISSION_READ).apply {
//                value = "String Service".toByteArray()
//            })
        }

        // Interactor characteristic
        val interactor = BluetoothGattCharacteristic(
            CHARACTERISTIC_INTERACTOR_UUID,
            PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE
        )
        val interactorConfig = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
            PERMISSION_READ or PERMISSION_WRITE
        )
        interactor.addDescriptor(interactorConfig)

        service.addCharacteristic(reader)
        service.addCharacteristic(interactor)
        return service
    }

}

object NordicUartServiceProfile {
    val NORDIC_UART_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NORDIC_UART_TX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NORDIC_UART_RX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    fun createNordicUartService(): BluetoothGattService {
        val service = BluetoothGattService(NORDIC_UART_SERVICE_UUID, SERVICE_TYPE_PRIMARY)

        val config = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
            PERMISSION_READ or PERMISSION_WRITE
        )

        // Uart TX characteristic 6e400002
        val uartTx = BluetoothGattCharacteristic(
            NORDIC_UART_TX_UUID,
            PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE
        )
        val writerConfig = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
            PERMISSION_READ or PERMISSION_WRITE
        )
        uartTx.addDescriptor(writerConfig)

        // Uart RX characteristic 6e400003
        val uartRx = BluetoothGattCharacteristic(
            NORDIC_UART_RX_UUID,
            PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ or PERMISSION_WRITE
        )
        val readerConfig = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
            PERMISSION_READ or PERMISSION_WRITE
        )
        uartRx.addDescriptor(readerConfig)

        service.addCharacteristic(uartTx)
        service.addCharacteristic(uartRx)
        return service
    }
}

