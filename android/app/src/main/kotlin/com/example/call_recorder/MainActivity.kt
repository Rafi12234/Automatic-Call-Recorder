package com.example.call_recorder

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.CallLog
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs


// ================================================================
// MAIN ACTIVITY
// ================================================================

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "call_recorder/native"
        private const val PERMISSION_REQUEST_CODE = 9001
    }

    private var pendingPermissionResult: MethodChannel.Result? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var methodChannel: MethodChannel
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: Any? = null
    private var playbackPaused = false

    private val audioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (
                change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                try {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        playbackPaused = true
                    }
                } catch (_: Exception) {
                }
            }
        }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        )

        methodChannel.setMethodCallHandler { call, result ->

            when (call.method) {

                "permissionsGranted" -> {
                    result.success(allPermissionsGranted())
                }

                "requestPermissions" -> {
                    requestAllPermissions(result)
                }

                "armRecorder" -> {

                    if (!allPermissionsGranted()) {
                        result.success(false)
                        return@setMethodCallHandler
                    }

                    try {
                        val intent = Intent(
                            this,
                            CallRecorderService::class.java
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }

                        result.success(true)

                    } catch (e: Exception) {
                        result.error(
                            "SERVICE_ERROR",
                            e.message,
                            null
                        )
                    }
                }

                "getHistory" -> {
                    result.success(
                        HistoryStore.getHistory(this)
                    )
                }

                "playRecording" -> {
                    val path = call.argument<String>("path")

                    if (path == null) {
                        result.error(
                            "PATH_ERROR",
                            "No recording path",
                            null
                        )

                        return@setMethodCallHandler
                    }

                    playRecording(path, result)
                }

                "pausePlayback" -> {
                    result.success(pausePlayback())
                }

                "resumePlayback" -> {
                    result.success(resumePlayback())
                }

                "stopPlayback" -> {
                    stopPlayback()
                    result.success(true)
                }

                "openSettings" -> {

                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    ).apply {
                        data = Uri.parse("package:$packageName")
                    }

                    startActivity(intent)
                    result.success(true)
                }

                else -> {
                    result.notImplemented()
                }
            }
        }
    }


    // ============================================================
    // PERMISSIONS
    // ============================================================

    private fun requiredPermissions(): Array<String> {

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        return permissions.toTypedArray()
    }


    private fun allPermissionsGranted(): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        return requiredPermissions().all {
            checkSelfPermission(it) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }


    private fun requestAllPermissions(
        result: MethodChannel.Result
    ) {

        if (allPermissionsGranted()) {
            result.success(true)
            return
        }

        pendingPermissionResult = result

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                requiredPermissions(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            result.success(true)
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == PERMISSION_REQUEST_CODE) {

            pendingPermissionResult?.success(
                allPermissionsGranted()
            )

            pendingPermissionResult = null
        }
    }


    // ============================================================
    // PLAY RECORDING
    // ============================================================

    private fun playRecording(
        path: String,
        result: MethodChannel.Result
    ) {

        try {

            val file = File(path)

            if (!file.exists() || file.length() == 0L) {
                result.error(
                    "NOT_FOUND",
                    "Recording file is missing or empty.",
                    null
                )
                return
            }

            stopPlayback()
            requestPlaybackAudioFocus()

            mediaPlayer = MediaPlayer().apply {

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                setDataSource(file.absolutePath)
                prepare()
                setVolume(1.0f, 1.0f)

                setOnCompletionListener {
                    try {
                        it.release()
                    } catch (_: Exception) {
                    }

                    mediaPlayer = null
                    playbackPaused = false
                    abandonPlaybackAudioFocus()

                    methodChannel.invokeMethod(
                        "playbackCompleted",
                        mapOf("path" to path)
                    )
                }

                setOnErrorListener { player, what, extra ->
                    try {
                        player.release()
                    } catch (_: Exception) {
                    }

                    mediaPlayer = null
                    playbackPaused = false
                    abandonPlaybackAudioFocus()

                    methodChannel.invokeMethod(
                        "playbackError",
                        mapOf(
                            "path" to path,
                            "what" to what,
                            "extra" to extra
                        )
                    )

                    true
                }

                start()
            }

            playbackPaused = false
            result.success(true)

        } catch (e: Exception) {

            stopPlayback()

            result.error(
                "PLAY_ERROR",
                e.message ?: "Unable to play this recording.",
                null
            )
        }
    }


    private fun pausePlayback(): Boolean {

        return try {

            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                playbackPaused = true
                true
            } else {
                false
            }

        } catch (_: Exception) {
            false
        }
    }


    private fun resumePlayback(): Boolean {

        return try {

            val player = mediaPlayer

            if (player != null && playbackPaused) {
                requestPlaybackAudioFocus()
                player.start()
                playbackPaused = false
                true
            } else {
                false
            }

        } catch (_: Exception) {
            false
        }
    }


    private fun requestPlaybackAudioFocus(): Boolean {

        val manager = audioManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val request =
                AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setOnAudioFocusChangeListener(
                        audioFocusChangeListener
                    )
                    .build()

            audioFocusRequest = request

            manager.requestAudioFocus(request) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        } else {

            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }


    private fun abandonPlaybackAudioFocus() {

        val manager = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val request =
                audioFocusRequest as? AudioFocusRequest

            if (request != null) {
                manager.abandonAudioFocusRequest(request)
            }

            audioFocusRequest = null

        } else {

            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(
                audioFocusChangeListener
            )
        }
    }


    private fun stopPlayback() {

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }

        mediaPlayer = null
        playbackPaused = false
        abandonPlaybackAudioFocus()
    }


    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}


