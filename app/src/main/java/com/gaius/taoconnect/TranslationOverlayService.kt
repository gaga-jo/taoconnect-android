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
import android.graphics.Color
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
import android.util.LruCache
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private var bubbleBackground: GradientDrawable? = null
    private var translationView: TranslationOverlayView? = null
    private var currentTranslations: List<TranslationItem> = emptyList()
    private var lastContentSignature: String? = null
    private var lastSourceBlocks: List<SourceBlock> = emptyList()
    private var consecutiveEmptyResults = 0
    private var translationTimeoutRunnable: Runnable? = null
    private var lastScanDetectedChange = true

    private val translationCache = LruCache<String, String>(
        MAX_TRANSLATION_CACHE_ENTRIES
    )

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val hideTranslationRunnable = Runnable {
        removeTranslationOverlay(animated = true)
    }

    private val autoRefreshRunnable = Runnable {
        if (!autoTranslateEnabled || !isRunning) return@Runnable
        if (isBusy) {
            scheduleNextAutoRefresh()
        } else {
            requestTranslation(automatic = true, showFeedback = false)
        }
    }

    @Volatile
    private var modelReady = false

    @Volatile
    private var modelPreparing = false

    @Volatile
    private var captureRequested = false

    @Volatile
    private var isBusy = false

    @Volatile
    private var autoTranslateEnabled = false

    @Volatile
    private var currentRequestAutomatic = false

    private val captureTimeout = Runnable {
        if (captureRequested) {
            val automatic = currentRequestAutomatic
            captureRequested = false
            virtualDisplay?.surface = null
            restoreViewsAfterCapture()
            markOverlayFresh()
            isBusy = false
            if (automatic) {
                scheduleNextAutoRefresh()
            } else {
                toast(getString(R.string.translation_failed))
            }
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
                if (autoTranslateEnabled && !isBusy) {
                    mainHandler.post {
                        requestTranslation(automatic = true, showFeedback = false)
                    }
                }
            }
            .addOnFailureListener {
                modelPreparing = false
                modelReady = false
                autoTranslateEnabled = false
                updateBubbleAppearance()
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

        val automatic = currentRequestAutomatic
        mainHandler.post { restoreViewsAfterCapture() }
        val bitmap = try {
            imageToBitmap(image)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            isBusy = false
            mainHandler.post {
                markOverlayFresh()
                if (automatic) {
                    scheduleNextAutoRefresh()
                } else {
                    toast(getString(R.string.translation_failed))
                }
            }
            return
        }

        recognizeAndTranslate(bitmap, automatic)
    }

    private fun requestTranslation(
        automatic: Boolean = false,
        showFeedback: Boolean = !automatic
    ) {
        if (isBusy) {
            if (showFeedback) toast(getString(R.string.capture_in_progress))
            return
        }
        if (!modelReady) {
            if (showFeedback) toast(getString(R.string.model_downloading))
            prepareTranslationModel()
            return
        }
        if (virtualDisplay == null || imageReader == null) {
            toast(getString(R.string.translation_failed))
            return
        }

        refreshCaptureSizeIfNeeded()
        isBusy = true
        currentRequestAutomatic = automatic
        if (showFeedback) toast(getString(R.string.capture_in_progress))
        prepareViewsForCapture()

        mainHandler.postDelayed({
            if (automatic && !autoTranslateEnabled) {
                isBusy = false
                restoreViewsAfterCapture()
            } else {
                captureRequested = true
                virtualDisplay?.surface = imageReader?.surface
                mainHandler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
            }
        }, OVERLAY_HIDE_DELAY_MS)
    }

    private fun prepareViewsForCapture() {
        mainHandler.removeCallbacks(hideTranslationRunnable)
        translationView?.apply {
            animate().cancel()
            alpha = 0f
        }
        bubbleView?.visibility = View.INVISIBLE
    }

    private fun restoreViewsAfterCapture() {
        bubbleView?.visibility = View.VISIBLE
        translationView?.apply {
            visibility = View.VISIBLE
            animate().cancel()
            alpha = STALE_OVERLAY_ALPHA
        }
    }

    private fun markOverlayFresh() {
        translationView?.apply {
            visibility = View.VISIBLE
            animate().cancel()
            animate()
                .alpha(1f)
                .setDuration(OVERLAY_SWAP_DURATION_MS)
                .start()
        }
    }

    private fun recognizeAndTranslate(bitmap: Bitmap, automatic: Boolean) {
        val focusRegion = detectModalFocusRegion(bitmap)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val sourceBlocks = try {
                    result.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->
                            val bounds = line.boundingBox ?: return@mapNotNull null
                            if (
                                focusRegion != null &&
                                !focusRegion.contains(bounds.centerX(), bounds.centerY())
                            ) {
                                return@mapNotNull null
                            }
                            val text = TranslationTextPolicy.normalizeSource(line.text)
                            if (!TranslationTextPolicy.shouldTranslate(text)) {
                                return@mapNotNull null
                            }
                            val backgroundColor = sampleBackgroundColor(bitmap, bounds)
                            SourceBlock(
                                bounds = Rect(bounds),
                                text = text.take(MAX_SOURCE_TEXT_LENGTH),
                                priority = translationPriority(
                                    text = text,
                                    bounds = bounds,
                                    modalFocused = focusRegion != null
                                ),
                                backgroundColor = backgroundColor,
                                foregroundColor = readableTextColor(backgroundColor)
                            )
                        }
                        .distinctBy { TranslationTextPolicy.cacheKey(it.text) }
                        .sortedByDescending { it.priority }
                        .take(MAX_TRANSLATED_BLOCKS)
                        .sortedWith(
                            compareBy<SourceBlock> { it.bounds.top }
                                .thenBy { it.bounds.left }
                        )
                } catch (_: Exception) {
                    emptyList()
                } finally {
                    bitmap.recycle()
                }

                if (sourceBlocks.isEmpty()) {
                    consecutiveEmptyResults += 1
                    isBusy = false
                    mainHandler.post {
                        if (consecutiveEmptyResults >= EMPTY_RESULTS_BEFORE_CLEAR) {
                            lastContentSignature = null
                            lastSourceBlocks = emptyList()
                            removeTranslationOverlay(animated = true)
                        } else {
                            markOverlayFresh()
                        }
                        if (automatic) {
                            scheduleNextAutoRefresh()
                        } else {
                            toast(getString(R.string.nothing_found))
                        }
                    }
                } else {
                    consecutiveEmptyResults = 0
                    val signature = contentSignature(sourceBlocks)
                    if (
                        (
                            signature == lastContentSignature ||
                                isEquivalentContent(sourceBlocks, lastSourceBlocks)
                            ) &&
                        currentTranslations.isNotEmpty()
                    ) {
                        lastScanDetectedChange = false
                        isBusy = false
                        mainHandler.post {
                            markOverlayFresh()
                            if (automatic) scheduleNextAutoRefresh()
                        }
                    } else {
                        lastScanDetectedChange = true
                        translateBlocks(sourceBlocks, signature, automatic)
                    }
                }
            }
            .addOnFailureListener {
                bitmap.recycle()
                isBusy = false
                mainHandler.post {
                    markOverlayFresh()
                    if (automatic) {
                        scheduleNextAutoRefresh()
                    } else {
                        toast(getString(R.string.translation_failed))
                    }
                }
            }
    }

    private fun translationPriority(
        text: String,
        bounds: Rect,
        modalFocused: Boolean
    ): Int {
        val chineseCharacterCount = TranslationTextPolicy.chineseCharacterCount(text)
        val compactTextScore = when {
            chineseCharacterCount <= 8 -> 12_000
            chineseCharacterCount <= 18 -> 8_000
            chineseCharacterCount <= 32 -> 4_000
            else -> 800
        }
        val actionScore = if (TranslationTextPolicy.isAction(text)) {
            ACTION_PRIORITY_BONUS
        } else {
            0
        }
        val knownLabelScore = if (TranslationTextPolicy.localTranslation(text) != null) {
            KNOWN_LABEL_PRIORITY_BONUS
        } else {
            0
        }
        val modalScore = if (modalFocused) MODAL_PRIORITY_BONUS else 0
        val areaScore = (
            bounds.width().coerceAtLeast(1) *
                bounds.height().coerceAtLeast(1)
            ).coerceAtMost(120_000) / 120

        return actionScore + knownLabelScore + compactTextScore + modalScore + areaScore
    }

    private fun contentSignature(blocks: List<SourceBlock>): String =
        blocks.joinToString(separator = "|") { block ->
            val bounds = block.bounds
            buildString {
                append(TranslationTextPolicy.cacheKey(block.text))
                append('@')
                append(bounds.left / SIGNATURE_GRID_SIZE)
                append(',')
                append(bounds.top / SIGNATURE_GRID_SIZE)
                append(',')
                append(bounds.right / SIGNATURE_GRID_SIZE)
                append(',')
                append(bounds.bottom / SIGNATURE_GRID_SIZE)
            }
        }

    private fun isEquivalentContent(
        newBlocks: List<SourceBlock>,
        previousBlocks: List<SourceBlock>
    ): Boolean {
        if (newBlocks.isEmpty() || newBlocks.size != previousBlocks.size) return false

        val newGroups = newBlocks.groupBy { TranslationTextPolicy.cacheKey(it.text) }
        val previousGroups = previousBlocks.groupBy { TranslationTextPolicy.cacheKey(it.text) }
        if (newGroups.keys != previousGroups.keys) return false

        val positionTolerance = dp(CONTENT_POSITION_TOLERANCE_DP)
        val sizeTolerance = dp(CONTENT_SIZE_TOLERANCE_DP)

        return newGroups.all { (key, currentGroup) ->
            val oldGroup = previousGroups[key] ?: return@all false
            if (currentGroup.size != oldGroup.size) return@all false

            val currentSorted = currentGroup.sortedWith(
                compareBy<SourceBlock> { it.bounds.top }.thenBy { it.bounds.left }
            )
            val oldSorted = oldGroup.sortedWith(
                compareBy<SourceBlock> { it.bounds.top }.thenBy { it.bounds.left }
            )

            currentSorted.zip(oldSorted).all { (current, old) ->
                abs(current.bounds.centerX() - old.bounds.centerX()) <= positionTolerance &&
                    abs(current.bounds.centerY() - old.bounds.centerY()) <= positionTolerance &&
                    abs(current.bounds.width() - old.bounds.width()) <= sizeTolerance &&
                    abs(current.bounds.height() - old.bounds.height()) <= sizeTolerance
            }
        }
    }

    private fun sampleBackgroundColor(bitmap: Bitmap, sourceBounds: Rect): Int {
        val left = sourceBounds.left.coerceIn(0, bitmap.width - 1)
        val top = sourceBounds.top.coerceIn(0, bitmap.height - 1)
        val right = sourceBounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = sourceBounds.bottom.coerceIn(top + 1, bitmap.height)
        val insetX = max(1, (right - left) / 10)
        val insetY = max(1, (bottom - top) / 6)
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        val points = listOf(
            left + insetX to top + insetY,
            centerX to top + insetY,
            right - insetX - 1 to top + insetY,
            left + insetX to centerY,
            right - insetX - 1 to centerY,
            left + insetX to bottom - insetY - 1,
            centerX to bottom - insetY - 1,
            right - insetX - 1 to bottom - insetY - 1
        )

        var red = 0L
        var green = 0L
        var blue = 0L
        points.forEach { (x, y) ->
            val pixel = bitmap.getPixel(
                x.coerceIn(0, bitmap.width - 1),
                y.coerceIn(0, bitmap.height - 1)
            )
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
        }

        return Color.rgb(
            (red / points.size).toInt(),
            (green / points.size).toInt(),
            (blue / points.size).toInt()
        )
    }

    private fun readableTextColor(backgroundColor: Int): Int {
        val luminance = (
            Color.red(backgroundColor) * 77 +
                Color.green(backgroundColor) * 150 +
                Color.blue(backgroundColor) * 29
            ) shr 8
        return if (luminance >= 148) Color.rgb(35, 38, 42) else Color.WHITE
    }

    private fun detectModalFocusRegion(bitmap: Bitmap): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 100 || height < 100) return null

        val center = Rect(
            width * 10 / 100,
            height * 18 / 100,
            width * 90 / 100,
            height * 82 / 100
        )
        val edgeRegions = listOf(
            Rect(0, height * 12 / 100, width * 8 / 100, height * 88 / 100),
            Rect(width * 92 / 100, height * 12 / 100, width, height * 88 / 100),
            Rect(width * 10 / 100, height * 8 / 100, width * 90 / 100, height * 17 / 100),
            Rect(width * 10 / 100, height * 83 / 100, width * 90 / 100, height * 92 / 100)
        )

        val centerLuminance = averageLuminance(bitmap, center)
        val edgeLuminance = edgeRegions
            .map { averageLuminance(bitmap, it) }
            .average()

        return if (
            centerLuminance >= MIN_MODAL_LUMINANCE &&
            centerLuminance - edgeLuminance >= MIN_MODAL_LUMINANCE_GAP
        ) {
            center
        } else {
            null
        }
    }

    private fun averageLuminance(bitmap: Bitmap, region: Rect): Double {
        val step = (
            minOf(region.width(), region.height()) / LUMINANCE_SAMPLE_DIVISOR
            ).coerceIn(MIN_LUMINANCE_SAMPLE_STEP, MAX_LUMINANCE_SAMPLE_STEP)
        var total = 0L
        var count = 0
        var y = region.top

        while (y < region.bottom) {
            var x = region.left
            while (x < region.right) {
                val pixel = bitmap.getPixel(x, y)
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                total += (red * 77 + green * 150 + blue * 29) shr 8
                count += 1
                x += step
            }
            y += step
        }

        return if (count == 0) 0.0 else total.toDouble() / count.toDouble()
    }

    private fun translateBlocks(
        blocks: List<SourceBlock>,
        contentSignature: String,
        automatic: Boolean
    ) {
        val translatedItems = Collections.synchronizedList(
            mutableListOf<TranslationItem>()
        )
        val uncachedBlocks = mutableListOf<SourceBlock>()

        blocks.forEach { block ->
            val localTranslation = TranslationTextPolicy.localTranslation(block.text)
            val cached = synchronized(translationCache) {
                translationCache.get(TranslationTextPolicy.cacheKey(block.text))
            }
            val readyTranslation = localTranslation ?: cached
            if (readyTranslation != null) {
                TranslationTextPolicy.cleanTranslation(block.text, readyTranslation)
                    ?.let { translated ->
                        translatedItems += block.toTranslationItem(translated)
                    }
                return@forEach
            }
            uncachedBlocks += block
        }

        if (uncachedBlocks.isEmpty()) {
            finishTranslation(
                translatedItems = translatedItems,
                sourceBlocks = blocks,
                contentSignature = contentSignature,
                automatic = automatic
            )
            return
        }

        val remaining = AtomicInteger(uncachedBlocks.size)
        val batchFinished = AtomicBoolean(false)
        fun completeBatchOnce() {
            if (!batchFinished.compareAndSet(false, true)) return
            translationTimeoutRunnable?.let { pending ->
                mainHandler.removeCallbacks(pending)
            }
            translationTimeoutRunnable = null
            finishTranslation(
                translatedItems = translatedItems,
                sourceBlocks = blocks,
                contentSignature = contentSignature,
                automatic = automatic
            )
        }

        val timeout = Runnable { completeBatchOnce() }
        translationTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, TRANSLATION_BATCH_TIMEOUT_MS)

        uncachedBlocks.forEach { block ->
            translator.translate(block.text)
                .addOnSuccessListener { translated ->
                    TranslationTextPolicy.cleanTranslation(block.text, translated)
                        ?.let { cleanedTranslation ->
                        synchronized(translationCache) {
                            translationCache.put(
                                TranslationTextPolicy.cacheKey(block.text),
                                cleanedTranslation
                            )
                        }
                        translatedItems += block.toTranslationItem(cleanedTranslation)
                    }
                }
                .addOnCompleteListener {
                    if (remaining.decrementAndGet() == 0) {
                        completeBatchOnce()
                    }
                }
        }
    }

    private fun SourceBlock.toTranslationItem(translatedText: String): TranslationItem =
        TranslationItem(
            sourceBounds = Rect(bounds),
            translatedText = translatedText,
            presentation = if (
                TranslationTextPolicy.shouldUseInlinePresentation(text, translatedText)
            ) {
                TranslationPresentation.INLINE
            } else {
                TranslationPresentation.CALLOUT
            },
            backgroundColor = backgroundColor,
            foregroundColor = foregroundColor,
            priority = priority
        )

    private fun finishTranslation(
        translatedItems: MutableList<TranslationItem>,
        sourceBlocks: List<SourceBlock>,
        contentSignature: String,
        automatic: Boolean
    ) {
        isBusy = false
        if (automatic && !autoTranslateEnabled) return

        val sortedItems = synchronized(translatedItems) {
            translatedItems
                .distinctBy { item ->
                    val bounds = item.sourceBounds
                    "${item.translatedText.lowercase().trim()}@${bounds.centerX() / 24},${bounds.centerY() / 24}"
                }
                .sortedWith(
                    compareByDescending<TranslationItem> { it.priority }
                        .thenBy { it.sourceBounds.top }
                        .thenBy { it.sourceBounds.left }
                )
        }

        mainHandler.post {
            if (automatic && !autoTranslateEnabled) return@post
            if (sortedItems.isEmpty()) {
                markOverlayFresh()
                if (!automatic) toast(getString(R.string.translation_failed))
            } else {
                lastContentSignature = contentSignature
                lastSourceBlocks = sourceBlocks.map { block ->
                    block.copy(bounds = Rect(block.bounds))
                }
                showTranslations(sortedItems)
            }
            if (automatic) scheduleNextAutoRefresh()
        }
    }

    private fun showTranslations(items: List<TranslationItem>) {
        mainHandler.removeCallbacks(hideTranslationRunnable)
        currentTranslations = items
        val avoidanceAreas = bubbleAvoidanceAreas()

        translationView?.let { overlay ->
            overlay.setTranslations(items, screenWidth, screenHeight, avoidanceAreas)
            overlay.visibility = View.VISIBLE
            overlay.animate().cancel()
            overlay.animate()
                .alpha(1f)
                .setDuration(OVERLAY_SWAP_DURATION_MS)
                .start()
            if (!autoTranslateEnabled) {
                mainHandler.postDelayed(hideTranslationRunnable, TRANSLATION_VISIBLE_MS)
            }
            return
        }

        val overlay = TranslationOverlayView(this).apply {
            setTranslations(items, screenWidth, screenHeight, avoidanceAreas)
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
            alpha = TOUCH_THROUGH_WINDOW_ALPHA
        }

        try {
            windowManager.addView(overlay, params)
            translationView = overlay
            if (!autoTranslateEnabled) {
                mainHandler.postDelayed(hideTranslationRunnable, TRANSLATION_VISIBLE_MS)
            }
        } catch (_: Exception) {
            translationView = null
            currentTranslations = emptyList()
            toast(getString(R.string.translation_failed))
        }
    }

    private fun bubbleAvoidanceAreas(): List<Rect> {
        val params = bubbleParams ?: return emptyList()
        val padding = dp(BUBBLE_AVOIDANCE_PADDING_DP)
        return listOf(
            Rect(
                (params.x - padding).coerceAtLeast(0),
                (params.y - padding).coerceAtLeast(0),
                (params.x + params.width + padding).coerceAtMost(screenWidth),
                (params.y + params.height + padding).coerceAtMost(screenHeight)
            )
        )
    }

    private fun refreshOverlayAvoidance() {
        if (currentTranslations.isEmpty()) return
        translationView?.setTranslations(
            currentTranslations,
            screenWidth,
            screenHeight,
            bubbleAvoidanceAreas()
        )
    }

    private fun toggleAutoTranslation() {
        autoTranslateEnabled = !autoTranslateEnabled
        mainHandler.removeCallbacks(autoRefreshRunnable)
        updateBubbleAppearance()

        if (autoTranslateEnabled) {
            lastScanDetectedChange = true
            toast(getString(R.string.smooth_mode_started))
            requestTranslation(automatic = true, showFeedback = false)
        } else {
            removeTranslationOverlay(animated = true)
            toast(getString(R.string.smooth_mode_stopped))
        }
    }

    private fun scheduleNextAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable)
        if (autoTranslateEnabled && isRunning) {
            val delay = if (lastScanDetectedChange) {
                AUTO_REFRESH_MOVING_INTERVAL_MS
            } else {
                AUTO_REFRESH_STILL_INTERVAL_MS
            }
            mainHandler.postDelayed(autoRefreshRunnable, delay)
        }
    }

    private fun updateBubbleAppearance() {
        bubbleView?.apply {
            text = if (autoTranslateEnabled) "AUTO\nFR" else "文\nFR"
            textSize = if (autoTranslateEnabled) 10.5f else 13f
            contentDescription = getString(
                if (autoTranslateEnabled) {
                    R.string.bubble_stop_description
                } else {
                    R.string.bubble_start_description
                }
            )
        }
        bubbleBackground?.setColor(
            getColor(if (autoTranslateEnabled) R.color.success else R.color.tao_orange)
        )
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val size = dp(BUBBLE_SIZE_DP)
        val backgroundShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(R.color.tao_orange))
            setStroke(dp(3), 0xFFFFFFFF.toInt())
        }
        bubbleBackground = backgroundShape

        val bubble = TextView(this).apply {
            text = "文\nFR"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = backgroundShape
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.bubble_start_description)
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
            updateBubbleAppearance()
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
                        if (!moved) {
                            toggleAutoTranslation()
                        } else {
                            val edgePadding = dp(BUBBLE_EDGE_PADDING_DP)
                            params.x = if (params.x + params.width / 2 < screenWidth / 2) {
                                edgePadding
                            } else {
                                max(edgePadding, screenWidth - params.width - edgePadding)
                            }
                            try {
                                windowManager.updateViewLayout(bubble, params)
                            } catch (_: Exception) {
                                // Le service peut être arrêté à la fin du geste.
                            }
                            refreshOverlayAvoidance()
                        }
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

    private fun removeTranslationOverlay(animated: Boolean = false) {
        mainHandler.removeCallbacks(hideTranslationRunnable)
        currentTranslations = emptyList()
        val view = translationView ?: return
        translationView = null

        val removeView = Runnable {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // Vue déjà retirée par Android.
            }
        }

        if (animated && view.isAttachedToWindow) {
            view.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_DURATION_MS)
                .withEndAction(removeView)
                .start()
        } else {
            removeView.run()
        }
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
        bubbleBackground = null
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
        autoTranslateEnabled = false
        requestTileRefresh()
        isBusy = false
        mainHandler.removeCallbacks(autoRefreshRunnable)
        translationTimeoutRunnable?.let { pending ->
            mainHandler.removeCallbacks(pending)
        }
        translationTimeoutRunnable = null
        removeTranslationOverlay()
        removeBubble()
        releaseProjection()
        recognizer.close()
        translator.close()
        synchronized(translationCache) {
            translationCache.evictAll()
        }
        captureThread.quitSafely()
        super.onDestroy()
    }

    private data class SourceBlock(
        val bounds: Rect,
        val text: String,
        val priority: Int,
        val backgroundColor: Int,
        val foregroundColor: Int
    )

    companion object {
        const val ACTION_START = "com.gaius.taoconnect.action.START"
        const val ACTION_STOP = "com.gaius.taoconnect.action.STOP"
        const val ACTION_CAPTURE = "com.gaius.taoconnect.action.CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        private const val NOTIFICATION_CHANNEL = "taoconnect_translation"
        private const val NOTIFICATION_ID = 1208
        private const val OVERLAY_HIDE_DELAY_MS = 48L
        private const val CAPTURE_TIMEOUT_MS = 2_500L
        private const val TRANSLATION_BATCH_TIMEOUT_MS = 6_000L
        private const val TRANSLATION_VISIBLE_MS = 30_000L
        private const val AUTO_REFRESH_MOVING_INTERVAL_MS = 900L
        private const val AUTO_REFRESH_STILL_INTERVAL_MS = 1_500L
        private const val FADE_OUT_DURATION_MS = 120L
        private const val OVERLAY_SWAP_DURATION_MS = 140L
        private const val STALE_OVERLAY_ALPHA = 1f
        private const val MAX_TRANSLATED_BLOCKS = 8
        private const val MAX_SOURCE_TEXT_LENGTH = 90
        private const val MAX_TRANSLATION_CACHE_ENTRIES = 256
        private const val EMPTY_RESULTS_BEFORE_CLEAR = 2
        private const val SIGNATURE_GRID_SIZE = 24
        private const val ACTION_PRIORITY_BONUS = 20_000
        private const val KNOWN_LABEL_PRIORITY_BONUS = 12_000
        private const val MODAL_PRIORITY_BONUS = 10_000
        private const val LUMINANCE_SAMPLE_DIVISOR = 20
        private const val MIN_LUMINANCE_SAMPLE_STEP = 6
        private const val MAX_LUMINANCE_SAMPLE_STEP = 24
        private const val MIN_MODAL_LUMINANCE = 135.0
        private const val MIN_MODAL_LUMINANCE_GAP = 45.0
        private const val CONTENT_POSITION_TOLERANCE_DP = 10
        private const val CONTENT_SIZE_TOLERANCE_DP = 8
        private const val BUBBLE_SIZE_DP = 56
        private const val BUBBLE_EDGE_PADDING_DP = 8
        private const val BUBBLE_AVOIDANCE_PADDING_DP = 6
        private const val TOUCH_THROUGH_WINDOW_ALPHA = 0.79f

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
