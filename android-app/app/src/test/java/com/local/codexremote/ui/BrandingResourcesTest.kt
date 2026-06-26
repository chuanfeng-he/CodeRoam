package com.local.codexremote.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingResourcesTest {
    @Test
    fun appNameAndLauncherIconsAreWired() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(strings.contains("<string name=\"app_name\">CodeRoam</string>"))
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        assertTrue(File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").exists())
        assertTrue(File("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml").exists())
        assertTrue(File("src/main/res/drawable/ic_launcher_foreground.xml").exists())
        assertTrue(File("src/main/res/drawable/ic_launcher_monochrome.xml").exists())
        assertFalse(File("src/main/res/drawable/ic_launcher_foreground.xml").readText().contains("#394350"))
    }
}