// ================================================================
// BACKGROUND CALL RECORDER SERVICE
// ================================================================

class CallRecorderService : Service() {

    companion object {

        private const val NOTIFICATION_CHANNEL =
            "automatic_call_recorder"

        private const val NOTIFICATION_ID = 7701
    }


    private lateinit var telephonyManager: TelephonyManager

    private var sawRinging = false
    private var recording = false

    private var currentDirection = "Outgoing"

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    private var recordingStartedAt: Long = 0
    private var observedIncomingNumber: String? = null
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    private var maxObservedAmplitude = 0

    private val amplitudeHandler =
        Handler(Looper.getMainLooper())

    private val amplitudeSampler =
        object : Runnable {
            override fun run() {

                if (!recording) {
                    return
                }

                try {
                    val amplitude =
                        recorder?.maxAmplitude ?: 0

                    if (amplitude > maxObservedAmplitude) {
                        maxObservedAmplitude = amplitude
                    }
                } catch (_: Exception) {
                }

                amplitudeHandler.postDelayed(
                    this,
                    500
                )
            }
        }


    // ============================================================
    // PHONE STATE LISTENER
    // ============================================================

    @Suppress("DEPRECATION")
    private val phoneStateListener =
        object : PhoneStateListener() {

            override fun onCallStateChanged(
                state: Int,
                phoneNumber: String?
            ) {

                super.onCallStateChanged(
                    state,
                    phoneNumber
                )

                handleCallState(
                    state,
                    phoneNumber
                )
            }
        }


    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        startRecorderForegroundService()

        telephonyManager =
            getSystemService(
                Context.TELEPHONY_SERVICE
            ) as TelephonyManager

