package de.fs.timeplan.settings

import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import de.fs.timeplan.R
import de.fs.timeplan.config.ConfigRepository
import de.fs.timeplan.config.ServerConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {

    @Test
    fun `saving fields persists config and finishes activity`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<EditText>(R.id.fieldBaseUrl).setText("http://planer-server:8000")
        activity.findViewById<EditText>(R.id.fieldDeviceId).setText("tablet-01")
        activity.findViewById<EditText>(R.id.fieldToken).setText("secret")
        activity.findViewById<Button>(R.id.buttonSave).performClick()

        val saved = ConfigRepository(ApplicationProvider.getApplicationContext()).load()
        assertEquals("http://planer-server:8000", saved?.baseUrl)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `saving baseUrl without scheme does not persist config or finish activity`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<EditText>(R.id.fieldBaseUrl).setText("planer-server:8000")
        activity.findViewById<EditText>(R.id.fieldDeviceId).setText("tablet-01")
        activity.findViewById<EditText>(R.id.fieldToken).setText("secret")
        activity.findViewById<Button>(R.id.buttonSave).performClick()

        val saved = ConfigRepository(ApplicationProvider.getApplicationContext()).load()
        assertEquals(null, saved)
        assertEquals(false, activity.isFinishing)
    }

    @Test
    fun `pre-fills fields from existing config`() {
        ConfigRepository(ApplicationProvider.getApplicationContext())
            .save(ServerConfig("http://x:8000", "tablet-02", "tok"))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val field = activity.findViewById<EditText>(R.id.fieldDeviceId)
        assertEquals("tablet-02", field.text.toString())
    }

    @Test
    fun `clearing config removes saved config and finishes activity`() {
        ConfigRepository(ApplicationProvider.getApplicationContext())
            .save(ServerConfig("http://x:8000", "tablet-09", "tok"))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<Button>(R.id.buttonClearConfig).performClick()

        val saved = ConfigRepository(ApplicationProvider.getApplicationContext()).load()
        assertEquals(null, saved)
        assertTrue(activity.isFinishing)
    }
}
