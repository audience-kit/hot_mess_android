package social.hotmess.android.support

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import social.hotmess.android.AppConfiguration
import social.hotmess.core.ApiError
import social.hotmess.core.ClientErrorReport
import social.hotmess.core.DeviceFacts
import social.hotmess.core.HotMessApi
import social.hotmess.core.RecentError
import social.hotmess.core.RecentErrors
import social.hotmess.core.isReportable
import social.hotmess.core.reportDescription
import java.io.File
import java.time.Instant

/**
 * Sends crashes and errors to the AudienceKit API (`POST /v1/client_errors`), which logs each one as a
 * `[client_error]` line in its Heroku log, and keeps the last few so "Report a problem" can attach them.
 *
 * A crash is saved to a file as the app dies and sent on the next launch. Errors are API failures that point at a
 * bug rather than at the network: a response the app couldn't decode, or a server error.
 */
class ErrorReporter(
    context: Context,
    private val api: HotMessApi,
    private val configuration: AppConfiguration,
    private val scope: CoroutineScope,
) {
    private val pendingCrash = File(context.filesDir, PENDING_CRASH)
    private val recent = RecentErrors()

    val device = DeviceFacts(
        version = configuration.versionName,
        build = configuration.versionCode.toString(),
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    /** Catches crashes from here on, and sends the one the last run saved. */
    fun start() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = ClientErrorReport.crash(error, thread.name, device)
                pendingCrash.writeText(HotMessApi.json.encodeToString(ClientErrorReport.serializer(), report))
            }
            previous?.uncaughtException(thread, error)
        }

        api.onFailure = { error, operation -> record(error, operation) }

        val saved = runCatching { pendingCrash.readText() }.getOrNull() ?: return
        pendingCrash.delete()
        val report = runCatching { HotMessApi.json.decodeFromString(ClientErrorReport.serializer(), saved) }
            .getOrNull() ?: return
        remember(report.kind, report.message)
        send(report)
    }

    /** Every failed call is kept for "Report a problem"; only the ones that point at a bug are sent. */
    fun record(error: ApiError, operation: String) {
        val message = "$operation: ${error.reportDescription}"
        remember("api", message)
        if (error.isReportable) send(ClientErrorReport(kind = "error", message = message))
    }

    /** Notes something that went wrong outside the API, e.g. a failed sign-in. */
    fun record(kind: String, message: String) = remember(kind, message)

    fun recentErrors(): List<RecentError> = recent.snapshot()

    private fun remember(kind: String, message: String) = recent.remember(kind, message, Instant.now().toString())

    private fun send(report: ClientErrorReport) {
        if (!recent.shouldSend(report)) return
        val completed = report.copy(
            version = report.version ?: device.version,
            build = report.build ?: device.build,
            osVersion = report.osVersion ?: device.osVersion,
            device = report.device ?: device.model,
        )
        Log.w("HotMess", "Reporting ${completed.kind}: ${completed.message}")
        scope.launch { api.reportError(completed, signedIn = api.client.isSignedIn) }
    }

    private companion object {
        const val PENDING_CRASH = "pending-crash.json"
    }
}
