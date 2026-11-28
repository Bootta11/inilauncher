package app.olauncher.extras.daynight

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs at the next sunrise or sunset (one-off, rescheduled by every [DayNight.sync]).
 * WorkManager keeps it across reboots. Returning home also syncs, in case it runs late.
 */
class DayNightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!DayNight.isEnabled(applicationContext)) return Result.success()
        DayNight.sync(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "inilauncher_daynight"

        /** [needsNetwork]: a retry for a wallpaper that has to be downloaded. */
        fun schedule(context: Context, atMs: Long, needsNetwork: Boolean = false) {
            if (app.olauncher.extras.showcase.Showcase.inProcess) return // the showcase never schedules work
            // A minute late, so the switch is clearly past when it runs
            val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0) + 60_000
            val request = OneTimeWorkRequestBuilder<DayNightWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .apply {
                    if (needsNetwork) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            if (app.olauncher.extras.showcase.Showcase.inProcess) return
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
