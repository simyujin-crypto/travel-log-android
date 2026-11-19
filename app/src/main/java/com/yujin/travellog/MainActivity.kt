package com.yujin.travellog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var coverUri: Uri? = null
    private var bgmUri: Uri? = null
    private var selectedMediaUris: List<Uri> = emptyList()

    private lateinit var tvStatus: TextView
    private lateinit var tvBgmStatus: TextView

    private val pickCoverLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                coverUri = uri
                Toast.makeText(this, "대표 표지 이미지가 선택되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    private val pickBgmLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                bgmUri = uri
                tvBgmStatus.text = "선택된 배경음악: ${uri.lastPathSegment ?: "오디오 파일"}"
            }
        }

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                selectedMediaUris = uris
                tvStatus.text = "선택된 사진/영상: ${uris.size}개"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etTitle = findViewById<EditText>(R.id.etTitle)
        val etDate = findViewById<EditText>(R.id.etDate)
        val etCompanions = findViewById<EditText>(R.id.etCompanions)
        val spSchedule = findViewById<Spinner>(R.id.spSchedule)
        val etDeparture = findViewById<EditText>(R.id.etDeparture)
        val etLodging = findViewById<EditText>(R.id.etLodging)
        tvStatus = findViewById(R.id.tvStatus)
        tvBgmStatus = findViewById(R.id.tvBgmStatus)

        val schedules = arrayOf("당일", "1박", "2박", "3박", "4박")
        spSchedule.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, schedules)

        findViewById<Button>(R.id.btnSelectCover).setOnClickListener {
            pickCoverLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.btnSelectBgm).setOnClickListener {
            pickBgmLauncher.launch("audio/*")
        }

        findViewById<Button>(R.id.btnSelectMedia).setOnClickListener {
            pickMediaLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.btnStartSlideshow).setOnClickListener {
            if (selectedMediaUris.isEmpty()) {
                Toast.makeText(this, "여행 사진이나 영상을 먼저 선택해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tripTitle = etTitle.text.toString().ifBlank { "나의 여행" }
            tvStatus.text = "사진 위치(GPS) 및 경로 분석 중..."
            val apiKey = "AIzaSyCm6JNhKc5ghV8qSTIzrrf5FYjlHqoa6jo"

            lifecycleScope.launch {
                val parsedItems = ExifAndMapHelper.parseMediaList(
                    this@MainActivity,
                    selectedMediaUris,
                    apiKey,
                    tripTitle
                )

                val config = TripConfig(
                    title = tripTitle,
                    dateText = etDate.text.toString(),
                    companions = etCompanions.text.toString(),
                    scheduleType = spSchedule.selectedItem.toString(),
                    departure = etDeparture.text.toString(),
                    lodging = etLodging.text.toString(),
                    coverUriString = coverUri?.toString(),
                    bgmUriString = bgmUri?.toString(),
                    apiKey = apiKey,
                    items = ArrayList(parsedItems)
                )

                val intent = Intent(this@MainActivity, SlideshowActivity::class.java).apply {
                    putExtra("TRIP_CONFIG", config)
                }
                startActivity(intent)
                tvStatus.text = "선택된 사진/영상: ${selectedMediaUris.size}개"
            }
        }
    }
}