        registerPhoneListener()
    }


    // ============================================================
    // START FOREGROUND SERVICE
    // ============================================================

    private fun startRecorderForegroundService() {

        val notification =
            buildNotification(
                "Ready — waiting for calls"
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }


    // ============================================================
    // NOTIFICATION CHANNEL
    // ============================================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Automatic Call Recorder",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Automatic call recorder background service"

            manager.createNotificationChannel(channel)
        }
    }


    private fun buildNotification(
        text: String
    ): Notification {

        val intent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                Notification.Builder(
                    this,
                    NOTIFICATION_CHANNEL
                )

            } else {

                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle(
                "Call Recorder Active"
            )
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }


    private fun updateNotification(
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )
    }


    // ============================================================
    // REGISTER PHONE LISTENER
    // ============================================================

    @Suppress("DEPRECATION")
    private fun registerPhoneListener() {

        try {

            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    @Suppress("DEPRECATION")
    private fun unregisterPhoneListener() {

        try {

            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_NONE
            )

        } catch (_: Exception) {
        }
    }


    // ============================================================
    // CALL STATE
    // ============================================================

    private fun handleCallState(
        state: Int,
        phoneNumber: String?
    ) {

        if (state == lastCallState) {
            return
        }

        lastCallState = state

        when (state) {

            TelephonyManager.CALL_STATE_RINGING -> {

                sawRinging = true
                currentDirection = "Incoming"

                if (!phoneNumber.isNullOrBlank()) {
                    observedIncomingNumber =
                        phoneNumber
                }

                updateNotification(
                    "Incoming call detected"
                )
            }


            TelephonyManager.CALL_STATE_OFFHOOK -> {

                if (!recording) {

                    currentDirection =
                        if (sawRinging) {
                            "Incoming"
                        } else {
                            "Outgoing"
                        }

                    startCallRecording()
                }
            }


            TelephonyManager.CALL_STATE_IDLE -> {

                if (recording) {
                    stopCallRecording()
                }

                sawRinging = false
                observedIncomingNumber = null

                updateNotification(
                    "Ready — waiting for calls"
                )
            }
        }
    }


    // ============================================================
    // START RECORDING
    // ============================================================

    private fun startCallRecording() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {

            val directory =
                File(
                    getExternalFilesDir(null),
                    "CallRecordings"
                )

            if (!directory.exists()) {
                directory.mkdirs()
            }


            val formatter =
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.getDefault()
                )

            val fileName =
                "call_${formatter.format(Date())}.m4a"


            currentFile =
                File(
                    directory,
                    fileName
                )


            val mediaRecorder =
                createMediaRecorder()


            mediaRecorder.setAudioSource(
                MediaRecorder.AudioSource.MIC
            )


            mediaRecorder.setOutputFormat(
                MediaRecorder.OutputFormat.MPEG_4
            )


            mediaRecorder.setAudioEncoder(
                MediaRecorder.AudioEncoder.AAC
            )


            mediaRecorder.setAudioEncodingBitRate(
                128000
            )


            mediaRecorder.setAudioSamplingRate(
                44100
            )


            mediaRecorder.setOutputFile(
                currentFile!!.absolutePath
            )


            mediaRecorder.prepare()

            mediaRecorder.start()


            recorder = mediaRecorder

            recordingStartedAt =
                System.currentTimeMillis()

            recording = true
            maxObservedAmplitude = 0

            amplitudeHandler.removeCallbacks(
                amplitudeSampler
            )

            amplitudeHandler.post(
                amplitudeSampler
            )


            updateNotification(
                "Recording $currentDirection call"
            )


        } catch (e: Exception) {

            e.printStackTrace()

            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            recorder = null

            currentFile?.delete()

            currentFile = null

            recording = false

            updateNotification(
                "Recording unavailable"
            )
        }
    }


    @Suppress("DEPRECATION")
    private fun createMediaRecorder():
            MediaRecorder {

        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {

            MediaRecorder(this)

        } else {

            MediaRecorder()
        }
    }


    // ============================================================
    // STOP RECORDING
    // ============================================================

    private fun stopCallRecording() {

        val endedAt =
            System.currentTimeMillis()

        val startedAt =
            recordingStartedAt

        val file =
            currentFile

        val direction =
            currentDirection

        val incomingNumber =
            observedIncomingNumber

        amplitudeHandler.removeCallbacks(
            amplitudeSampler
        )

        val peakAmplitude =
            maxObservedAmplitude


        var validRecording = true


        try {
            recorder?.stop()
        } catch (e: Exception) {

            e.printStackTrace()

            validRecording = false
        }


        try {
            recorder?.reset()
        } catch (_: Exception) {
        }


        try {
            recorder?.release()
        } catch (_: Exception) {
        }


        recorder = null
        recording = false
        currentFile = null


        if (
            !validRecording ||
            file == null ||
            !file.exists()
        ) {

            file?.delete()

            return
        }


        Handler(
            Looper.getMainLooper()
        ).postDelayed({

            saveCompletedRecording(
                file,
                direction,
                startedAt,
                endedAt,
                incomingNumber,
                peakAmplitude
            )

        }, 1500)
    }


    // ============================================================
    // SAVE RECORDING INFORMATION
    // ============================================================

    private fun saveCompletedRecording(
        file: File,
        direction: String,
        startedAt: Long,
        endedAt: Long,
        incomingNumber: String?,
        peakAmplitude: Int
    ) {

        var number =
            incomingNumber
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown"


        if (number == "Unknown") {

            val call =
                findLatestMatchingCall(
                    direction,
                    startedAt
                )

            if (call != null) {

                number =
                    call.number.ifBlank {
                        "Unknown"
                    }
            }
        }


        val item =
            mapOf<String, Any>(

                "id" to
                        UUID.randomUUID()
                            .toString(),

                "number" to number,

                "type" to direction,

                "startedAt" to startedAt,

                "endedAt" to endedAt,

                "durationMs" to
                        (endedAt - startedAt),

                "path" to
                        file.absolutePath,

                "fileSize" to
                        file.length(),

                "maxAmplitude" to
                        peakAmplitude,

                "audioDetected" to
                        (peakAmplitude > 0)
            )


        HistoryStore.addHistory(
            this,
            item
        )
    }


    // ============================================================
    // READ LAST CALL
    // ============================================================

    private data class RecentCall(
        val number: String,
        val type: Int,
        val date: Long
    )


    private fun findLatestMatchingCall(
        direction: String,
        recordingStartedAt: Long
    ): RecentCall? {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }


        return try {

            val projection =
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE
                )


            val cursor =
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )


            cursor?.use {

                val numberIndex =
                    it.getColumnIndex(
                        CallLog.Calls.NUMBER
                    )

                val typeIndex =
                    it.getColumnIndex(
                        CallLog.Calls.TYPE
                    )

                val dateIndex =
                    it.getColumnIndex(
                        CallLog.Calls.DATE
                    )


                var checked = 0


                while (
                    it.moveToNext() &&
                    checked < 5
                ) {

                    checked++


                    val number =
                        if (numberIndex >= 0) {
                            it.getString(
                                numberIndex
                            ) ?: "Unknown"
                        } else {
                            "Unknown"
                        }


                    val type =
                        if (typeIndex >= 0) {
                            it.getInt(
                                typeIndex
                            )
                        } else {
                            -1
                        }


                    val date =
                        if (dateIndex >= 0) {
                            it.getLong(
                                dateIndex
                            )
                        } else {
                            0L
                        }


                    val correctType =
                        if (
                            direction ==
                            "Incoming"
                        ) {

                            type ==
                                    CallLog.Calls.INCOMING_TYPE

                        } else {

                            type ==
                                    CallLog.Calls.OUTGOING_TYPE
                        }


                    val closeEnough =
                        abs(
                            date -
                                    recordingStartedAt
                        ) <
                                5 * 60 * 1000


                    if (
                        correctType &&
                        closeEnough
                    ) {

                        return RecentCall(
                            number,
                            type,
                            date
                        )
                    }
                }
            }


            null

        } catch (e: Exception) {

            e.printStackTrace()

            null
        }
    }


    // ============================================================
    // SERVICE
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        return START_STICKY
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }


    override fun onDestroy() {

        unregisterPhoneListener()

        if (recording) {
            stopCallRecording()
        }

        super.onDestroy()
    }
}


