package me.capcom.smsgateway.modules.gateway

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpStatusCode
import me.capcom.smsgateway.data.entities.MessageWithRecipients
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.MessageContent
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.gateway.events.DeviceRegisteredEvent
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.gateway.workers.InboxSyncWorker
import me.capcom.smsgateway.modules.gateway.workers.PullMessagesWorker
import me.capcom.smsgateway.modules.gateway.workers.SendStateWorker
import me.capcom.smsgateway.modules.gateway.workers.SettingsUpdateWorker
import me.capcom.smsgateway.modules.gateway.workers.WebhooksUpdateWorker
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.MessagesSettings
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.services.PushService
import java.util.Date

class GatewayService(
    private val messagesService: MessagesService,
    private val settings: GatewaySettings,
    private val events: EventBus,
    private val logsService: LogsService,
) {
    private val eventsReceiver by lazy { EventsReceiver() }

    private var _api: GatewayApi? = null

    private val api
        get() = _api ?: GatewayApi(
            settings.serverUrl,
            settings.privateToken
        ).also { _api = it }

    //region Start, stop, etc...
    fun start(context: Context) {
        if (!settings.enabled) return

        PushService.register(context)
        PullMessagesWorker.start(context)
        WebhooksUpdateWorker.start(context)
        SettingsUpdateWorker.start(context)
        InboxSyncWorker.start(context)

        eventsReceiver.start()
    }

    fun stop(context: Context) {
        eventsReceiver.stop()

        SSEForegroundService.stop(context)
        SettingsUpdateWorker.stop(context)
        WebhooksUpdateWorker.stop(context)
        PullMessagesWorker.stop(context)

        this._api = null
    }

    fun isActiveLiveData(context: Context) = PullMessagesWorker.getStateLiveData(context)
    //endregion

    //region Account
    suspend fun getLoginCode(): GatewayApi.GetUserCodeResponse {
        val username = settings.username
            ?: throw IllegalStateException("Username is not set")
        val password = settings.password
            ?: throw IllegalStateException("Password is not set")

        return api.getUserCode(username to password)
    }

    suspend fun changePassword(current: String, new: String) {
        val info = settings.registrationInfo
            ?: throw IllegalStateException("The device is not registered on the server")

        this.api.changeUserPassword(
            info.token,
            GatewayApi.PasswordChangeRequest(current, new)
        )

        settings.registrationInfo = info.copy(password = new)

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                info.login,
                new,
            )
        )
    }
    //endregion

    //region Device
    internal suspend fun registerDevice(
        pushToken: String?,
        registerMode: RegistrationMode
    ) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo
        val accessToken = settings?.token

        if (accessToken != null) {
            // if there's an access token, try to update push token
            try {
                updateDevice(pushToken)
                return
            } catch (e: ClientRequestException) {
                // if token is invalid, try to register new one
                if (e.response.status != HttpStatusCode.Unauthorized) {
                    throw e
                }
            }
        }

        try {
            val deviceName = "${Build.MANUFACTURER}/${Build.PRODUCT}"
            val request = GatewayApi.DeviceRegisterRequest(
                deviceName,
                pushToken
            )
            val response = when (registerMode) {
                RegistrationMode.Anonymous -> api.deviceRegister(request, null)
                is RegistrationMode.WithCode -> api.deviceRegister(request, registerMode.code)
                is RegistrationMode.WithCredentials -> api.deviceRegister(
                    request,
                    registerMode.login to registerMode.password
                )
            }

            this.settings.fcmToken = pushToken
            this.settings.registrationInfo = response

            events.emit(
                DeviceRegisteredEvent.Success(
                    api.hostname,
                    response.login,
                    response.password,
                )
            )
        } catch (th: Throwable) {
            events.emit(
                DeviceRegisteredEvent.Failure(
                    api.hostname,
                    th.localizedMessage ?: th.message ?: th.toString()
                )
            )

            throw th
        }
    }

    internal suspend fun updateDevice(pushToken: String?) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo ?: return
        val accessToken = settings.token

        pushToken?.let {
            api.devicePatch(
                accessToken,
                GatewayApi.DevicePatchRequest(
                    settings.id,
                    it
                )
            )
        }

        this.settings.fcmToken = pushToken

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                settings.login,
                settings.password,
            )
        )
    }

    sealed class RegistrationMode {
        object Anonymous : RegistrationMode()
        class WithCredentials(val login: String, val password: String) : RegistrationMode()
        class WithCode(val code: String) : RegistrationMode()
    }
    //endregion

    //region Messages
    internal suspend fun getNewMessages(context: Context) {
        if (!settings.enabled) return
        val settings = settings.registrationInfo ?: return
        val processingOrder = when (messagesService.processingOrder) {
            MessagesSettings.ProcessingOrder.LIFO -> GatewayApi.ProcessingOrder.LIFO
            MessagesSettings.ProcessingOrder.FIFO -> GatewayApi.ProcessingOrder.FIFO
        }
        val messages = api.getMessages(settings.token, processingOrder)
        for (message in messages) {
            try {
                processMessage(context, message)
            } catch (th: Throwable) {
                logsService.insert(
                    LogEntry.Priority.ERROR,
                    MODULE_NAME,
                    "Failed to process message",
                    mapOf(
                        "message" to message,
                        "exception" to th.stackTraceToString(),
                    )
                )
                th.printStackTrace()
            }
        }
    }

    private fun processMessage(context: Context, message: GatewayApi.Message) {
        val messageState = messagesService.getMessage(message.id)
        if (messageState != null) {
            SendStateWorker.start(context, message.id)
            return
        }

        val request = SendRequest(
            EntitySource.Cloud,
            me.capcom.smsgateway.modules.messages.data.Message(
                message.id,
                when (val content = message.content) {
                    is GatewayApi.MessageContent.Text -> MessageContent.Text(content.text)
                    is GatewayApi.MessageContent.Data -> MessageContent.Data(
                        content.data,
                        content.port
                    )
                },
                message.phoneNumbers,
                message.isEncrypted ?: false,
                message.createdAt ?: Date(),
            ),
            SendParams(
                message.withDeliveryReport ?: true,
                skipPhoneValidation = true,
                simNumber = message.simNumber,
                validUntil = message.validUntil,
                priority = message.priority,
            )
        )
        messagesService.enqueueMessage(request)
    }

    internal suspend fun sendState(
        message: MessageWithRecipients
    ) {
        val settings = settings.registrationInfo ?: return

        api.patchMessages(
            settings.token,
            listOf(
                GatewayApi.MessagePatchRequest(
                    message.message.id,
                    message.message.state,
                    message.recipients.map {
                        GatewayApi.RecipientState(
                            it.phoneNumber,
                            it.state,
                            it.error
                        )
                    },
                    message.states.associate { it.state to Date(it.updatedAt) }
                )
            )
        )
    }
    //endregion

    //region Webhooks
    internal suspend fun getWebHooks(): List<GatewayApi.WebHook> {
        val settings = settings.registrationInfo
        return if (settings != null) {
            api.getWebHooks(settings.token)
        } else {
            emptyList()
        }
    }
    //endregion

    //region Settings
    internal suspend fun getSettings(): Map<String, *>? {
        val settings = settings.registrationInfo ?: return null

        return api.getSettings(settings.token)
    }
    //endregion

    //region Utility
    suspend fun getPublicIP(): String {
        return GatewayApi(
            settings.serverUrl,
            settings.privateToken
        )
            .getDevice(settings.registrationInfo?.token)
            .externalIp
    }
    //endregion

    //region Inbox Sync
    internal suspend fun syncInbox(context: Context) {
        val reg = settings.registrationInfo
        android.util.Log.i("InboxSync", "syncInbox: registrationInfo=${reg != null}")
        if (reg == null) return

        syncSmsPages(context, reg.token)
        syncMmsPages(context, reg.token)
        android.util.Log.i("InboxSync", "syncInbox: done")
    }

    private suspend fun syncSmsPages(context: Context, token: String) {
        val batchSize = 100
        var offset = 0
        while (true) {
            val batch = readSmsPage(context, batchSize, offset)
            android.util.Log.i("InboxSync", "SMS batch offset=$offset size=${batch.size}")
            if (batch.isEmpty()) break
            pushBatch(token, batch)
            offset += batchSize
        }
    }

    private suspend fun syncMmsPages(context: Context, token: String) {
        val batchSize = 100
        var offset = 0
        while (true) {
            val batch = readMmsPage(context, batchSize, offset)
            if (batch.isEmpty()) break
            pushBatch(token, batch)
            offset += batchSize
        }
    }

    private suspend fun pushBatch(token: String, payload: List<GatewayApi.InboxPushRequest>) {
        try {
            api.pushInbox(token, payload)
            android.util.Log.i("InboxSync", "Pushed ${payload.size} messages")
        } catch (e: ClientRequestException) {
            if (e.response.status.value != 409) throw e
        } catch (e: ServerResponseException) {
            // Server returns 500 for duplicates in some cases, safe to ignore
            android.util.Log.w("InboxSync", "Server error (ignored): ${e.message}")
        }
    }

    private fun readSmsPage(context: Context, limit: Int, offset: Int): List<GatewayApi.InboxPushRequest> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} ASC LIMIT $limit OFFSET $offset"
        ) ?: return emptyList()

        val results = mutableListOf<GatewayApi.InboxPushRequest>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val address = it.getString(1) ?: continue
                val body = it.getString(2) ?: ""
                val date = it.getLong(3)
                results.add(
                    GatewayApi.InboxPushRequest(
                        phoneNumber = address,
                        body = body,
                        receivedAt = Date(date),
                        externalId = "sms_$id"
                    )
                )
            }
        }
        return results
    }

    private fun readMmsPage(context: Context, limit: Int, offset: Int): List<GatewayApi.InboxPushRequest> {
        val mmsUri = Uri.parse("content://mms/")
        val cursor = context.contentResolver.query(
            mmsUri,
            arrayOf("_id", "date", "sub"),
            "msg_box = 1", // inbox only
            null,
            "date ASC LIMIT $limit OFFSET $offset"
        ) ?: return emptyList()

        val results = mutableListOf<GatewayApi.InboxPushRequest>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val dateSec = it.getLong(1)
                val subject = it.getString(2)

                val address = getMmsAddress(context, id) ?: continue
                val body = getMmsTextBody(context, id)
                    ?: subject?.let { s -> "[MMS: $s]" }
                    ?: "[MMS: media message]"

                results.add(
                    GatewayApi.InboxPushRequest(
                        phoneNumber = address,
                        body = body,
                        receivedAt = Date(dateSec * 1000), // MMS date is in seconds
                        externalId = "mms_$id"
                    )
                )
            }
        }
        return results
    }

    private fun getMmsAddress(context: Context, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("address", "type"),
            null, null, null
        ) ?: return null

        val addresses = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val addr = it.getString(0)
                if (!addr.isNullOrBlank() && addr != "insert-address-token") {
                    addresses.add(addr)
                }
            }
        }
        return if (addresses.isEmpty()) null else addresses.distinct().joinToString(";")
    }

    private fun getMmsTextBody(context: Context, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/part")
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("_id", "ct", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val contentType = it.getString(1)
                if (contentType == "text/plain") {
                    val text = it.getString(2)
                    if (!text.isNullOrBlank()) return text
                }
            }
        }
        return null
    }
    //endregion
}