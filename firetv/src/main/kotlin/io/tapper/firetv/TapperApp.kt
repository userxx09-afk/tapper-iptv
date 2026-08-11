package io.tapper.firetv

import android.app.Application
import io.tapper.firetv.data.CredentialVault
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.EpgRepository
import io.tapper.firetv.data.FavoritesStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.SourceStore

class TapperApp : Application() {
    lateinit var vault: CredentialVault; private set
    lateinit var sourceStore: SourceStore; private set
    lateinit var favorites: FavoritesStore; private set
    lateinit var repository: PlaylistRepository; private set
    lateinit var epgDb: EpgDatabase; private set
    lateinit var epg: EpgRepository; private set

    override fun onCreate() {
        super.onCreate()
        vault = CredentialVault(this)
        sourceStore = SourceStore(this)
        favorites = FavoritesStore(this)
        repository = PlaylistRepository(cacheDir, vault)
        epgDb = EpgDatabase(this)
        epg = EpgRepository(epgDb, vault)
    }
}
