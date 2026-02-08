package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.modules.gateway.GatewayApi
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Date
import java.util.concurrent.TimeUnit

class InboxPushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val settings: GatewaySettings by inject()

    override suspend fun doWork(): Result {
        val token = settings.registrationInfo?.token ?: return Result.failure()
        val phoneNumber = inputData.getString(KEY_PHONE) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, 0L)
        val externalId = inputData.getString(KEY_EXTERNAL_ID) ?: return Result.failure()

        val api = GatewayApi(settings.serverUrl, settings.privateToken)

        return try {
            withContext(Dispatchers.IO) {
                api.pushInbox(
                    token,
                    listOf(
                        GatewayApi.InboxPushRequest(
                            phoneNumber = phoneNumber,
                            body = body,
                            receivedAt = Date(receivedAt),
                            externalId = externalId,
                        )
                    )
                )
            }
            Result.success()
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 409) {
                Result.success() // duplicate, that's fine
            } else if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (th: Throwable) {
            th.printStackTrace()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_PHONE = "phone"
        private const val KEY_BODY = "body"
        private const val KEY_RECEIVED_AT = "receivedAt"
        private const val KEY_EXTERNAL_ID = "externalId"

        fun start(context: Context, phoneNumber: String, body: String, receivedAt: Date, externalId: String) {
            val work = OneTimeWorkRequestBuilder<InboxPushWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PHONE to phoneNumber,
                        KEY_BODY to body,
                        KEY_RECEIVED_AT to receivedAt.time,
                        KEY_EXTERNAL_ID to externalId,
                    )
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(work)
        }
    }
}
