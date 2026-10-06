package ru.fueltracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.fueltracker.app.ui.navigation.NavGraph
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FuelTrackerTheme {
                // Отступы от системных панелей и Snackbar — в Scaffold каждого экрана
                NavGraph()
            }
        }
    }
}
