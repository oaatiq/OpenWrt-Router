package io.wrtpilot.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Walks through the app against the mock router (tools/mock-router, reached
 * from the emulator at 10.0.2.2:8080) and saves a screenshot of each screen
 * to the app's external files dir (screenshots/). Run by
 * .github/workflows/screenshots.yml; not part of the regular test suite.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Test
    fun tour() {
        setLocale("en")
        launch()

        // onboarding
        waitFor(R.string.welcome_start)
        shot("01-welcome")
        click(R.string.welcome_start)
        waitFor(R.string.form_connect)
        type(R.string.form_name, "Home")
        type(R.string.form_address, "10.0.2.2")
        type(R.string.form_port, "8080")
        type(R.string.form_password, "wrtpilot")
        shot("02-add-router")
        click(R.string.form_connect)
        waitFor(R.string.check_connected, 30_000)
        shot("03-router-check")
        click(R.string.check_finish)

        // main screens
        waitFor(R.string.dash_top_devices, 30_000)
        shot("04-home", settle = 4_000)
        click(R.string.tab_devices)
        waitForText("Living room TV")
        shot("05-devices")
        clickText("Living room TV")
        waitFor(R.string.live_traffic)
        shot("06-device-detail", settle = 4_000)
        back()
        click(R.string.tab_family)
        waitForText("Kids")
        shot("07-family")
        clickText("Kids")
        waitFor(R.string.allowed_hours)
        shot("08-family-group")
        back()
        click(R.string.tab_settings)
        waitFor(R.string.language)
        shot("09-settings")
        click(R.string.qos_title)
        waitFor(R.string.qos_enable)
        shot("10-smart-queue")
        back()

        // dark theme
        click(R.string.theme)
        click(R.string.theme_dark)
        click(R.string.tab_home)
        waitFor(R.string.dash_top_devices)
        shot("11-home-dark", settle = 3_000)
        click(R.string.tab_devices)
        waitForText("Living room TV")
        clickText("Living room TV")
        waitFor(R.string.live_traffic)
        shot("12-device-detail-dark", settle = 4_000)
        back()
        click(R.string.tab_settings)
        click(R.string.theme)
        click(R.string.theme_light)
        scenario.close()

        // Arabic (right-to-left)
        setLocale("ar")
        launch()
        waitFor(R.string.dash_top_devices, 30_000)
        shot("13-home-arabic", settle = 4_000)
        click(R.string.tab_devices)
        waitForText("Living room TV")
        shot("14-devices-arabic")
        clickText("Living room TV")
        waitFor(R.string.live_traffic)
        shot("15-device-detail-arabic", settle = 4_000)
        back()
        click(R.string.tab_family)
        waitForText("Kids")
        clickText("Kids")
        waitFor(R.string.allowed_hours)
        shot("16-family-group-arabic")
        scenario.close()

        // French
        setLocale("fr")
        launch()
        waitFor(R.string.dash_top_devices, 30_000)
        shot("17-home-french", settle = 4_000)
        scenario.close()
        setLocale("")
    }

    // ------------------------------------------------------------------

    private fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun setLocale(tag: String) {
        instrumentation.runOnMainSync {
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
            )
        }
    }

    /** Text of a string resource in the activity's current language. */
    private fun str(@StringRes id: Int): String {
        var text = ""
        scenario.onActivity { text = it.getString(id) }
        return text
    }

    private fun waitFor(@StringRes id: Int, timeout: Long = 15_000) = waitForText(str(id), timeout)

    private fun waitForText(text: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(@StringRes id: Int) = clickNode(hasText(str(id)) and hasClickAction(), str(id))

    private fun clickText(text: String) = clickNode(hasText(text, substring = true) and hasClickAction(), text)

    private fun clickNode(matcher: SemanticsMatcher, what: String) {
        // "what" only makes a failing wait easier to read in the test report
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private fun type(@StringRes label: Int, value: String) {
        val node = compose.onNode(hasSetTextAction() and hasText(str(label)))
        runCatching { node.performScrollTo() }
        node.performTextReplacement(value)
    }

    private fun shot(name: String, settle: Long = 1_500) {
        compose.waitForIdle()
        SystemClock.sleep(settle)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
