package app.olauncher.extras.weather

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Refreshes the weather about once an hour while the feature is on and there's a network. */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = WeatherRepository(applicationContext)
        if (!repo.isEnabled()) return Result.success()
        repo.refresh(force = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "inilauncher_weather"

        fun schedule(context: Context) {
            if (app.olauncher.extras.showcase.Showcase.inProcess) return // the showcase never schedules work
            val request = PeriodicWorkRequestBuilder<WeatherWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            if (app.olauncher.extras.showcase.Showcase.inProcess) return
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
