package com.yujin.travellog

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
import android.media.MediaFormat
import android.media.MediaMuxer
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

class SlideshowActivity : AppCompatActivity() {

    private lateinit var config: TripConfig
    private lateinit var imgSlide: ImageView
    private lateinit var videoSlide: VideoView
    private lateinit var tvPlaceCaption: TextView
    private lateinit var tvEndingOverlay: TextView
    private lateinit var frameContainer: View

    private val handler = Handler(Looper.getMainLooper())
    private val timeline = mutableListOf<SlideStep>()
    private var stepIndex = 0
    private var routeMapFile: File? = null

    data class SlideStep(
        val uri: Uri?,
        val filePath: String?,
        val placeOnly: String,
        val durationMs: Long,
        val isVideo: Boolean = false,
        val isEnding: Boolean = false,
        val transitionType: Int = 0
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slideshow)

        config = intent.getSerializableExtra("TRIP_CONFIG") as TripConfig
        imgSlide = findViewById(R.id.imgSlide)
        videoSlide = findViewById(R.id.videoSlide)
        tvPlaceCaption = findViewById(R.id.tvPlaceCaption)
        tvEndingOverlay = findViewById(R.id.tvEndingOverlay)
        frameContainer = findViewById(R.id.frameContainer)

        val btnSaveMp4: Button = findViewById(R.id.btnSaveMp4)
        btnSaveMp4.setOnClickListener { exportSlideshowMp4() }

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

    private fun buildTimeline() {
        timeline.clear()
        val coverUri = config.getUri(config.coverUriString)
            ?: config.items.firstOrNull()?.let { Uri.parse(it.uriString) }

        // 1. 도입부: 대표 이미지 -> 전체 여행경로 지도(약 2초)
        if (coverUri != null) {
            timeline.add(SlideStep(coverUri, null, config.title, 2000L, transitionType = 0))
        }
        routeMapFile?.let { file ->
            timeline.add(
                SlideStep(
                    uri = null,
                    filePath = file.absolutePath,
                    placeOnly = "전체 여행 경로",
                    durationMs = 2000L,
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
            appendLine("경로·장소: $placesRoute")
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
            // 최종 검정 화면 마무리
            tvPlaceCaption.visibility = View.GONE
            tvEndingOverlay.visibility = View.GONE
            imgSlide.visibility = View.GONE
            videoSlide.visibility = View.GONE
            frameContainer.setBackgroundColor(Color.BLACK)
            return
        }

        val step = timeline[stepIndex++]
        applyTransition(step.transitionType)

        if (step.isEnding) {
            videoSlide.visibility = View.GONE
            imgSlide.visibility = View.VISIBLE
            tvPlaceCaption.visibility = View.GONE
            step.uri?.let { imgSlide.setImageURI(it) }

            tvEndingOverlay.text = step.placeOnly
            tvEndingOverlay.alpha = 1f
            tvEndingOverlay.visibility = View.VISIBLE

            // 2초 페이드아웃 연출
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
        // 요구사항 반영: 하단에는 장소명만 표시 + 감성 서체
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
     * 슬라이드 이미지를 MP4 파일로 저장 (MediaCodec + MediaMuxer)
     */
    private fun exportSlideshowMp4() {
        lifecycleScope.launch {
            Toast.makeText(this@SlideshowActivity, "MP4 영상 저장을 시작합니다...", Toast.LENGTH_SHORT).show()
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

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${config.title}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TravelLog")
            }
        }

        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val pfd = contentResolver.openFileDescriptor(outUri, "w") ?: return false

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val borderPaint = Paint().apply {
            color = Color.parseColor("#D4AF37")
            style = Paint.Style.STROKE
            strokeWidth = 24f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }

        try {
            val slides = timeline.filter { !it.isVideo }
            var frameCount = 0

            for (step in slides) {
                val rawBmp = loadBitmapForStep(step) ?: continue
                val scaled = Bitmap.createScaledBitmap(rawBmp, width, height, true)

                repeat(fps * secondsPerSlide) {
                    val canvas: Canvas = inputSurface.lockCanvas(null)
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(scaled, 0f, 0f, null)
                    // 추억 액자 테두리
                    canvas.drawRect(Rect(24, 24, width - 24, height - 24), borderPaint)
                    if (!step.isEnding) {
                        canvas.drawText(step.placeOnly, width / 2f, height - 120f, textPaint)
                    }
                    inputSurface.unlockCanvasAndPost(canvas)

                    drainEncoder(encoder, muxer, bufferInfo, { trackIndex = it }, { muxerStarted = it }, trackIndex, muxerStarted, false)
                    frameCount++
                }
            }
            drainEncoder(encoder, muxer, bufferInfo, { trackIndex = it }, { muxerStarted = it }, trackIndex, muxerStarted, true)
        } finally {
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop(); muxer.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
        return true
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
    }
}