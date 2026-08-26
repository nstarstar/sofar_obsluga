package com.example.sofarcontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.net.InetSocketAddress
import java.net.Socket

enum class PreferredConnectionMode { AUTO, FORCE_LOCAL, FORCE_CLOUD }
enum class ConnectionSource { LOCAL, CLOUD, DISCONNECTED }

data class InverterDataState(val soc: Int = 0, val batteryPower: Int = 0, val source: ConnectionSource = ConnectionSource.DISCONNECTED)

interface SolarmanApiService {
    @POST("v1.0/token")
    suspend fun getAccessToken(@Body request: Map<String, String>): Response<Map<String, String>>
}

class HybridInverterRepository(
    private val notificationHelper: NotificationHelper,
    private val localIp: String = "192.168.1.100",
    private val port: Int = 502
) {
    var currentPreferredMode = PreferredConnectionMode.AUTO

    private suspend fun isLocalAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(localIp, port), 1000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getInverterData(): InverterDataState {
        val isLocal = isLocalAvailable()
        
        return when (currentPreferredMode) {
            PreferredConnectionMode.AUTO -> if (isLocal) fetchLocal() else fetchCloud()
            PreferredConnectionMode.FORCE_LOCAL -> if (isLocal) fetchLocal() else handleDisconnected()
            PreferredConnectionMode.FORCE_CLOUD -> fetchCloud()
        }
    }

    private fun fetchLocal(): InverterDataState {
        notificationHelper.clearNotifications()
        return InverterDataState(soc = 85, batteryPower = 1200, source = ConnectionSource.LOCAL)
    }

    private fun fetchCloud(): InverterDataState {
        notificationHelper.clearNotifications()
        return InverterDataState(soc = 85, batteryPower = 1200, source = ConnectionSource.CLOUD)
    }

    private fun handleDisconnected(): InverterDataState {
        notificationHelper.showConnectionLostNotification()
        return InverterDataState(soc = 0, batteryPower = 0, source = ConnectionSource.DISCONNECTED)
    }
}