// ================================================================
// SAVE HISTORY LOCALLY
// ================================================================

object HistoryStore {

    private const val PREFS =
        "call_recorder_history"

    private const val KEY =
        "history"


    fun addHistory(
        context: Context,
        item: Map<String, Any>
    ) {

        val preferences =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )


        val oldJson =
            preferences.getString(
                KEY,
                "[]"
            ) ?: "[]"


        val oldArray =
            try {
                JSONArray(oldJson)
            } catch (_: Exception) {
                JSONArray()
            }


        val newArray =
            JSONArray()


        val objectItem =
            JSONObject()


        item.forEach {
            objectItem.put(
                it.key,
                it.value
            )
        }


        newArray.put(
            objectItem
        )


        val maximum =
            minOf(
                oldArray.length(),
                499
            )


        for (i in 0 until maximum) {
            newArray.put(
                oldArray.get(i)
            )
        }


        preferences
            .edit()
            .putString(
                KEY,
                newArray.toString()
            )
            .apply()
    }


    fun getHistory(
        context: Context
    ): List<Map<String, Any>> {

        val preferences =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )


        val json =
            preferences.getString(
                KEY,
                "[]"
            ) ?: "[]"


        val array =
            try {
                JSONArray(json)
            } catch (_: Exception) {
                JSONArray()
            }


        val result =
            mutableListOf<Map<String, Any>>()


        for (i in 0 until array.length()) {

            val item =
                array.getJSONObject(i)


            result.add(
                mapOf(

                    "id" to
                            item.optString(
                                "id"
                            ),

                    "number" to
                            item.optString(
                                "number",
                                "Unknown"
                            ),

                    "type" to
                            item.optString(
                                "type",
                                "Unknown"
                            ),

                    "startedAt" to
                            item.optLong(
                                "startedAt"
                            ),

                    "endedAt" to
                            item.optLong(
                                "endedAt"
                            ),

                    "durationMs" to
                            item.optLong(
                                "durationMs"
                            ),

                    "path" to
                            item.optString(
                                "path"
                            ),

                    "fileSize" to
                            item.optLong(
                                "fileSize"
                            ),

                    "maxAmplitude" to
                            item.optInt(
                                "maxAmplitude"
                            ),

                    "audioDetected" to
                            if (
                                item.has(
                                    "audioDetected"
                                )
                            ) {
                                item.optBoolean(
                                    "audioDetected"
                                )
                            } else {
                                true
                            }
                )
            )
        }


        return result
    }
}