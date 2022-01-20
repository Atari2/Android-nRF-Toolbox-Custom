package no.nordicsemi.android.utils

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.navigation.NavController
import no.nordicsemi.android.navigation.ParcelableArgument
import no.nordicsemi.android.navigation.SuccessDestinationResult
import no.nordicsemi.ui.scanner.DiscoveredBluetoothDevice

val <T> T.exhaustive
    get() = this

val String.Companion.EMPTY
    get() = ""

fun Context.isServiceRunning(serviceClassName: String): Boolean {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val services = activityManager.getRunningServices(Integer.MAX_VALUE)
    return services.find { it.service.className == serviceClassName } != null
}

fun SuccessDestinationResult.getDevice(): DiscoveredBluetoothDevice {
    return (argument as ParcelableArgument).value as DiscoveredBluetoothDevice
}

@Composable
fun <T> NavController.consumeResult(value: String): T? {

    val secondScreenResult = currentBackStackEntry
        ?.savedStateHandle
        ?.getLiveData<T>(value)?.observeAsState()

    return secondScreenResult?.value?.also {
        currentBackStackEntry
            ?.savedStateHandle
            ?.set(value, null)
    }
}
