package com.example.sofarcontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import java.net.InetSocketAddress
import java.net.Socket

// ==========================================
// 1. MODELE DANYCH I ENUMY
// ==========================================
enum class PreferredConnectionMode { AUTO, FORCE_LOCAL, FORCE_CLOUD }
enum class ConnectionSource { LOCAL, CLOUD, DISCONNECTED }

data class InverterDataState(
    val soc: Int = 0,
    val batteryPower: Int = 0,
    val source: ConnectionSource = ConnectionSource.DISCONNECTED
)

// ==========================================
// 2. HELPER POWIADOMIEŃ SYSTEMOWYCH
// ==========================================
class NotificationHelper(private val context: Context) {
    private val channelId = "sofar_connection_channel"
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Status Połączenia Falownika",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showConnectionLostNotification() {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Utrata łączności z falownikiem")
            .setContentText("Brak połączenia lokalnego (Wi-Fi) oraz chmurowego z Sofar HYD.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }

    fun clearNotifications() {
        notificationManager.cancel(1001)
    }
}

// ==========================================
// 3. REPOZYTORIUM HYBRYDOWE (MODBUS + API)
// ==========================================
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

// ==========================================
// 4. GŁÓWNA AKTYWNOŚĆ APLIKACJI
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationHelper = NotificationHelper(this)
        val repository = HybridInverterRepository(notificationHelper)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(repository)
                }
            }
        }
    }
}

// ==========================================
// 5. WIDOK INTERFEJSU (JETPACK COMPOSE)
// ==========================================
@Composable
fun MainScreen(repository: HybridInverterRepository) {
    var state by remember { mutableStateOf(InverterDataState()) }
    var isPVOnly by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(PreferredConnectionMode.AUTO) }

    LaunchedEffect(mode) {
        repository.currentPreferredMode = mode
        state = repository.getInverterData()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Nagłówek i status połączenia
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Sofar HYD 5-KTL", style = MaterialTheme.typography.titleLarge)

            val (statusColor, statusText) = when (state.source) {
                ConnectionSource.LOCAL -> Pair(Color(0xFF4CAF50), "Wi-Fi")
                ConnectionSource.CLOUD -> Pair(Color(0xFF2196F3), "Chmura")
                ConnectionSource.DISCONNECTED -> Pair(Color(0xFFF44336), "Brak sieci")
            }

            Surface(color = statusColor.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                Text(
                    text = statusText,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Panel wyboru trybu komunikacji
        Text(text = "Tryb Połączenia", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { mode = PreferredConnectionMode.AUTO },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == PreferredConnectionMode.AUTO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            ) { Text("Auto") }

            Button(
                onClick = { mode = PreferredConnectionMode.FORCE_LOCAL },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == PreferredConnectionMode.FORCE_LOCAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            ) { Text("Wi-Fi") }

            Button(
                onClick = { mode = PreferredConnectionMode.FORCE_CLOUD },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == PreferredConnectionMode.FORCE_CLOUD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            ) { Text("Poza Wi-Fi") }
        }

        // Karta stanu baterii
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Bateria: ${state.soc}%")
                LinearProgressIndicator(
                    progress = state.soc / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
                Text(text = "Moc ładowania: ${state.batteryPower} W")
            }
        }

        // Przełącznik ładowania tylko z PV
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Ładuj TYLKO z PV", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Blokuje pobieranie energii z sieci AC do akumulatora",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = isPVOnly, onCheckedChange = { isPVOnly = it })
            }
        }
    }
}
