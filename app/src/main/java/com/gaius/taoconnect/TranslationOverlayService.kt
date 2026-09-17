package com.gaius.taoconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

class TranslationOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("TaoConnectCapture")
    private lateinit var captureHandler: Handler

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var translationView: TranslationOverlayView? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val hideTranslationRunnable = Runnable { removeTranslationOverlay() }

    @Volatile
    private var modelReady = false

    @Volatile
    private var modelPreparing = false

    @Volatile
    private var captureRequested = false

    @Volatile
    private var isBusy = false

    private val captureTimeout = Runnable {
        if (captureRequested) {
            captureRequested = false
            virtualDisplay?.surface = null
            showBubbleAfterCapture()
            isBusy = false
            toast(getString(R.string.translation_failed))
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopSelf() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = false
        windowManager = getSystemService(WindowManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )

        val translatorOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.FRENCH)
            .build()
        translator = Translation.getClient(translatorOptions)

        createNotificationChannel()
        prepareTranslationModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CAPTURE -> {
                requestTranslation()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startAsForeground()

                if (!Settings.canDrawOverlays(this)) {
                    toast(getString(R.string.overlay_permission_explanation))
                    stopSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = readProjectionIntent(intent)
                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startProjection(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Traduction Taobao",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Indique quand TaoConnect peut lire l’écran à votre demande."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, TranslationOverlayService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val translateIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, TranslationOverlayService::class.java).apply {
                action = ACTION_CAPTURE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.translation_notification_title))
            .setContentText(getString(R.string.translation_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.translate_now), translateIntent)
            .addAction(0, "Arrêter", stopIntent)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun readProjectionIntent(serviceIntent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serviceIntent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            serviceIntent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun prepareTranslationModel() {
        if (modelPreparing || modelReady) return
        modelPreparing = true
        toast(getString(R.string.model_downloading))
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                modelPreparing = false
                modelReady = true
                toast(getString(R.string.model_ready))
            }
            .addOnFailureListener {
                modelPreparing = false
                modelReady = false
                toast(getString(R.string.model_error))
            }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        releaseProjection()
        updateScreenMetrics()

        try {
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Autorisation de capture absente")
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            imageReader = createImageReader()

            // La surface est brièvement attachée ici puis détachée dès la première image.
            // Cela valide le VirtualDisplay une seule fois, exigence d’Android 14+.
            virtualDisplay = projection.createVirtualDisplay(
                "TaoConnectScreen",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            ) ?: throw IllegalStateException("Écran virtuel indisponible")

            showBubble()
            isRunning = true
            requestTileRefresh()
        } catch (_: Exception) {
            toast(getString(R.string.translation_failed))
            releaseProjection()
            stopSelf()
        }
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        screenDensity = resources.configuration.densityDpi
    }

    private fun createImageReader(): ImageReader =
        ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener(::onImageAvailable, captureHandler)
        }

    private fun refreshCaptureSizeIfNeeded() {
        val previousWidth = screenWidth
        val previousHeight = screenHeight
        val previousDensity = screenDensity
        updateScreenMetrics()

        if (
            previousWidth == screenWidth &&
            previousHeight == screenHeight &&
            previousDensity == screenDensity
        ) {
            return
        }

        virtualDisplay?.surface = null
        imageReader?.close()
        imageReader = createImageReader()
        virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)

        bubbleParams?.let { params ->
            params.x = params.x.coerceIn(0, max(0, screenWidth - params.width))
            params.y = params.y.coerceIn(0, max(0, screenHeight - params.height))
            bubbleView?.let { bubble ->
                try {
                    windowManager.updateViewLayout(bubble, params)
                } catch (_: Exception) {
                    // La bulle peut être retirée pendant un changement de configuration.
                }
            }
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        if (!captureRequested) {
            image.close()
            virtualDisplay?.surface = null
            return
        }

        captureRequested = false
        mainHandler.removeCallbacks(captureTimeout)
        virtualDisplay?.surface = null

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }

        mainHandler.post { showBubbleAfterCapture() }
        recognizeAndTranslate(bitmap)
    }

    private fun requestTranslation() {
        if (isBusy) {
            toast(getString(R.string.capture_in_progress))
            return
        }
        if (!modelReady) {
            toast(getString(R.string.model_downloading))
            prepareTranslationModel()
            return
        }
        if (virtualDisplay == null || imageReader == null) {
            toast(getString(R.string.translation_failed))
            return
        }

        refreshCaptureSizeIfNeeded()
        isBusy = true
        toast(getString(R.string.capture_in_progress))
        removeTranslationOverlay()
        bubbleView?.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            captureRequested = true
            virtualDisplay?.surface = imageReader?.surface
            mainHandler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
        }, OVERLAY_HIDE_DELAY_MS)
    }

    private fun showBubbleAfterCapture() {
        bubbleView?.visibility = View.VISIBLE
    }

    private fun recognizeAndTranslate(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                bitmap.recycle()
                val sourceBlocks = result.textBlocks.mapNotNull { block ->
                    val bounds = block.boundingBox ?: return@mapNotNull null
                    val text = block.text.trim()
                    if (text.isBlank() || !CHINESE_REGEX.containsMatchIn(text)) {
                        return@mapNotNull null
                    }
                    SourceBlock(Rect(bounds), text)
                }.take(MAX_TRANSLATED_BLOCKS)

                if (sourceBlocks.isEmpty()) {
                    isBusy = false
                    toast(getString(R.string.nothing_found))
                } else {
                    translateBlocks(sourceBlocks)
                }
            }
            .addOnFailureListener {
                bitmap.recycle()
                isBusy = false
                toast(getString(R.string.translation_failed))
            }
    }

    private fun translateBlocks(blocks: List<SourceBlock>) {
        val remaining = AtomicInteger(blocks.size)
        val translatedItems = Collections.synchronizedList(
            mutableListOf<TranslationItem>()
        )

        blocks.forEach { block ->
            translator.translate(block.text)
                .addOnSuccessListener { translated ->
                    if (translated.isNotBlank()) {
                        translatedItems += TranslationItem(block.bounds, translated.trim())
                    }
                }
                .addOnCompleteListener {
                    if (remaining.decrementAndGet() == 0) {
                        isBusy = false
                        val sortedItems = translatedItems.sortedWith(
                            compareBy<TranslationItem> { it.sourceBounds.top }
                                .thenBy { it.sourceBounds.left }
                        )
                        if (sortedItems.isEmpty()) {
                            toast(getString(R.string.translation_failed))
                        } else {
                            mainHandler.post { showTranslations(sortedItems) }
                        }
                    }
                }
        }
    }

    private fun showTranslations(items: List<TranslationItem>) {
        removeTranslationOverlay()

        val overlay = TranslationOverlayView(this).apply {
            setTranslations(items, screenWidth, screenHeight)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(overlay, params)
            translationView = overlay
            mainHandler.postDelayed(hideTranslationRunnable, TRANSLATION_VISIBLE_MS)
        } catch (_: Exception) {
            translationView = null
            toast(getString(R.string.translation_failed))
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val size = dp(64)
        val backgroundShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(R.color.tao_orange))
            setStroke(dp(3), 0xFFFFFFFF.toInt())
        }

        val bubble = TextView(this).apply {
            text = "文\nFR"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = backgroundShape
            elevation = dp(8).toFloat()
            contentDescription = "Traduire l’écran chinois en français"
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(dp(8), screenWidth - size - dp(14))
            y = screenHeight / 3
        }

        installBubbleTouchListener(bubble, params)

        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
            bubbleParams = params
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun installBubbleTouchListener(
        bubble: TextView,
        params: WindowManager.LayoutParams
    ) {
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downX
                        val deltaY = event.rawY - downY
                        if (abs(deltaX) > dp(5) || abs(deltaY) > dp(5)) {
                            moved = true
                        }
                        params.x = (initialX + deltaX.toInt())
                            .coerceIn(0, max(0, screenWidth - params.width))
                        params.y = (initialY + deltaY.toInt())
                            .coerceIn(0, max(0, screenHeight - params.height))
                        try {
                            windowManager.updateViewLayout(bubble, params)
                        } catch (_: Exception) {
                            // La fenêtre peut disparaître si le service est arrêté pendant le geste.
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) requestTranslation()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        if (paddedWidth == image.width) return paddedBitmap

        val cropped = Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            image.width,
            image.height
        )
        paddedBitmap.recycle()
        return cropped
    }

    private fun removeTranslationOverlay() {
        mainHandler.removeCallbacks(hideTranslationRunnable)
        translationView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // Vue déjà retirée par Android.
            }
        }
        translationView = null
    }

    private fun removeBubble() {
        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // Vue déjà retirée par Android.
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    private fun releaseProjection() {
        captureRequested = false
        mainHandler.removeCallbacks(captureTimeout)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.let { projection ->
            try {
                projection.unregisterCallback(projectionCallback)
                projection.stop()
            } catch (_: Exception) {
                // Session déjà terminée par le système ou l’utilisateur.
            }
        }
        mediaProjection = null
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun requestTileRefresh() {
        TileService.requestListeningState(
            this,
            ComponentName(this, TaoConnectTileService::class.java)
        )
    }

    override fun onDestroy() {
        isRunning = false
        requestTileRefresh()
        isBusy = false
        removeTranslationOverlay()
        removeBubble()
        releaseProjection()
        recognizer.close()
        translator.close()
        captureThread.quitSafely()
        super.onDestroy()
    }

    private data class SourceBlock(
        val bounds: Rect,
        val text: String
    )

    companion object {
        const val ACTION_START = "com.gaius.taoconnect.action.START"
        const val ACTION_STOP = "com.gaius.taoconnect.action.STOP"
        const val ACTION_CAPTURE = "com.gaius.taoconnect.action.CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        private const val NOTIFICATION_CHANNEL = "taoconnect_translation"
        private const val NOTIFICATION_ID = 1208
        private const val OVERLAY_HIDE_DELAY_MS = 280L
        private const val CAPTURE_TIMEOUT_MS = 2_500L
        private const val TRANSLATION_VISIBLE_MS = 15_000L
        private const val MAX_TRANSLATED_BLOCKS = 40

        private val CHINESE_REGEX = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]")

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
