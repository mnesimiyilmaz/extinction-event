package io.github.mnesimiyilmaz.extinction

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mnesimiyilmaz.extinction.wallpaper.ExtinctionWallpaperService
import io.github.mnesimiyilmaz.extinction.wallpaper.SetWallpaperActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetWallpaperActivityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // The app's only way in: without it the wallpaper cannot be found on One UI at all.
    @Test
    fun appearsInTheLauncher() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("no launcher entry", launch)
        assertEquals(SetWallpaperActivity::class.java.name, launch!!.component!!.className)
    }

    @Test
    fun opensThisWallpaperInTheSystemPreview() {
        val intent = SetWallpaperActivity.pickerIntents(context).first()
        assertEquals(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, intent.action)
        @Suppress("DEPRECATION")
        assertEquals(
            ComponentName(context, ExtinctionWallpaperService::class.java),
            intent.getParcelableExtra<ComponentName>(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT),
        )
        assertNotNull("no system activity shows a live wallpaper preview", intent.resolveActivity(context.packageManager))
    }

    @Test
    fun fallsBackToTheLiveWallpaperChooser() {
        assertEquals(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER, SetWallpaperActivity.pickerIntents(context).last().action)
    }

    private fun Intent.resolveActivity(pm: android.content.pm.PackageManager) = pm.resolveActivity(this, 0)
}
