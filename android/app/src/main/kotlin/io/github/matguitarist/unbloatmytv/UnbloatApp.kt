package io.github.matguitarist.unbloatmytv

import android.app.Application
import java.io.File
import java.util.Locale

class UnbloatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: UnbloatApp
            private set

        /** Backups, the catalog cache and the ADB key, inside the app's private storage. */
        val dataDir: File get() = instance.filesDir
    }
}

/** UI text from strings.xml, for code outside Compose. Placeholders are %1$s, so everything goes in as text. */
fun str(id: Int, vararg args: Any): String =
    if (args.isEmpty()) UnbloatApp.instance.getString(id)
    else UnbloatApp.instance.getString(id, *args.map { it.toString() }.toTypedArray())

/** Catalog text comes in English and French; the phone's language picks one. */
data class LocText(val en: String, val fr: String) {
    val text: String get() = if (isFrench) fr.ifEmpty { en } else en
    override fun toString() = text

    companion object {
        val isFrench: Boolean get() = Locale.getDefault().language == "fr"
    }
}
