package com.clawwatch

import android.app.Application
import com.clawwatch.data.LocationProvider
import com.clawwatch.data.OpenClawClient
import com.clawwatch.data.SettingsStore
import com.clawwatch.voice.VoiceOutput

class ClawWatchApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var openClawClient: OpenClawClient
        private set
    lateinit var voiceOutput: VoiceOutput
        private set
    lateinit var locationProvider: LocationProvider
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        openClawClient = OpenClawClient()
        voiceOutput = VoiceOutput(this)
        locationProvider = LocationProvider(this)
    }
}
