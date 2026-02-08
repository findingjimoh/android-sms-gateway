package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.modules.gateway.GatewayService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class InboxSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val gatewayService: GatewayService by inject()

    override suspend fun doWork(): Result {
        return try {
            withContext(Dispatchers.IO) {
                gatewayService.syncInbox(applicationContext)
            }
            Result.success()
        } catch (th: Throwable) {
            th.printStackTrace()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val NAME = "InboxSyncWorker"

        fun start(context: Context) {
            val work = OneTimeWorkRequestBuilder<InboxSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
