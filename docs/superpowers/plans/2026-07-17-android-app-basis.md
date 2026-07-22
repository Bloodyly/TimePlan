# TimePlan Android App-Basis (Meilenstein 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kotlin-Android-App-Grundgerüst für das Galaxy Tab Active3: Projekt-Setup, Konfigurationsseite (Server-URL/Gerätekennung/Token), Wochenraster mit echten Serverdaten (Monteure + Azubi-Block) und Wochennavigation (vor/zurück/heute) — vollständig gegen `docs/api-contract.md`.

**Architecture:** Klassische Android Views (kein Compose), Schichtung `model` (Datenklassen/JSON) → `net` (Wochenlogik + REST-Client) → `grid` (Raster-Aufbau + RecyclerView-Adapter) → `week`/`settings` (Presenter + Activities). Alle Netzwerk-Aufrufe sind synchron im API-Client und werden von der Activity über Kotlin Coroutines (`Dispatchers.IO`) aufgerufen; ein reiner `WeekPresenter` kombiniert Worker- und Wochenabruf und ist ohne Android-Framework testbar.

**Tech Stack:** Kotlin, Gradle 8.9 (Kotlin DSL), Android Gradle Plugin 8.5.2, minSdk 24 / targetSdk 34 / compileSdk 34, OkHttp 4.12, kotlinx.serialization 1.6.3, AndroidX AppCompat/RecyclerView, JUnit4 + Robolectric 4.13 + OkHttp MockWebServer für Tests (kein Gerät/Emulator, kein laufender TimePlan-Server nötig).

## Global Constraints

- Paketname: **`de.fs.timeplan`** (folgt der bestehenden Namenskonvention `de.fs.<app>` aus Maintenance-Pro)
- `minSdk 24`, `targetSdk 34`, `compileSdk 34` — passend zur installierten SDK-Plattform `android-34`
- Gradle **8.9**, AGP **8.5.2**, Kotlin **1.9.24** (aufeinander abgestimmtes Trio, nicht ändern)
- Klassische Android Views, kein Jetpack Compose
- **Kein laufender TimePlan-Server in dieser Umgebung verfügbar.** Jede Aufgabe, die Netzwerkcode testet, MUSS dies über OkHttp `MockWebServer` tun; kein Task darf einen echten Server oder ein verbundenes Gerät/Emulator voraussetzen, um seine Tests zu bestehen
- API-Vertrag: `docs/api-contract.md` (Repo-Root-relativ, ein Verzeichnis über `android/`) — Feldnamen/Typen/Statuscodes exakt danach
- Alle Gradle-Kommandos aus `android/` heraus über `./gradlew`, nie ein globales `gradle`
- **JDK-Bereitstellung in dieser Umgebung:** Vor jedem `./gradlew`-Aufruf muss das im Android-Studio-Flatpak gebündelte JBR ermittelt werden:
  ```bash
  export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
  ```
  Diese Zeile steht in jedem Task vor dem jeweiligen `./gradlew`-Aufruf.
- Android-SDK-Pfad: `/home/admin/Android/Sdk` (wird in Task 1 als `android/local.properties` hinterlegt, `.gitignore`t)
- Alle Pfadangaben sind relativ zu `/home/admin/Projekte/TimePlan/` (Repo-Root), außer wenn explizit `android/...` als Unterverzeichnis genannt wird

---

### Task 1: Gradle-Projekt-Grundgerüst + Wrapper

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/local.properties`, `android/.gitignore`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/res/layout/activity_week.xml` (Platzhalter, wird in Task 8 ersetzt)
- Create: `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt` (Platzhalter, wird in Task 8 ersetzt)
- Generate (nicht von Hand schreiben, siehe Step 3): `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradle/wrapper/gradle-wrapper.jar`

**Interfaces:**
- Produces: ein baubares Gradle/AGP/Kotlin-Projekt unter `android/`, das `./gradlew :app:assembleDebug` erfolgreich durchläuft. Alle folgenden Tasks legen Quellen unter `android/app/src/main/java/de/fs/timeplan/...` bzw. `android/app/src/test/java/de/fs/timeplan/...` ab und erweitern `android/app/build.gradle.kts` bei Bedarf um Dependencies (in diesem Task bereits vollständig vorgesehen, siehe unten).

- [ ] **Step 1: Root-Gradle-Dateien anlegen**

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "TimePlan"
include(":app")
```

`android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
```

`android/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android/local.properties` (wird NICHT committet, siehe `.gitignore`, aber lokal für den Build benötigt):
```properties
sdk.dir=/home/admin/Android/Sdk
```

`android/.gitignore`:
```text
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build/
app/build/
/captures/
.externalNativeBuild/
.cxx/
```

- [ ] **Step 2: App-Modul anlegen**

`android/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "de.fs.timeplan"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.fs.timeplan"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
```

`android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.TimePlan">

        <activity
            android:name=".WeekActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

`android/app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">TimePlan</string>
</resources>
```

`android/app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.TimePlan" parent="Theme.MaterialComponents.DayNight.NoActionBar" />
</resources>
```

`android/app/src/main/res/layout/activity_week.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name" />

</LinearLayout>
```

`android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`:
```kotlin
package de.fs.timeplan

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class WeekActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_week)
    }
}
```

- [ ] **Step 3: Gradle-Wrapper bootstrappen**

In dieser Umgebung existiert kein globales `gradle`-Kommando. Ein Gradle-8.9-Vertriebspaket wird einmalig heruntergeladen, um damit den Wrapper zu erzeugen:

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
mkdir -p /tmp/timeplan-gradle-bootstrap && cd /tmp/timeplan-gradle-bootstrap
if [ ! -d gradle-8.9 ]; then
  curl -fsSL -o gradle-8.9-bin.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  python3 -c "import zipfile; zipfile.ZipFile('gradle-8.9-bin.zip').extractall('.')"
