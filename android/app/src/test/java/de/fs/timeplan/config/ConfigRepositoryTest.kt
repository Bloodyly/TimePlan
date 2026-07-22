package de.fs.timeplan.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

@RunWith(RobolectricTestRunner::class)
class ConfigRepositoryTest {

    @Test
    fun `returns null when nothing saved`() {
        val repo = ConfigRepository(ApplicationProvider.getApplicationContext())
        assertNull(repo.load())
    }

    @Test
    fun `saves and reloads config`() {
        val repo = ConfigRepository(ApplicationProvider.getApplicationContext())
        repo.save(ServerConfig("http://planer-server:8000", "tablet-01", "secret-token"))
        val loaded = repo.load()
        assertEquals("http://planer-server:8000", loaded?.baseUrl)
        assertEquals("tablet-01", loaded?.deviceId)
        assertEquals("secret-token", loaded?.token)
    }

    @Test
    fun `clear removes saved config`() {
        val repo = ConfigRepository(ApplicationProvider.getApplicationContext())
        repo.save(ServerConfig("url", "id", "token"))
        repo.clear()
        assertNull(repo.load())
    }
}
