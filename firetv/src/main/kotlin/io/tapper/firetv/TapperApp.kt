package io.tapper.firetv

import android.app.Application
import io.tapper.firetv.data.PlaylistRepository

class TapperApp : Application() {
    lateinit var repository: PlaylistRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = PlaylistRepository(cacheDir)
    }
}
