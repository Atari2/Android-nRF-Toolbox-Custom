/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.bps.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelUuid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nordicsemi.android.analytics.AppAnalytics
import no.nordicsemi.android.analytics.Profile
import no.nordicsemi.android.analytics.ProfileConnectedEvent
import no.nordicsemi.android.bps.view.BPSViewEvent
import no.nordicsemi.android.bps.view.BPSViewState
import no.nordicsemi.android.bps.view.DisconnectEvent
import no.nordicsemi.android.bps.view.OpenLoggerEvent
import no.nordicsemi.android.common.navigation.NavigationResult
import no.nordicsemi.android.common.navigation.Navigator
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.client.callback.BleGattClient
import no.nordicsemi.android.kotlin.ble.core.client.service.BleGattServices
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.profile.battery.BatteryLevelParser
import no.nordicsemi.android.kotlin.ble.profile.bps.data.BloodPressureMeasurementData
import no.nordicsemi.android.kotlin.ble.profile.bps.BloodPressureMeasurementParser
import no.nordicsemi.android.kotlin.ble.profile.bps.data.IntermediateCuffPressureData
import no.nordicsemi.android.kotlin.ble.profile.bps.IntermediateCuffPressureParser
import no.nordicsemi.android.toolbox.scanner.ScannerDestinationId
import java.util.*
import javax.inject.Inject

val BPS_SERVICE_UUID: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
private val BPM_CHARACTERISTIC_UUID = UUID.fromString("00002A35-0000-1000-8000-00805f9b34fb")
private val ICP_CHARACTERISTIC_UUID = UUID.fromString("00002A36-0000-1000-8000-00805f9b34fb")

private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission", "StaticFieldLeak")
@HiltViewModel
internal class BPSViewModel @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val navigationManager: Navigator,
    private val analytics: AppAnalytics
) : ViewModel() {

    private val _state = MutableStateFlow(BPSViewState())
    val state = _state.asStateFlow()

    private lateinit var client: BleGattClient

    init {
        navigationManager.navigateTo(ScannerDestinationId, ParcelUuid(BPS_SERVICE_UUID))

        navigationManager.resultFrom(ScannerDestinationId)
            .onEach { handleArgs(it) }
            .launchIn(viewModelScope)
    }

    private fun handleArgs(result: NavigationResult<ServerDevice>) {
        when (result) {
            is NavigationResult.Cancelled -> navigationManager.navigateUp()
            is NavigationResult.Success -> startGattClient(result.value)
        }
    }

    fun onEvent(event: BPSViewEvent) {
        when (event) {
            DisconnectEvent -> navigationManager.navigateUp()
            OpenLoggerEvent -> TODO()
        }
    }

    private fun startGattClient(blinkyDevice: ServerDevice) = viewModelScope.launch {
        _state.value = _state.value.copy(deviceName = blinkyDevice.name)

        client = blinkyDevice.connect(context)

        client.connectionState
            .filterNotNull()
            .onEach { onDataUpdate(it) }
            .onEach { stopIfDisconnected(it) }
            .onEach { logAnalytics(it) }
            .launchIn(viewModelScope)

        client.services
            .filterNotNull()
            .onEach { configureGatt(it) }
            .launchIn(viewModelScope)
    }

    private suspend fun configureGatt(services: BleGattServices) {
        val bpsService = services.findService(BPS_SERVICE_UUID)!!
        val bpmCharacteristic = bpsService.findCharacteristic(BPM_CHARACTERISTIC_UUID)!!
        val icpCharacteristic = bpsService.findCharacteristic(ICP_CHARACTERISTIC_UUID)
        val batteryService = services.findService(BATTERY_SERVICE_UUID)!!
        val batteryLevelCharacteristic = batteryService.findCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)!!

        batteryLevelCharacteristic.getNotifications()
            .mapNotNull { BatteryLevelParser.parse(it) }
            .onEach { onDataUpdate(it) }
            .launchIn(viewModelScope)

        bpmCharacteristic.getNotifications()
            .mapNotNull { BloodPressureMeasurementParser.parse(it) }
            .onEach { onDataUpdate(it) }
            .launchIn(viewModelScope)

        icpCharacteristic?.getNotifications()
            ?.mapNotNull { IntermediateCuffPressureParser.parse(it) }
            ?.onEach { onDataUpdate(it) }
            ?.launchIn(viewModelScope)
    }

    private fun onDataUpdate(connectionState: GattConnectionState) {
        val newResult = _state.value.result.copy(connectionState = connectionState)
        _state.value = _state.value.copy(result = newResult)
    }

    private fun onDataUpdate(batteryLevel: Int) {
        val newResult = _state.value.result.copy(batteryLevel = batteryLevel)
        _state.value = _state.value.copy(result = newResult)
    }

    private fun onDataUpdate(data: BloodPressureMeasurementData) {
        val newResult = _state.value.result.copy(bloodPressureMeasurement = data)
        _state.value = _state.value.copy(result = newResult)
    }

    private fun onDataUpdate(data: IntermediateCuffPressureData) {
        val newResult = _state.value.result.copy(intermediateCuffPressure = data)
        _state.value = _state.value.copy(result = newResult)
    }

    private fun stopIfDisconnected(connectionState: GattConnectionState) {
        if (connectionState == GattConnectionState.STATE_DISCONNECTED) {
            navigationManager.navigateUp()
        }
    }

    private fun logAnalytics(connectionState: GattConnectionState) {
        if (connectionState == GattConnectionState.STATE_CONNECTED) {
            analytics.logEvent(ProfileConnectedEvent(Profile.BPS))
        }
    }
}