fi
chmod +x gradle-8.9/bin/gradle
cd /home/admin/Projekte/TimePlan/android
/tmp/timeplan-gradle-bootstrap/gradle-8.9/bin/gradle wrapper --gradle-version 8.9 --distribution-type bin --console=plain
```

Erwartet: `BUILD SUCCESSFUL`, und `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradle/wrapper/gradle-wrapper.jar` existieren.

- [ ] **Step 4: Build verifizieren**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
chmod +x gradlew
./gradlew :app:assembleDebug --console=plain
```
Erwartet: `BUILD SUCCESSFUL`, Ausgabedatei unter `android/app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/settings.gradle.kts android/build.gradle.kts android/gradle.properties \
  android/.gitignore android/app/build.gradle.kts android/app/src \
  android/gradlew android/gradlew.bat android/gradle
git commit -m "feat(android): Gradle-Projekt-Grundgerüst mit Wrapper und leerer WeekActivity"
```
(`android/local.properties` bleibt dank `.gitignore` unversioniert.)

---

### Task 2: Datenmodelle + JSON-Serialisierung

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/model/Models.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/model/ModelSerializationTest.kt`

**Interfaces:**
- Consumes: nichts (reine Datenklassen)
- Produces: `data class Worker(id, number, name, category, position, active: Boolean, revision: Int)` mit `val displayName: String` (`"{number} {name}"`), `val isMonteur: Boolean`, `val isAzubi: Boolean`; `data class Week(id, status, revision: Int)`; `data class Entry(id, cell_id, type, author_type, author_id, content: JsonElement, conflict_of: String?, created_at, updated_at, revision: Int)` mit Extension `fun Entry.textOrNull(): String?`; `data class WorkersResponse(workers: List<Worker>)`; `data class WeekBundle(week: Week, dates: List<String>, entries: List<Entry>)`; `data class StatusResponse(status: String, revision: Int)`. Alle mit `@Serializable`, alle Felder passend zu `docs/api-contract.md`. Von Task 5 (API-Client), Task 6 (Grid-Aufbau) und Task 8 (Presenter) verwendet.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/model/ModelSerializationTest.kt`:
```kotlin
package de.fs.timeplan.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ModelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes worker from api contract shape`() {
        val worker = json.decodeFromString<Worker>(
            """{"id":"w-1","number":"144","name":"Albrecht","category":"monteur","position":1,"active":true,"revision":3}"""
        )
        assertEquals("144 Albrecht", worker.displayName)
        assertEquals(true, worker.isMonteur)
        assertEquals(false, worker.isAzubi)
    }

    @Test
    fun `decodes text entry and extracts text`() {
        val entry = json.decodeFromString<Entry>(
            """{"id":"e-127","cell_id":"2026-W31_w-1_2026-07-30","type":"text",
               "author_type":"web","author_id":"verwaltung-1",
               "content":{"text":"Material bei Lager 2 abholen"},
               "conflict_of":null,"created_at":"2026-07-30T07:30:00",
               "updated_at":"2026-07-30T07:30:00","revision":1}"""
        )
        assertEquals("Material bei Lager 2 abholen", entry.textOrNull())
    }

    @Test
    fun `drawing entry has no text`() {
        val entry = json.decodeFromString<Entry>(
            """{"id":"e-113","cell_id":"2026-W31_w-1_2026-07-30","type":"drawing",
               "author_type":"tablet","author_id":"tablet-01",
               "content":{"canvas_width":1200,"canvas_height":500,"strokes":[]},
               "conflict_of":null,"created_at":"now","updated_at":"now","revision":1}"""
        )
        assertNull(entry.textOrNull())
    }

    @Test
    fun `decodes week bundle`() {
        val bundle = json.decodeFromString<WeekBundle>(
            """{"week":{"id":"2026-W31","status":"OPEN","revision":42},
               "dates":["2026-07-27","2026-07-28","2026-07-29","2026-07-30","2026-07-31","2026-08-01","2026-08-02"],
               "entries":[]}"""
        )
        assertEquals("2026-W31", bundle.week.id)
        assertEquals(7, bundle.dates.size)
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (Compile-Fehler, `Models.kt` existiert noch nicht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/model/Models.kt`:
```kotlin
package de.fs.timeplan.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Worker(
    val id: String,
    val number: String,
    val name: String,
    val category: String,
    val position: Int,
    val active: Boolean,
    val revision: Int
) {
    val displayName: String get() = "$number $name"
    val isMonteur: Boolean get() = category == "monteur"
    val isAzubi: Boolean get() = category == "azubi"
}

@Serializable
data class Week(
    val id: String,
    val status: String,
    val revision: Int
)

@Serializable
data class Entry(
    val id: String,
    val cell_id: String,
    val type: String,
    val author_type: String,
    val author_id: String,
    val content: JsonElement,
    val conflict_of: String? = null,
    val created_at: String,
    val updated_at: String,
    val revision: Int
)

fun Entry.textOrNull(): String? {
    if (type != "text") return null
    val obj = content as? JsonObject ?: return null
    val value = obj["text"] as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

@Serializable
data class WorkersResponse(val workers: List<Worker>)

@Serializable
data class WeekBundle(
    val week: Week,
    val dates: List<String>,
    val entries: List<Entry>
)

@Serializable
data class StatusResponse(val status: String, val revision: Int)
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (4 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/model android/app/src/test/java/de/fs/timeplan/model
git commit -m "feat(android): Datenmodelle mit kotlinx.serialization gemäß API-Vertrag"
```

---

### Task 3: ISO-Wochenlogik mit Core-Library-Desugaring

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/net/WeekId.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/net/WeekIdTest.kt`

**Interfaces:**
- Consumes: nichts (reine Kotlin/`java.time`-Logik; Desugaring bereits in Task 1 aktiviert)
- Produces: `object WeekId` mit `fun parse(weekId: String): Pair<Int, Int>` (wirft `IllegalArgumentException`), `fun weekDates(weekId: String): List<LocalDate>` (Mo–So), `fun currentWeekId(today: LocalDate = LocalDate.now()): String`, `fun adjacentWeekId(weekId: String, delta: Int): String`, `fun makeCellId(weekId: String, workerId: String, date: LocalDate): String`, `fun makeCellId(weekId: String, workerId: String, dateIso: String): String` (Overload), `data class CellRef(weekId: String, workerId: String, date: String)`, `fun parseCellId(cellId: String): CellRef`. Kotlin-Äquivalent zu `server/app/weeks.py`. Von Task 6 (Grid-Aufbau) und Task 8 (Presenter/Activity) verwendet.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/net/WeekIdTest.kt`:
```kotlin
package de.fs.timeplan.net

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.time.LocalDate

class WeekIdTest {

    @Test
    fun `week dates match iso week`() {
        val dates = WeekId.weekDates("2026-W31")
        assertEquals(LocalDate.of(2026, 7, 27), dates[0])
        assertEquals(LocalDate.of(2026, 8, 2), dates[6])
    }

    @Test
    fun `adjacent week crosses year boundary`() {
        assertEquals("2025-W52", WeekId.adjacentWeekId("2026-W01", -1))
        assertEquals("2026-W01", WeekId.adjacentWeekId("2025-W52", 1))
    }

    @Test
    fun `parse rejects invalid ids`() {
        assertThrows(IllegalArgumentException::class.java) { WeekId.parse("2026-31") }
        assertThrows(IllegalArgumentException::class.java) { WeekId.parse("2026-W60") }
    }

    @Test
    fun `cell id round trip`() {
        val cellId = WeekId.makeCellId("2026-W31", "w-abc12345", LocalDate.of(2026, 7, 30))
        assertEquals("2026-W31_w-abc12345_2026-07-30", cellId)
        val ref = WeekId.parseCellId(cellId)
        assertEquals("2026-W31", ref.weekId)
        assertEquals("w-abc12345", ref.workerId)
        assertEquals("2026-07-30", ref.date)
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`WeekId` existiert noch nicht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/net/WeekId.kt`:
```kotlin
package de.fs.timeplan.net

import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.temporal.WeekFields

data class CellRef(val weekId: String, val workerId: String, val date: String)

object WeekId {
    private val WEEK_ID_REGEX = Regex("""^(\d{4})-W(\d{2})$""")

    fun parse(weekId: String): Pair<Int, Int> {
        val match = WEEK_ID_REGEX.matchEntire(weekId)
            ?: throw IllegalArgumentException("invalid week id: $weekId")
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toInt()
        val wf = WeekFields.ISO
        val jan4 = LocalDate.of(year, 1, 4)
        val monday = jan4.with(ChronoField.DAY_OF_WEEK, 1L).plusWeeks((week - 1).toLong())
        if (monday.get(wf.weekBasedYear()) != year || monday.get(wf.weekOfWeekBasedYear()) != week) {
            throw IllegalArgumentException("invalid week id: $weekId")
        }
        return year to week
    }

    fun weekDates(weekId: String): List<LocalDate> {
        val (year, week) = parse(weekId)
        val jan4 = LocalDate.of(year, 1, 4)
        val monday = jan4.with(ChronoField.DAY_OF_WEEK, 1L).plusWeeks((week - 1).toLong())
        return (0..6).map { monday.plusDays(it.toLong()) }
    }

    fun currentWeekId(today: LocalDate = LocalDate.now()): String {
        val wf = WeekFields.ISO
        val year = today.get(wf.weekBasedYear())
        val week = today.get(wf.weekOfWeekBasedYear())
        return "%04d-W%02d".format(year, week)
    }

    fun adjacentWeekId(weekId: String, delta: Int): String {
        val monday = weekDates(weekId)[0].plusWeeks(delta.toLong())
        return currentWeekId(monday)
    }

    fun makeCellId(weekId: String, workerId: String, date: LocalDate): String =
        "${weekId}_${workerId}_${date}"

    fun makeCellId(weekId: String, workerId: String, dateIso: String): String =
        "${weekId}_${workerId}_${dateIso}"

    fun parseCellId(cellId: String): CellRef {
        val parts = cellId.split("_")
        require(parts.size == 3) { "invalid cell id: $cellId" }
        return CellRef(parts[0], parts[1], parts[2])
    }
}
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (4 neue + 4 bestehende = 8 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/net/WeekId.kt android/app/src/test/java/de/fs/timeplan/net/WeekIdTest.kt
git commit -m "feat(android): ISO-Wochenlogik (WeekId) als Kotlin-Pendant zu server/app/weeks.py"
```

---

### Task 4: Konfigurationsspeicher (ConfigRepository)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/config/ConfigRepository.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/config/ConfigRepositoryTest.kt`

**Interfaces:**
- Consumes: `android.content.Context` (per Robolectric im Test, per Activity-Context in Task 7/8)
- Produces: `data class ServerConfig(baseUrl: String, deviceId: String, token: String)`; `class ConfigRepository(context: Context)` mit `fun load(): ServerConfig?` (null wenn unvollständig), `fun save(config: ServerConfig)`, `fun clear()`. Speicherung über `SharedPreferences`. Von Task 5 (API-Client-Konstruktion), Task 7 (SettingsActivity) und Task 8 (WeekActivity) verwendet.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/config/ConfigRepositoryTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`ConfigRepository` existiert noch nicht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/config/ConfigRepository.kt`:
```kotlin
package de.fs.timeplan.config

import android.content.Context

data class ServerConfig(
    val baseUrl: String,
    val deviceId: String,
    val token: String
)

class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return ServerConfig(baseUrl, deviceId, token)
    }

    fun save(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_DEVICE_ID, config.deviceId)
            .putString(KEY_TOKEN, config.token)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "timeplan_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"
    }
}
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (3 neue + 8 bestehende = 11 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/config android/app/src/test/java/de/fs/timeplan/config
git commit -m "feat(android): ConfigRepository für Server-URL, Gerätekennung und Token"
```

---

### Task 5: REST-API-Client

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/net/ApiResult.kt`, `android/app/src/main/java/de/fs/timeplan/net/TimePlanApi.kt`, `android/app/src/main/java/de/fs/timeplan/net/TimePlanApiClient.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/net/TimePlanApiClientTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.config.ServerConfig` (Task 4), `de.fs.timeplan.model.{StatusResponse,WorkersResponse,WeekBundle}` (Task 2)
- Produces: `sealed class ApiResult<out T>` mit `Success(data: T)`, `Error(code: Int, message: String)`, `NetworkFailure(message: String)`; `interface TimePlanApi { fun getStatus(): ApiResult<StatusResponse>; fun getWorkers(): ApiResult<WorkersResponse>; fun getWeek(weekId: String): ApiResult<WeekBundle> }`; `class TimePlanApiClient(config: ServerConfig, client: OkHttpClient = OkHttpClient(), json: Json = Json{ignoreUnknownKeys=true}) : TimePlanApi`. Sendet `Authorization: Bearer <token>` auf jedem Request. Von Task 8 (WeekPresenter, per Interface `TimePlanApi`) verwendet.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/net/TimePlanApiClientTest.kt`:
```kotlin
package de.fs.timeplan.net

import de.fs.timeplan.config.ServerConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TimePlanApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TimePlanApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = ServerConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "tablet-01",
            token = "testtoken"
        )
        client = TimePlanApiClient(config)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getStatus parses success response`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","revision":5}"""))
        val result = client.getStatus()
        require(result is ApiResult.Success)
        assertEquals("ok", result.data.status)
        assertEquals(5, result.data.revision)
    }

    @Test
    fun `getWorkers returns Error on 401`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid device token"}"""))
        val result = client.getWorkers()
        require(result is ApiResult.Error)
        assertEquals(401, result.code)
    }

    @Test
    fun `getWeek returns NetworkFailure when server unreachable`() {
        server.shutdown()
        val result = client.getWeek("2026-W31")
        assertTrue(result is ApiResult.NetworkFailure)
    }

    @Test
    fun `request sends bearer token header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"workers":[]}"""))
        client.getWorkers()
        val recorded = server.takeRequest()
        assertEquals("Bearer testtoken", recorded.getHeader("Authorization"))
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`TimePlanApiClient` existiert noch nicht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/net/ApiResult.kt`:
```kotlin
package de.fs.timeplan.net

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkFailure(val message: String) : ApiResult<Nothing>()
}
```

`android/app/src/main/java/de/fs/timeplan/net/TimePlanApi.kt`:
```kotlin
package de.fs.timeplan.net

import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.WorkersResponse

interface TimePlanApi {
    fun getStatus(): ApiResult<StatusResponse>
    fun getWorkers(): ApiResult<WorkersResponse>
    fun getWeek(weekId: String): ApiResult<WeekBundle>
}
```

`android/app/src/main/java/de/fs/timeplan/net/TimePlanApiClient.kt`:
```kotlin
package de.fs.timeplan.net

import de.fs.timeplan.config.ServerConfig
import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.WorkersResponse
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class TimePlanApiClient(
    private val config: ServerConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TimePlanApi {

    private fun request(path: String): Request =
        Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

    private inline fun <reified T> execute(path: String): ApiResult<T> {
        return try {
            client.newCall(request(path)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ApiResult.Success(json.decodeFromString<T>(body))
                } else {
                    ApiResult.Error(response.code, body)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkFailure(e.message ?: "network error")
        }
    }

    override fun getStatus(): ApiResult<StatusResponse> = execute("/api/v1/status")

    override fun getWorkers(): ApiResult<WorkersResponse> = execute("/api/v1/workers")

    override fun getWeek(weekId: String): ApiResult<WeekBundle> = execute("/api/v1/weeks/$weekId")
}
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (4 neue + 11 bestehende = 15 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/net android/app/src/test/java/de/fs/timeplan/net/TimePlanApiClientTest.kt
git commit -m "feat(android): REST-API-Client (OkHttp + kotlinx.serialization) mit MockWebServer-Tests"
```

---

### Task 6: Wochenraster-Aufbau + RecyclerView-Adapter

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/grid/WeekRow.kt`, `android/app/src/main/java/de/fs/timeplan/grid/WeekGridBuilder.kt`, `android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt`
- Create: `android/app/src/main/res/layout/row_week_worker.xml`, `android/app/src/main/res/layout/row_week_separator.xml`
- Modify: `android/app/src/main/res/values/strings.xml` (String `azubi_separator` ergänzen)
- Test: `android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt`, `android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.model.{Worker,Entry,textOrNull}` (Task 2), `de.fs.timeplan.net.WeekId.makeCellId(weekId, workerId, dateIso: String)` (Task 3)
- Produces: `sealed class WeekRow { data class Monteur(workerId, displayName, cellTexts: List<String?>); data class Azubi(workerId, displayName, cellTexts: List<String?>); object Separator }`; `object WeekGridBuilder { fun build(weekId: String, dates: List<String>, workers: List<Worker>, entries: List<Entry>): List<WeekRow> }` (nur aktive Worker, Monteure zuerst nach Position sortiert, dann Separator, dann Azubis nach Position); `class WeekGridAdapter(rows: List<WeekRow> = emptyList()) : RecyclerView.Adapter<RecyclerView.ViewHolder>()` mit `fun submitRows(newRows: List<WeekRow>)`. Von Task 8 (WeekPresenter/WeekActivity) verwendet.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt`:
```kotlin
package de.fs.timeplan.grid

import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Worker
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class WeekGridBuilderTest {

    private val json = Json

    private fun textEntry(cellId: String, text: String) = Entry(
        id = "e-1", cell_id = cellId, type = "text", author_type = "web", author_id = "web-admin",
        content = json.parseToJsonElement("""{"text":"$text"}"""),
        conflict_of = null, created_at = "now", updated_at = "now", revision = 1
    )

    @Test
    fun `builds monteur rows, separator, and azubi rows with cell text placed correctly`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val azubi = Worker("w-2", "501", "Petersen", "azubi", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")
        val entries = listOf(textEntry("2026-W31_w-1_2026-07-30", "Baustelle A"))

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur, azubi), entries)

        assertEquals(3, rows.size)
        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals("144 Albrecht", monteurRow.displayName)
        assertEquals("Baustelle A", monteurRow.cellTexts[3])
        assertEquals(null, monteurRow.cellTexts[0])
        assertTrue(rows[1] is WeekRow.Separator)
        val azubiRow = rows[2] as WeekRow.Azubi
        assertEquals("501 Petersen", azubiRow.displayName)
    }

    @Test
    fun `ignores inactive workers`() {
        val inactiveMonteur = Worker("w-1", "144", "Albrecht", "monteur", 1, false, 1)
        val rows = WeekGridBuilder.build("2026-W31", List(7) { "2026-07-27" }, listOf(inactiveMonteur), emptyList())
        assertEquals(1, rows.size)
        assertTrue(rows[0] is WeekRow.Separator)
    }
}
```

`android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt`:
```kotlin
package de.fs.timeplan.grid

import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import de.fs.timeplan.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
class WeekGridAdapterTest {

    @Test
    fun `renders monteur rows, separator, and azubi rows`() {
        val rows = listOf(
            WeekRow.Monteur("w-1", "144 Albrecht", listOf("Baustelle A", null, null, null, null, null, null)),
            WeekRow.Separator,
            WeekRow.Azubi("w-2", "501 Petersen", listOf(null, "144 Albrecht", null, null, null, null, null))
        )
        val adapter = WeekGridAdapter(rows)
        assertEquals(3, adapter.itemCount)
        assertEquals(0, adapter.getItemViewType(0))
        assertEquals(1, adapter.getItemViewType(1))
        assertEquals(0, adapter.getItemViewType(2))

        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as WeekGridAdapter.WorkerRowViewHolder
        adapter.onBindViewHolder(holder, 0)
        val nameView = holder.itemView.findViewById<TextView>(R.id.rowWorkerName)
        assertEquals("144 Albrecht", nameView.text.toString())
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`WeekRow`/`WeekGridBuilder`/`WeekGridAdapter` existieren noch nicht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/grid/WeekRow.kt`:
```kotlin
package de.fs.timeplan.grid

sealed class WeekRow {
    data class Monteur(val workerId: String, val displayName: String, val cellTexts: List<String?>) : WeekRow()
    data class Azubi(val workerId: String, val displayName: String, val cellTexts: List<String?>) : WeekRow()
    object Separator : WeekRow()
}
```

`android/app/src/main/java/de/fs/timeplan/grid/WeekGridBuilder.kt`:
```kotlin
package de.fs.timeplan.grid

import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.textOrNull
import de.fs.timeplan.net.WeekId

object WeekGridBuilder {
    fun build(weekId: String, dates: List<String>, workers: List<Worker>, entries: List<Entry>): List<WeekRow> {
        val byCell: Map<String, List<Entry>> = entries.groupBy { it.cell_id }

        fun cellText(workerId: String, date: String): String? {
            val cellEntries = byCell[WeekId.makeCellId(weekId, workerId, date)].orEmpty()
            if (cellEntries.isEmpty()) return null
            return cellEntries.joinToString("\n") { entry ->
                if (entry.type == "text") entry.textOrNull().orEmpty() else "✎ Zeichnung"
            }
        }

        val active = workers.filter { it.active }
        val monteure = active.filter { it.isMonteur }.sortedBy { it.position }
        val azubis = active.filter { it.isAzubi }.sortedBy { it.position }

        val rows = mutableListOf<WeekRow>()
        for (w in monteure) {
            rows += WeekRow.Monteur(w.id, w.displayName, dates.map { cellText(w.id, it) })
        }
        rows += WeekRow.Separator
        for (w in azubis) {
            rows += WeekRow.Azubi(w.id, w.displayName, dates.map { cellText(w.id, it) })
        }
        return rows
    }
}
```

`android/app/src/main/res/layout/row_week_worker.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingTop="2dp"
    android:paddingBottom="2dp">

    <TextView
        android:id="@+id/rowWorkerName"
        android:layout_width="140dp"
        android:layout_height="wrap_content"
        android:paddingStart="8dp"
        android:paddingEnd="8dp"
        android:textStyle="bold" />

    <LinearLayout
        android:id="@+id/rowCellsContainer"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="horizontal" />

</LinearLayout>
```

`android/app/src/main/res/layout/row_week_separator.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/azubi_separator"
    android:textStyle="bold"
    android:background="#E5E7EB"
    android:padding="6dp" />
```

`android/app/src/main/res/values/strings.xml` ergänzen (neue Zeile innerhalb `<resources>`):
```xml
    <string name="azubi_separator">Mitarb. / Azubis</string>
```

`android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt`:
```kotlin
package de.fs.timeplan.grid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.fs.timeplan.R

private const val VIEW_TYPE_WORKER = 0
private const val VIEW_TYPE_SEPARATOR = 1

class WeekGridAdapter(private var rows: List<WeekRow> = emptyList()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun submitRows(newRows: List<WeekRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is WeekRow.Separator -> VIEW_TYPE_SEPARATOR
        else -> VIEW_TYPE_WORKER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SEPARATOR) {
            SeparatorViewHolder(inflater.inflate(R.layout.row_week_separator, parent, false))
        } else {
            WorkerRowViewHolder(inflater.inflate(R.layout.row_week_worker, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is WeekRow.Monteur -> (holder as WorkerRowViewHolder).bind(row.displayName, row.cellTexts, compact = false)
            is WeekRow.Azubi -> (holder as WorkerRowViewHolder).bind(row.displayName, row.cellTexts, compact = true)
            WeekRow.Separator -> Unit
        }
    }

    class SeparatorViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class WorkerRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameView: TextView = view.findViewById(R.id.rowWorkerName)
        private val cellsContainer: LinearLayout = view.findViewById(R.id.rowCellsContainer)

        fun bind(name: String, cellTexts: List<String?>, compact: Boolean) {
            nameView.text = name
            cellsContainer.removeAllViews()
            val context = cellsContainer.context
            for (text in cellTexts) {
                val cell = TextView(context).apply {
                    this.text = text.orEmpty()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = if (compact) 1 else 4
                    setPadding(8, 8, 8, 8)
                }
                cellsContainer.addView(cell)
            }
        }
    }
}
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (3 neue + 15 bestehende = 18 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/grid android/app/src/main/res/layout/row_week_worker.xml \
  android/app/src/main/res/layout/row_week_separator.xml android/app/src/main/res/values/strings.xml \
  android/app/src/test/java/de/fs/timeplan/grid
git commit -m "feat(android): Wochenraster-Aufbau (Monteur/Azubi-Block) und RecyclerView-Adapter"
```

---

### Task 7: Konfigurationsseite (SettingsActivity)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/settings/SettingsActivity.kt`
- Create: `android/app/src/main/res/layout/activity_settings.xml`
- Modify: `android/app/src/main/res/values/strings.xml` (neue Strings), `android/app/src/main/AndroidManifest.xml` (Activity registrieren)
- Test: `android/app/src/test/java/de/fs/timeplan/settings/SettingsActivityTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.config.{ConfigRepository,ServerConfig}` (Task 4)
- Produces: `class SettingsActivity : AppCompatActivity()` mit Eingabefeldern für Server-URL/Gerätekennung/Token, Speichern via `ConfigRepository`, `finish()` nach erfolgreichem Speichern. Von Task 8 (WeekActivity startet diese Activity, wenn keine Konfiguration vorliegt) verwendet.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/settings/SettingsActivityTest.kt`:
```kotlin
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
    fun `pre-fills fields from existing config`() {
        ConfigRepository(ApplicationProvider.getApplicationContext())
            .save(ServerConfig("http://x:8000", "tablet-02", "tok"))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val field = activity.findViewById<EditText>(R.id.fieldDeviceId)
        assertEquals("tablet-02", field.text.toString())
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`SettingsActivity` existiert noch nicht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/res/values/strings.xml` ergänzen (neue Zeilen innerhalb `<resources>`):
```xml
    <string name="hint_base_url">http://planer-server:8000</string>
    <string name="hint_device_id">Gerätekennung (z.B. tablet-01)</string>
    <string name="hint_token">Geräte-Token</string>
    <string name="save">Speichern</string>
    <string name="settings_incomplete">Bitte alle Felder ausfüllen</string>
```

`android/app/src/main/res/layout/activity_settings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <EditText
        android:id="@+id/fieldBaseUrl"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/hint_base_url"
        android:inputType="textUri" />

    <EditText
        android:id="@+id/fieldDeviceId"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/hint_device_id" />

    <EditText
        android:id="@+id/fieldToken"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/hint_token" />

    <Button
        android:id="@+id/buttonSave"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="end"
        android:text="@string/save" />

</LinearLayout>
```

`android/app/src/main/java/de/fs/timeplan/settings/SettingsActivity.kt`:
```kotlin
package de.fs.timeplan.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.fs.timeplan.R
import de.fs.timeplan.config.ConfigRepository
import de.fs.timeplan.config.ServerConfig

class SettingsActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var baseUrlField: EditText
    private lateinit var deviceIdField: EditText
    private lateinit var tokenField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configRepository = ConfigRepository(applicationContext)

        baseUrlField = findViewById(R.id.fieldBaseUrl)
        deviceIdField = findViewById(R.id.fieldDeviceId)
        tokenField = findViewById(R.id.fieldToken)

        configRepository.load()?.let { config ->
            baseUrlField.setText(config.baseUrl)
            deviceIdField.setText(config.deviceId)
            tokenField.setText(config.token)
        }

        findViewById<Button>(R.id.buttonSave).setOnClickListener { onSave() }
    }

    private fun onSave() {
        val baseUrl = baseUrlField.text.toString().trim()
        val deviceId = deviceIdField.text.toString().trim()
        val token = tokenField.text.toString().trim()

        if (baseUrl.isEmpty() || deviceId.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, R.string.settings_incomplete, Toast.LENGTH_SHORT).show()
            return
        }

        configRepository.save(ServerConfig(baseUrl, deviceId, token))
        finish()
    }
}
```

`android/app/src/main/AndroidManifest.xml`: innerhalb `<application>`, nach der bestehenden `WeekActivity`-Deklaration, ergänzen:
```xml
        <activity
            android:name=".settings.SettingsActivity"
            android:exported="false" />
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (2 neue + 18 bestehende = 20 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/settings android/app/src/main/res/layout/activity_settings.xml \
  android/app/src/main/res/values/strings.xml android/app/src/main/AndroidManifest.xml \
  android/app/src/test/java/de/fs/timeplan/settings
git commit -m "feat(android): Konfigurationsseite (SettingsActivity) für Server-URL/Gerät/Token"
```

---

### Task 8: WeekPresenter + WeekActivity (Verdrahtung, Navigation, Fehlerzustände)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/week/WeekPresenter.kt`
- Modify: `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`, `android/app/src/main/res/layout/activity_week.xml`, `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt`, `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.net.{TimePlanApi,ApiResult,WeekId}` (Task 3, 5), `de.fs.timeplan.grid.{WeekGridBuilder,WeekRow,WeekGridAdapter}` (Task 6), `de.fs.timeplan.config.ConfigRepository` (Task 4), `de.fs.timeplan.settings.SettingsActivity` (Task 7), `de.fs.timeplan.model.{Week,WeekBundle,WorkersResponse}` (Task 2)
- Produces: `sealed class WeekLoadResult { data class Success(week: Week, dates: List<String>, rows: List<WeekRow>); data class Failure(message: String) }`; `class WeekPresenter(api: TimePlanApi) { fun loadWeek(weekId: String): WeekLoadResult }` (synchron, blockierend — von der Activity über `Dispatchers.IO` aufgerufen); `WeekActivity` zeigt Wochenkopf, Navigation (vor/zurück/heute), leitet bei fehlender Konfiguration zu `SettingsActivity` weiter, zeigt Fehlermeldungen bei Netzwerk-/Serverfehlern statt abzustürzen.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt`:
```kotlin
package de.fs.timeplan.week

import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.Week
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.WorkersResponse
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.TimePlanApi
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private class FakeApi(
    private val workersResult: ApiResult<WorkersResponse>,
    private val weekResult: ApiResult<WeekBundle>
) : TimePlanApi {
    override fun getStatus(): ApiResult<StatusResponse> = ApiResult.Success(StatusResponse("ok", 1))
    override fun getWorkers(): ApiResult<WorkersResponse> = workersResult
    override fun getWeek(weekId: String): ApiResult<WeekBundle> = weekResult
}

class WeekPresenterTest {

    private val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
    private val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")

    @Test
    fun `success combines workers and week into rows`() {
        val api = FakeApi(
            workersResult = ApiResult.Success(WorkersResponse(listOf(monteur))),
            weekResult = ApiResult.Success(WeekBundle(Week("2026-W31", "OPEN", 5), dates, emptyList()))
        )
        val result = WeekPresenter(api).loadWeek("2026-W31")
        require(result is WeekLoadResult.Success)
        assertEquals("2026-W31", result.week.id)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `workers error yields failure`() {
        val api = FakeApi(
            workersResult = ApiResult.Error(401, "unauthorized"),
            weekResult = ApiResult.Success(WeekBundle(Week("2026-W31", "OPEN", 5), dates, emptyList()))
        )
        val result = WeekPresenter(api).loadWeek("2026-W31")
        require(result is WeekLoadResult.Failure)
        assertTrue(result.message.contains("401"))
    }

    @Test
    fun `network failure on week yields failure`() {
        val api = FakeApi(
            workersResult = ApiResult.Success(WorkersResponse(listOf(monteur))),
            weekResult = ApiResult.NetworkFailure("timeout")
        )
        val result = WeekPresenter(api).loadWeek("2026-W31")
        require(result is WeekLoadResult.Failure)
        assertTrue(result.message.contains("nicht erreichbar"))
    }
}
```

`android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`:
```kotlin
package de.fs.timeplan.week

import de.fs.timeplan.WeekActivity
import de.fs.timeplan.settings.SettingsActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
class WeekActivityTest {

    @Test
    fun `redirects to settings when unconfigured`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val nextIntent = shadowOf(activity).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, nextIntent?.component?.className)
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`WeekPresenter`/`WeekLoadResult` existieren noch nicht, `WeekActivityTest` schlägt fehl da `WeekActivity` noch keinen Redirect macht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/week/WeekPresenter.kt`:
```kotlin
package de.fs.timeplan.week

import de.fs.timeplan.grid.WeekGridBuilder
import de.fs.timeplan.grid.WeekRow
import de.fs.timeplan.model.Week
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.TimePlanApi

sealed class WeekLoadResult {
    data class Success(val week: Week, val dates: List<String>, val rows: List<WeekRow>) : WeekLoadResult()
    data class Failure(val message: String) : WeekLoadResult()
}

class WeekPresenter(private val api: TimePlanApi) {

    fun loadWeek(weekId: String): WeekLoadResult {
        val workersResult = api.getWorkers()
        val workers = when (workersResult) {
            is ApiResult.Success -> workersResult.data.workers
            is ApiResult.Error -> return WeekLoadResult.Failure(
                "Monteure konnten nicht geladen werden (${workersResult.code})")
            is ApiResult.NetworkFailure -> return WeekLoadResult.Failure(
                "Server nicht erreichbar: ${workersResult.message}")
        }

        val weekResult = api.getWeek(weekId)
        return when (weekResult) {
            is ApiResult.Success -> {
                val bundle = weekResult.data
                WeekLoadResult.Success(
                    week = bundle.week,
                    dates = bundle.dates,
                    rows = WeekGridBuilder.build(weekId, bundle.dates, workers, bundle.entries)
                )
            }
            is ApiResult.Error -> WeekLoadResult.Failure(
                "Woche konnte nicht geladen werden (${weekResult.code})")
            is ApiResult.NetworkFailure -> WeekLoadResult.Failure(
                "Server nicht erreichbar: ${weekResult.message}")
        }
    }
}
```

`android/app/src/main/res/values/strings.xml` ergänzen (neue Zeilen innerhalb `<resources>`):
```xml
    <string name="prev_week">◀</string>
    <string name="next_week">▶</string>
    <string name="today">Heute</string>
    <string name="settings">Einstellungen</string>
```

`android/app/src/main/res/layout/activity_week.xml` komplett ersetzen durch:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="8dp"
        android:gravity="center_vertical">

        <Button
            android:id="@+id/buttonPrevWeek"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/prev_week" />

        <TextView
            android:id="@+id/weekLabel"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center"
            android:textStyle="bold" />

        <Button
            android:id="@+id/buttonNextWeek"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/next_week" />

        <Button
            android:id="@+id/buttonToday"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/today" />

        <Button
            android:id="@+id/buttonSettings"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/settings" />

    </LinearLayout>

    <TextView
        android:id="@+id/errorLabel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp"
        android:gravity="center"
        android:visibility="gone" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/weekRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>
```

`android/app/src/main/java/de/fs/timeplan/WeekActivity.kt` komplett ersetzen durch:
```kotlin
package de.fs.timeplan

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.fs.timeplan.config.ConfigRepository
import de.fs.timeplan.grid.WeekGridAdapter
import de.fs.timeplan.net.TimePlanApiClient
import de.fs.timeplan.net.WeekId
import de.fs.timeplan.settings.SettingsActivity
import de.fs.timeplan.week.WeekLoadResult
import de.fs.timeplan.week.WeekPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WeekActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var weekLabel: TextView
    private lateinit var errorLabel: TextView
    private lateinit var recyclerView: RecyclerView
    private val adapter = WeekGridAdapter()
    private var currentWeekId: String = WeekId.currentWeekId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_week)
        configRepository = ConfigRepository(applicationContext)

        weekLabel = findViewById(R.id.weekLabel)
        errorLabel = findViewById(R.id.errorLabel)
        recyclerView = findViewById(R.id.weekRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.buttonPrevWeek).setOnClickListener {
            currentWeekId = WeekId.adjacentWeekId(currentWeekId, -1)
            loadCurrentWeek()
        }
        findViewById<View>(R.id.buttonNextWeek).setOnClickListener {
            currentWeekId = WeekId.adjacentWeekId(currentWeekId, 1)
            loadCurrentWeek()
        }
        findViewById<View>(R.id.buttonToday).setOnClickListener {
            currentWeekId = WeekId.currentWeekId()
            loadCurrentWeek()
        }
        findViewById<View>(R.id.buttonSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        weekLabel.text = currentWeekId
        if (configRepository.load() == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        loadCurrentWeek()
    }

    private fun loadCurrentWeek() {
        val config = configRepository.load() ?: return
        weekLabel.text = currentWeekId
        errorLabel.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WeekPresenter(TimePlanApiClient(config)).loadWeek(currentWeekId)
            }
            render(result)
        }
    }

    private fun render(result: WeekLoadResult) {
        when (result) {
            is WeekLoadResult.Success -> {
                errorLabel.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.submitRows(result.rows)
            }
            is WeekLoadResult.Failure -> {
                recyclerView.visibility = View.GONE
                errorLabel.visibility = View.VISIBLE
                errorLabel.text = result.message
            }
        }
    }
}
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (4 neue + 20 bestehende = 24 Tests).

- [ ] **Step 5: Build erneut verifizieren**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:assembleDebug --console=plain
```
Erwartet: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/week android/app/src/main/java/de/fs/timeplan/WeekActivity.kt \
  android/app/src/main/res/layout/activity_week.xml android/app/src/main/res/values/strings.xml \
  android/app/src/test/java/de/fs/timeplan/week
git commit -m "feat(android): WeekPresenter + WeekActivity mit Navigation und Fehlerzuständen"
```

---

## Nach diesem Plan

Meilenstein 3 (App-Basis) ist damit vollständig: Projekt-Setup, Konfigseite, Wochenraster mit echten Serverdaten, Wochennavigation. **Nicht Teil dieses Plans** (folgen als eigene Pläne, sobald ein Server/Gerät zum Testen bereitsteht):

1. **Live-Sync** (M4): WebSocket-Client, `cell.updated`-Events lösen selektives Neuladen aus
2. **Stifteingabe** (M5): eigene `DrawingCanvasView`, Vektordatenmodell, lokale Speicherung, Zellvorschau
3. **Offline & Upload** (M6): Room-Datenbank, Sync-Queue, WorkManager-Retries, Konfliktbehandlung im UI
4. **PDF & Archiv** (M7), **Betrieb/Kiosk** (M8)

**Noch offen aus diesem Plan:**
- Echte Geräte-/Emulator-Verifikation (kein AVD in dieser Umgebung vorhanden) — vor Erstinbetriebnahme auf dem Galaxy Tab Active3 oder einem Emulator nachholen
- Manueller Test gegen einen laufenden TimePlan-Server (dieser Plan deckt die Netzwerkschicht ausschließlich über `MockWebServer` ab)
