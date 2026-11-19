package com.yujin.travellog

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.min

class SlideshowActivity : AppCompatActivity() {

    private lateinit var config: TripConfig
    private lateinit var imgSlide: ImageView
    private lateinit var videoSlide: VideoView
    private lateinit var webMapView: WebView
    private lateinit var tvPlaceCaption: TextView
    private lateinit var tvEndingOverlay: TextView
    private lateinit var frameContainer: View

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeline = mutableListOf<SlideStep>()
    private var stepIndex = 0
    private var routeMapFile: File? = null
    private var isMapOverlayVisible = false

    data class SlideStep(
        val uri: Uri?,
        val filePath: String?,
        val placeOnly: String,
        val durationMs: Long,
        val isVideo: Boolean = false,
        val isMapOverview: Boolean = false,
        val isEnding: Boolean = false,
        val transitionType: Int = 0
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slideshow)

        @Suppress("DEPRECATION")
        config = intent.getSerializableExtra("TRIP_CONFIG") as TripConfig

        imgSlide = findViewById(R.id.imgSlide)
        videoSlide = findViewById(R.id.videoSlide)
        webMapView = findViewById(R.id.webMapView)
        tvPlaceCaption = findViewById(R.id.tvPlaceCaption)
        tvEndingOverlay = findViewById(R.id.tvEndingOverlay)
        frameContainer = findViewById(R.id.frameContainer)

        setupWebMap()
        setupBgm()

        findViewById<Button>(R.id.btnToggleMap).setOnClickListener {
            isMapOverlayVisible = !isMapOverlayVisible
            webMapView.visibility = if (isMapOverlayVisible) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnSaveMp4).setOnClickListener {
            exportSlideshowMp4WithBgm()
        }

