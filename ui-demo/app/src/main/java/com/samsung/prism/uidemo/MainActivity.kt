package com.samsung.prism.uidemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.samsung.prism.uidemo.ui.screens.MainDashboardScreen
import com.samsung.prism.uidemo.ui.theme.BgBase
import com.samsung.prism.uidemo.ui.theme.SamsungPrismUIDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SamsungPrismUIDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgBase
                ) {
                    MainDashboardScreen()
                }
            }
        }
    }
}
