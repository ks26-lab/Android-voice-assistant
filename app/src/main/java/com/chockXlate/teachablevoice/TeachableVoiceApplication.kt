package com.chockXlate.teachablevoice

import android.app.Application
import com.chockXlate.teachablevoice.runtime.RuntimeLifecycleManager
import java.io.File

/**
 * Application entry point for Teachable Voice Automation.
 * Initializes persistent skill storage and transitions the runtime to READY state on process start.
 */
class TeachableVoiceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val skillsDir = File(filesDir, "skills")
        RuntimeLifecycleManager.initialize(skillsDir)
    }
}
