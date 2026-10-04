package com.chockXlate.teachablevoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chockXlate.teachablevoice.app.NativeSpeechInput
import com.chockXlate.teachablevoice.app.service.AccessibilityStatusDetector
import com.chockXlate.teachablevoice.ui.components.AccessibilityPermissionDialog
import com.chockXlate.teachablevoice.ui.components.MicrophonePermissionDialog
import com.chockXlate.teachablevoice.ui.screens.MainDashboardScreen
import com.chockXlate.teachablevoice.ui.theme.ColorBgBase
import com.chockXlate.teachablevoice.ui.theme.TeachableVoiceTheme
import com.chockXlate.teachablevoice.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {

    internal var isAccessibilityEnabled by mutableStateOf(true)
    internal var isDialogDismissedByUser by mutableStateOf(false)

    // Voice & Audio Permission States
    internal var isAudioPermissionGranted by mutableStateOf(false)
    internal var showAudioPermissionDialog by mutableStateOf(false)
    internal var isAudioPermanentlyDenied by mutableStateOf(false)
    internal var pendingVoiceStartAfterSettings by mutableStateOf(false)

    internal var isListening by mutableStateOf(false)
    internal var voiceStatusMessage by mutableStateOf<String?>(null)
    internal var voiceTranscript by mutableStateOf<String?>(null)

    private var speechInput: NativeSpeechInput? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        isAudioPermissionGranted = granted
        if (granted) {
            showAudioPermissionDialog = false
            startVoiceInput()
        } else {
            val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
            isAudioPermanentlyDenied = permanentlyDenied
            showAudioPermissionDialog = true
            voiceStatusMessage = "Microphone permission denied. You can continue using text input."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreferences.init(this)
        checkAccessibilityState(resetUserDismissal = true)
        checkAudioPermissionState()

        setContent {
            TeachableVoiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ColorBgBase
                ) {
                    MainDashboardScreen(
                        onVoiceClick = { triggerVoiceInput() },
                        isVoiceListening = isListening,
                        voiceStatusMessage = voiceStatusMessage,
                        externalVoiceTranscript = voiceTranscript,
                        onExternalVoiceTranscriptConsumed = { voiceTranscript = null }
                    )

                    if (!isAccessibilityEnabled && !isDialogDismissedByUser) {
                        AccessibilityPermissionDialog(
                            onEnableClicked = {
                                isDialogDismissedByUser = false
                                openAccessibilitySettings()
                            },
                            onCancelClicked = {
                                isDialogDismissedByUser = true
                            }
                        )
                    }

                    if (showAudioPermissionDialog) {
                        MicrophonePermissionDialog(
                            isPermanentlyDenied = isAudioPermanentlyDenied,
                            onGrantOrSettingsClicked = {
                                if (isAudioPermanentlyDenied) {
                                    showAudioPermissionDialog = false
                                    openAppSettings()
                                } else {
                                    showAudioPermissionDialog = false
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onDismissOrTextFallback = {
                                showAudioPermissionDialog = false
                                voiceStatusMessage = "Voice input cancelled. You can continue using text input."
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Every time the app becomes foreground/active, verify Accessibility and Audio permissions
        checkAccessibilityState(resetUserDismissal = false)
        val previouslyGranted = isAudioPermissionGranted
        checkAudioPermissionState()
        if (!previouslyGranted && isAudioPermissionGranted && pendingVoiceStartAfterSettings) {
            pendingVoiceStartAfterSettings = false
            showAudioPermissionDialog = false
            startVoiceInput()
        } else if (pendingVoiceStartAfterSettings && !isAudioPermissionGranted) {
            pendingVoiceStartAfterSettings = false
            voiceStatusMessage = "Microphone permission still denied. You can continue using text input."
        }
    }

    override fun onStop() {
        super.onStop()
        isDialogDismissedByUser = false
    }

    override fun onDestroy() {
        super.onDestroy()
        speechInput?.close()
        speechInput = null
    }

    internal fun checkAccessibilityState(resetUserDismissal: Boolean = false) {
        if (resetUserDismissal) {
            isDialogDismissedByUser = false
        }
        val enabled = AccessibilityStatusDetector.isAccessibilityServiceEnabled(this)
        isAccessibilityEnabled = enabled
        if (enabled) {
            isDialogDismissedByUser = false
        }
    }

    internal fun checkAudioPermissionState() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        isAudioPermissionGranted = granted
    }

    internal fun triggerVoiceInput() {
        checkAudioPermissionState()
        if (isAudioPermissionGranted) {
            toggleVoiceInput()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
            if (shouldShowRationale) {
                isAudioPermanentlyDenied = false
                showAudioPermissionDialog = true
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    internal fun toggleVoiceInput() {
        if (isListening) {
            speechInput?.toggle()
            isListening = false
        } else {
            startVoiceInput()
        }
    }

    internal fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatusMessage = "Voice input is currently unavailable. You can continue using text input."
            return
        }
        try {
            if (speechInput == null) {
                speechInput = NativeSpeechInput(
                    context = this,
                    onStatus = { status ->
                        voiceStatusMessage = status
                        if (status.startsWith("Listening", ignoreCase = true)) {
                            isListening = true
                        } else if (status.contains("stopped", ignoreCase = true) || status.contains("error", ignoreCase = true) || status.contains("recognized", ignoreCase = true)) {
                            isListening = false
                        }
                    },
                    onTranscript = { text ->
                        isListening = false
                        voiceTranscript = text
                    }
                )
            }
            isListening = true
            speechInput?.toggle()
        } catch (_: Exception) {
            isListening = false
            voiceStatusMessage = "Voice input is currently unavailable. You can continue using text input."
        }
    }

    internal fun openAccessibilitySettings() {
        try {
            val intent = AccessibilityStatusDetector.createAccessibilitySettingsIntent()
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
            }
        }
    }

    internal fun openAppSettings() {
        try {
            pendingVoiceStartAfterSettings = true
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            pendingVoiceStartAfterSettings = false
        }
    }
}