        lifecycleScope.launch {
            routeMapFile = ExifAndMapHelper.downloadKoreaRouteMap(
                this@SlideshowActivity,
                config.items,
                config.apiKey
            )
            buildTimeline()
            playNextStep()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebMap() {
        val settings: WebSettings = webMapView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webMapView.webViewClient = WebViewClient()

        val validPoints = config.items.filter { it.lat != null && it.lng != null }
        val firstLat = validPoints.firstOrNull()?.lat ?: 36.543250
        val firstLng = validPoints.firstOrNull()?.lng ?: 127.870583

        val pathJs = validPoints.joinToString(",") { "{lat: ${it.lat}, lng: ${it.lng}}" }
        val markersJs = validPoints.mapIndexed { idx, item ->
            val safePlace = item.placeName.replace("'", "\\'")
            """
            new google.maps.Marker({
                position: {lat: ${item.lat}, lng: ${item.lng}},
                map: map,
                label: '${idx + 1}',
                title: '$safePlace'
            });
            """.trimIndent()
        }.joinToString("\n")

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="initial-scale=1.0, user-scalable=yes">
              <style>
                html, body, #map { height: 100%; margin: 0; padding: 0; background: #0f172a; }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                function initMap() {
                  const center = {lat: $firstLat, lng: $firstLng};
                  const map = new google.maps.Map(document.getElementById('map'), {
                    zoom: 12,
                    center: center
                  });
                  const routePath = new google.maps.Polyline({
                    path: [$pathJs],
                    geodesic: true,
                    strokeColor: '#D4AF37',
                    strokeOpacity: 1.0,
                    strokeWeight: 4
                  });
                  routePath.setMap(map);
                  $markersJs
                }
              </script>
              <script async defer src="https://maps.googleapis.com/maps/api/js?key=${config.apiKey}&callback=initMap"></script>
            </body>
            </html>
        """.trimIndent()

        webMapView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
    }

    private fun setupBgm() {
        val bgmUri = config.getUri(config.bgmUriString) ?: return
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@SlideshowActivity, bgmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
        }
    }

    private fun buildTimeline() {
        timeline.clear()
        val coverUri = config.getUri(config.coverUriString)
            ?: config.items.firstOrNull()?.let { Uri.parse(it.uriString) }

        // 1. 도입부: 대표 이미지 -> 전체 여행경로 지도(약 2초)
        if (coverUri != null) {
            timeline.add(
                SlideStep(
                    uri = coverUri,
                    filePath = null,
                    placeOnly = config.title,
                    durationMs = 2000L,
                    transitionType = 0
                )
            )
        }

        if (routeMapFile != null) {
            timeline.add(
                SlideStep(
                    uri = null,
                    filePath = routeMapFile!!.absolutePath,
                    placeOnly = "전체 여행 경로",
                    durationMs = 2000L,
                    isMapOverview = true,
                    transitionType = 1
                )
            )
        }

        // 2. 본문: 사진 및 영상 (하단에는 장소명만 표시)
        config.items.forEachIndexed { idx, item ->
            timeline.add(
                SlideStep(
                    uri = Uri.parse(item.uriString),
                    filePath = null,
                    placeOnly = item.placeName,
                    durationMs = if (item.isVideo) 5000L else 3000L,
                    isVideo = item.isVideo,
                    transitionType = idx % 3
                )
            )
        }

        // 3. 엔딩: 대표 이미지 위에 전체 일시, 전체 장소·경로, 동반자 명단 -> 2초 페이드아웃 후 검정 마무리
        val placesRoute = config.items.map { it.placeName }.distinct().joinToString(" → ")
        val summaryText = buildString {
            appendLine(config.title)
            appendLine("일시: ${config.dateText} (${config.scheduleType})")
            appendLine("출발: ${config.departure} / 숙박: ${config.lodging}")
            appendLine("장소·경로: $placesRoute")
            append("동반자(${config.companionCount()}명): ${config.companions}")
        }
        timeline.add(
            SlideStep(
                uri = coverUri,
                filePath = null,
                placeOnly = summaryText,
                durationMs = 2500L,
                isEnding = true
            )
        )
    }

    private fun playNextStep() {
        if (stepIndex >= timeline.size) {
            tvPlaceCaption.visibility = View.GONE
            tvEndingOverlay.visibility = View.GONE
            imgSlide.visibility = View.GONE
            videoSlide.visibility = View.GONE
            webMapView.visibility = View.GONE
            frameContainer.setBackgroundColor(Color.BLACK)
            return
        }

        val step = timeline[stepIndex++]
        applyTransition(step.transitionType)

        if (step.isEnding) {
            videoSlide.visibility = View.GONE
            if (!isMapOverlayVisible) webMapView.visibility = View.GONE
            imgSlide.visibility = View.VISIBLE
            tvPlaceCaption.visibility = View.GONE
            step.uri?.let { imgSlide.setImageURI(it) }

            tvEndingOverlay.text = step.placeOnly
            tvEndingOverlay.alpha = 1f
            tvEndingOverlay.visibility = View.VISIBLE

            handler.postDelayed({
                val fadeOut = AlphaAnimation(1f, 0f).apply {
                    duration = 2000L
                    fillAfter = true
                }
                frameContainer.startAnimation(fadeOut)
            }, 500L)

            handler.postDelayed({ playNextStep() }, 2500L)
            return
        }

        tvEndingOverlay.visibility = View.GONE
        tvPlaceCaption.visibility = View.VISIBLE
        // 요구사항: 하단에 장소명만 표시 + 감성 서체
        tvPlaceCaption.text = step.placeOnly
        tvPlaceCaption.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)

        if (step.isVideo && step.uri != null) {
            imgSlide.visibility = View.GONE
            videoSlide.visibility = View.VISIBLE
            videoSlide.setVideoURI(step.uri)
            videoSlide.start()
        } else {
            videoSlide.visibility = View.GONE
            imgSlide.visibility = View.VISIBLE
            if (step.filePath != null) {
                imgSlide.setImageURI(Uri.fromFile(File(step.filePath)))
            } else {
                imgSlide.setImageURI(step.uri)
            }
        }

        handler.postDelayed({ playNextStep() }, step.durationMs)
    }

    private fun applyTransition(type: Int) {
        when (type) {
            0 -> {
                val anim = AlphaAnimation(0.15f, 1f).apply { duration = 600L }
                imgSlide.startAnimation(anim)
            }
            1 -> {
                val set = AnimationSet(true).apply {
                    addAnimation(ScaleAnimation(1.08f, 1.0f, 1.08f, 1.0f))
                    addAnimation(AlphaAnimation(0.2f, 1f))
                    duration = 700L
                }
                imgSlide.startAnimation(set)
            }
            else -> {
                val set = AnimationSet(true).apply {
                    addAnimation(TranslateAnimation(120f, 0f, 0f, 0f))
                    addAnimation(AlphaAnimation(0.2f, 1f))
                    duration = 650L
                }
                imgSlide.startAnimation(set)
            }
        }
    }

    /**
     * 원본 비율을 유지한 채 캔버스에 맞추어 그리기 (찌그러짐/늘어짐 방지)
     */
    private fun drawAspectFitBitmap(canvas: Canvas, src: Bitmap, targetRect: Rect, paint: Paint) {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        if (srcW <= 0f || srcH <= 0f) return

        val dstW = targetRect.width().toFloat()
        val dstH = targetRect.height().toFloat()
        val scale = min(dstW / srcW, dstH / srcH)

        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = targetRect.left + (dstW - drawW) / 2f
        val top = targetRect.top + (dstH - drawH) / 2f
        val dest = android.graphics.RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(src, null, dest, paint)
    }

    private fun exportSlideshowMp4WithBgm() {
        lifecycleScope.launch {
            Toast.makeText(this@SlideshowActivity, "원본 비율 유지 MP4 영상 저장 중...", Toast.LENGTH_SHORT).show()
            val ok = withContext(Dispatchers.Default) {
                encodeSlidesToMp4()
            }
            if (ok) {
                Toast.makeText(
                    this@SlideshowActivity,
                    "갤러리(Movies/TravelLog)에 MP4로 저장되었습니다.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@SlideshowActivity, "MP4 저장 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun encodeSlidesToMp4(): Boolean {
        val width = 1080
        val height = 1920
        val fps = 10
        val secondsPerSlide = 2

        val tempVideoFile = File(cacheDir, "temp_slides_${System.currentTimeMillis()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val borderPaint = Paint().apply {
            color = Color.parseColor("#D4AF37")
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFF8E7")
            textSize = 44f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        val boxPaint = Paint().apply {
            color = Color.parseColor("#88000000")
            style = Paint.Style.FILL
        }
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val contentRect = Rect(48, 80, width - 48, height - 180)

        try {
            val slides = timeline.filter { !it.isVideo }
            for (step in slides) {
                val rawBmp = loadBitmapForStep(step) ?: continue
                repeat(fps * secondsPerSlide) {
                    val canvas: Canvas = inputSurface.lockCanvas(null)
                    canvas.drawColor(Color.BLACK)
                    // 원본 비율 유지
                    drawAspectFitBitmap(canvas, rawBmp, contentRect, bmpPaint)
                    // 추억 액자 테두리
                    canvas.drawRect(Rect(24, 24, width - 24, height - 24), borderPaint)
                    if (!step.isEnding) {
                        canvas.drawRect(Rect(80, height - 150, width - 80, height - 70), boxPaint)
                        canvas.drawText(step.placeOnly, width / 2f, height - 96f, textPaint)
                    }
                    inputSurface.unlockCanvasAndPost(canvas)

                    drainEncoder(
                        encoder, muxer, bufferInfo,
                        { trackIndex = it }, { muxerStarted = it },
                        trackIndex, muxerStarted, false
                    )
                }
                rawBmp.recycle()
            }
            drainEncoder(
                encoder, muxer, bufferInfo,
                { trackIndex = it }, { muxerStarted = it },
                trackIndex, muxerStarted, true
            )
        } finally {
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop(); muxer.release() } catch (_: Exception) {}
        }

        val finalSaved = muxAudioAndSaveToGallery(tempVideoFile)
        tempVideoFile.delete()
        return finalSaved
    }

    private fun muxAudioAndSaveToGallery(videoFile: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${config.title}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TravelLog")
            }
        }
        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val bgmUri = config.getUri(config.bgmUriString)

        return try {
            val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            val videoTrackIdx = findTrack(videoExtractor, "video/")
            if (videoTrackIdx < 0) return false
            videoExtractor.selectTrack(videoTrackIdx)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            val tempOutFile = File(cacheDir, "final_mux_${System.currentTimeMillis()}.mp4")
            val outMuxer = MediaMuxer(tempOutFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = outMuxer.addTrack(videoFormat)

            var audioExtractor: MediaExtractor? = null
            var outAudioTrack = -1
            var audioTrackIdx = -1

            if (bgmUri != null) {
                val pfd = contentResolver.openFileDescriptor(bgmUri, "r")
                if (pfd != null) {
                    val ext = MediaExtractor()
                    ext.setDataSource(pfd.fileDescriptor)
                    audioTrackIdx = findTrack(ext, "audio/")
                    if (audioTrackIdx >= 0) {
                        ext.selectTrack(audioTrackIdx)
                        outAudioTrack = outMuxer.addTrack(ext.getTrackFormat(audioTrackIdx))
                        audioExtractor = ext
                    }
                    pfd.close()
                }
            }

            outMuxer.start()
            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            // 영상 트랙 복사
            var maxVideoPtsUs = 0L
            while (true) {
                val sampleSize = videoExtractor.readSampleData(buf, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = videoExtractor.sampleTime
                if (info.presentationTimeUs > maxVideoPtsUs) maxVideoPtsUs = info.presentationTimeUs
                info.flags = if ((videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                outMuxer.writeSampleData(outVideoTrack, buf, info)
                videoExtractor.advance()
            }

            // 오디오 트랙 복사 (영상 길이에 맞춰 복사)
            if (audioExtractor != null && outAudioTrack >= 0) {
                while (true) {
                    val sampleSize = audioExtractor.readSampleData(buf, 0)
                    if (sampleSize < 0) break
                    val pts = audioExtractor.sampleTime
                    if (pts > maxVideoPtsUs + 500_000L) break
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = pts
                    info.flags = if ((audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else 0
                    outMuxer.writeSampleData(outAudioTrack, buf, info)
                    audioExtractor.advance()
                }
                audioExtractor.release()
            }

            videoExtractor.release()
            outMuxer.stop()
            outMuxer.release()

            contentResolver.openOutputStream(outUri)?.use { out ->
                FileInputStream(tempOutFile).use { input ->
                    input.copyTo(out)
                }
            }
            tempOutFile.delete()
            true
        } catch (_: Exception) {
            // 오디오 믹싱이 안 되는 포맷일 경우 무음 영상이라도 저장
            try {
                contentResolver.openOutputStream(outUri)?.use { out ->
                    FileInputStream(videoFile).use { input -> input.copyTo(out) }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun loadBitmapForStep(step: SlideStep): Bitmap? {
        return try {
            if (step.filePath != null) {
                BitmapFactory.decodeFile(step.filePath)
            } else if (step.uri != null) {
                contentResolver.openInputStream(step.uri)?.use { BitmapFactory.decodeStream(it) }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        setTrack: (Int) -> Unit,
        setStarted: (Boolean) -> Unit,
        currentTrack: Int,
        isStarted: Boolean,
        endOfStream: Boolean
    ) {
        if (endOfStream) encoder.signalEndOfInputStream()
        var track = currentTrack
        var started = isStarted

        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, 10_000)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.outputFormat)
                setTrack(track)
                muxer.start()
                started = true
                setStarted(true)
            } else if (idx >= 0) {
                val outBuf = encoder.getOutputBuffer(idx)
                if (outBuf != null && info.size != 0 && started) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, outBuf, info)
                }
                encoder.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }
}