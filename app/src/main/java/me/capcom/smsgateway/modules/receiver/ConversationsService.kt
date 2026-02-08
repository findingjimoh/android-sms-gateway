package me.capcom.smsgateway.modules.receiver

import android.content.Context
import android.provider.Telephony
import java.util.Date

class ConversationsService {

    data class ConversationPreview(
        val address: String,
        val lastMessage: String,
        val date: Long,
        val messageCount: Int,
    )

    data class ThreadMessage(
        val body: String,
        val date: Long,
        val type: String,
        val address: String,
    )

    fun getConversations(context: Context, limit: Int = 20, offset: Int = 0): List<ConversationPreview> {
        val conversations = mutableMapOf<String, ConversationPreview>()
        val messageCounts = mutableMapOf<String, Int>()

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(0) ?: continue
                val body = it.getString(1) ?: ""
                val date = it.getLong(2)

                val normalizedAddress = address.replace("[^+\\d]".toRegex(), "")

                messageCounts[normalizedAddress] = (messageCounts[normalizedAddress] ?: 0) + 1

                if (!conversations.containsKey(normalizedAddress)) {
                    conversations[normalizedAddress] = ConversationPreview(
                        address = address,
                        lastMessage = body,
                        date = date,
                        messageCount = 0,
                    )
                }
            }
        }

        return conversations.map { (key, preview) ->
            preview.copy(messageCount = messageCounts[key] ?: 0)
        }
            .sortedByDescending { it.date }
            .drop(offset)
            .take(limit)
    }

    fun getThread(context: Context, phone: String, limit: Int = 50, offset: Int = 0): List<ThreadMessage> {
        val messages = mutableListOf<ThreadMessage>()

        val projection = arrayOf(
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.ADDRESS,
        )

        val selection = "${Telephony.Sms.ADDRESS} LIKE ?"
        val selectionArgs = arrayOf("%${phone.replace("[^+\\d]".toRegex(), "")}%")

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                messages.add(
                    ThreadMessage(
                        body = it.getString(0) ?: "",
                        date = it.getLong(1),
                        type = when (it.getInt(2)) {
                            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
                            else -> "unknown"
                        },
                        address = it.getString(3) ?: "",
                    )
                )
            }
        }

        return messages.drop(offset).take(limit)
    }

    fun getRecent(context: Context, since: Long, limit: Int = 50): List<ThreadMessage> {
        val messages = mutableListOf<ThreadMessage>()

        val projection = arrayOf(
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.ADDRESS,
        )

        val selection = "${Telephony.Sms.DATE} >= ?"
        val selectionArgs = arrayOf(since.toString())

        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                messages.add(
                    ThreadMessage(
                        body = it.getString(0) ?: "",
                        date = it.getLong(1),
                        type = "received",
                        address = it.getString(3) ?: "",
                    )
                )
            }
        }

        return messages.take(limit)
    }
}
