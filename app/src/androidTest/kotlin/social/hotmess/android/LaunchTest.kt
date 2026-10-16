package social.hotmess.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the app lifecycle without Facebook credentials or a live API session. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @Test fun launchAndRecreate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertNotNull(activity.window.decorView)
            assertFalse(activity.isFinishing)
            activity.recreate()
        }
        instrumentation.waitForIdleSync()
    }
}
