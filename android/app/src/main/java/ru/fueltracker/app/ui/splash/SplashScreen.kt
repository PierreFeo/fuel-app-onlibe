package ru.fueltracker.app.ui.splash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

/**
 * Показывается, пока читаются сохранённые токены. Куда идти дальше,
 * решает NavGraph по `AppViewModel.isLoggedIn` (без запроса к серверу).
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun SplashScreenPreview() {
    FuelTrackerTheme {
        SplashScreen()
    }
}
