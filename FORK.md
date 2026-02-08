# Fork Changes

Fork of [capcom6/android-sms-gateway](https://github.com/capcom6/android-sms-gateway) with inbox sync support.

## What's Added

### Inbox Sync (`InboxSyncWorker`)
One-shot `CoroutineWorker` that syncs the full SMS/MMS inbox to the server on app startup. Reads from Android content providers (`Telephony.Sms.Inbox`, `content://mms/`) in batches of 100 and pushes via `POST /mobile/v1/inbox`. Server deduplicates on `(externalId, deviceId)`.

### Real-time Push (`InboxPushWorker`)
Per-message worker that pushes newly received SMS/MMS to the server immediately after webhook emission. Handles 409 (duplicate) as success. Retries up to 3 times with exponential backoff.

### Group MMS
`getMmsAddress()` returns all participants' phone numbers joined by `;` instead of only the FROM address.

### API Extension (`GatewayApi.pushInbox`)
New endpoint method and `InboxPushRequest` DTO for batch inbox push.

## Files Modified

| File | Change |
|------|--------|
| `modules/gateway/GatewayApi.kt` | Added `pushInbox()` + `InboxPushRequest` |
| `modules/gateway/GatewayService.kt` | Added `syncInbox()`, content provider readers, group MMS |
| `modules/receiver/ReceiverService.kt` | Added real-time push via `InboxPushWorker` |

## Files Added

| File | Purpose |
|------|---------|
| `modules/gateway/workers/InboxSyncWorker.kt` | Startup bulk sync worker |
| `modules/gateway/workers/InboxPushWorker.kt` | Real-time per-message push worker |

## Build

```bash
./gradlew assembleDebugInsecure
# APK: app/build/outputs/apk/debugInsecure/app-debugInsecure.apk
adb install -r app/build/outputs/apk/debugInsecure/app-debugInsecure.apk
```
