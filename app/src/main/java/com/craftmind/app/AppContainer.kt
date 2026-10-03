package com.craftmind.app

import android.content.Context
import com.craftmind.app.data.media.AndroidImageReferenceRepository
import com.craftmind.app.data.settings.DataStoreSettingsRepository

/** Small application-scoped composition root for Phase 1 dependencies. */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val imageReferenceRepository = AndroidImageReferenceRepository(applicationContext.contentResolver)
    val settingsRepository = DataStoreSettingsRepository(applicationContext)
}
