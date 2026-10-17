package io.github.mnesimiyilmaz.extinction.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

// The launcher entry, with no UI of its own: it opens the system preview of this wallpaper, where
// it is applied to the home and lock screens. Launchers such as One UI's do not list third-party
// live wallpapers in their own wallpaper settings, so this is the way in.
class SetWallpaperActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        for (intent in pickerIntents(this)) {
            try {
                startActivity(intent)
                break
            } catch (_: ActivityNotFoundException) {
                // try the next one
            }
        }
        finish()
    }

    companion object {
        // This wallpaper's preview first, the system's live wallpaper chooser as a fallback.
        fun pickerIntents(context: Context): List<Intent> = listOf(
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, ExtinctionWallpaperService::class.java)),
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
        )
    }
}
