package com.ncmc.ricky

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import android.widget.ProgressBar
import com.ncmc.ricky.adapter.ConversionAdapter
import com.ncmc.ricky.core.ConversionEngine
import com.ncmc.ricky.model.ConversionItem
import com.ncmc.ricky.util.StorageUtil
import java.io.File
import java.util.Locale

/**
 * 主界面：目录选择、开始/取消转换、实时进度可视化
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK_INPUT = 1001
        private const val REQ_PICK_OUTPUT = 1002
        private const val REQ_PERMISSION = 2000
    }

    private lateinit var inputPath: EditText
    private lateinit var outputPath: EditText
    private lateinit var btnStart: MaterialButton
    private lateinit var progressCard: MaterialCardView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvQueueCount: TextView
    private lateinit var adapter: ConversionAdapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConversionEngine.ACTION_PROGRESS -> renderProgress()
                ConversionEngine.ACTION_FINISHED -> {
                    renderProgress()
                    showFinishedDialog(intent)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupRecycler()

        val prefs = getSharedPreferences("ncmc", MODE_PRIVATE)
        inputPath.setText(prefs.getString("input_dir", StorageUtil.defaultInputDir().absolutePath))
        outputPath.setText(prefs.getString("output_dir", StorageUtil.defaultOutputDir().absolutePath))

        findViewById<MaterialButton>(R.id.btnPickInput).setOnClickListener {
            openPicker(REQ_PICK_INPUT, inputPath.text.toString())
        }
        findViewById<MaterialButton>(R.id.btnPickOutput).setOnClickListener {
            openPicker(REQ_PICK_OUTPUT, outputPath.text.toString())
        }
        btnStart.setOnClickListener { onStartClicked() }

        checkPermissions()
    }

    private fun bindViews() {
        inputPath = findViewById(R.id.inputPath)
        outputPath = findViewById(R.id.outputPath)
        btnStart = findViewById(R.id.btnStart)
        progressCard = findViewById(R.id.progressCard)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvQueueCount = findViewById(R.id.tvQueueCount)
    }

    private fun setupRecycler() {
        adapter = ConversionAdapter()
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = this@MainActivity.adapter
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ConversionEngine.ACTION_PROGRESS)
                addAction(ConversionEngine.ACTION_FINISHED)
            }
        )
        renderProgress()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        super.onStop()
    }

    private fun onStartClicked() {
        if (ConversionEngine.running) {
            // 正在转换 -> 请求取消
            val cancelIntent = Intent(this, NcmConversionService::class.java)
                .setAction(ConversionEngine.ACTION_CANCEL)
            startService(cancelIntent)
            return
        }

        val input = inputPath.text.toString().trim()
        val output = outputPath.text.toString().trim()
        if (input.isEmpty() || output.isEmpty()) {
            toast(getString(R.string.hint_fill_paths))
            return
        }
        if (!StorageUtil.hasNcmFiles(input)) {
            toast(getString(R.string.no_ncm_files))
            return
        }

        // 输出目录不可写时自动回退到应用专属目录（Android 12+ 作用域存储）
        var outDir = output
        if (!StorageUtil.isWritable(File(outDir))) {
            outDir = StorageUtil.fallbackOutputDir(this).absolutePath
            outputPath.setText(outDir)
            toast(getString(R.string.output_not_writable))
        }

        getSharedPreferences("ncmc", MODE_PRIVATE).edit()
            .putString("input_dir", input)
            .putString("output_dir", outDir)
            .apply()

        val intent = Intent(this, NcmConversionService::class.java)
            .setAction(ConversionEngine.ACTION_START)
            .putExtra(ConversionEngine.EXTRA_INPUT, input)
            .putExtra(ConversionEngine.EXTRA_OUTPUT, outDir)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnStart.text = getString(R.string.btn_cancel)
    }

    private fun openPicker(requestCode: Int, currentPath: String) {
        val intent = Intent(this, FolderPickerActivity::class.java)
        intent.putExtra(FolderPickerActivity.EXTRA_START, currentPath)
        startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data != null) {
            val path = data.getStringExtra(FolderPickerActivity.EXTRA_RESULT)
            if (!TextUtils.isEmpty(path)) {
                when (requestCode) {
                    REQ_PICK_INPUT -> inputPath.setText(path)
                    REQ_PICK_OUTPUT -> outputPath.setText(path)
                }
            }
        }
    }

    private fun renderProgress() {
        val items = ConversionEngine.items
        val total = items.size
        val done = items.count {
            it.status == ConversionItem.Status.DONE ||
                    it.status == ConversionItem.Status.FAILED ||
                    it.status == ConversionItem.Status.SKIPPED
        }
        adapter.submit(items)
        tvQueueCount.text = getString(R.string.queue_count, done, total)

        if (ConversionEngine.running) {
            progressCard.visibility = android.view.View.VISIBLE
            val totalBytes = ConversionEngine.totalBytes
            val doneBytes = ConversionEngine.completedBytes.get()
            val percent = if (totalBytes > 0) doneBytes * 100.0 / totalBytes else 0.0
            progressBar.progress = percent.toInt()
            tvProgress.text = getString(
                R.string.progress_summary,
                done, total, String.format(Locale.US, "%.1f", percent)
            )
            val current = items.firstOrNull { it.status == ConversionItem.Status.CONVERTING }
            tvCurrent.text = current?.let { getString(R.string.progress_current, it.file.name) } ?: ""
            val elapsedSec = (System.currentTimeMillis() - ConversionEngine.startTime) / 1000.0
            if (elapsedSec > 0 && doneBytes > 0) {
                val speed = doneBytes / 1024.0 / 1024.0 / elapsedSec
                tvSpeed.text = getString(R.string.progress_speed, String.format(Locale.US, "%.1f", speed))
            } else {
                tvSpeed.text = ""
            }
            btnStart.text = getString(R.string.btn_cancel)
        } else {
            btnStart.text = getString(R.string.btn_start)
            if (total > 0) {
                progressCard.visibility = android.view.View.VISIBLE
                progressBar.progress = 100
                tvProgress.text = getString(R.string.progress_finished, done, total)
                tvSpeed.text = ""
                tvCurrent.text = ""
            } else {
                progressCard.visibility = android.view.View.GONE
            }
        }
    }

    private fun showFinishedDialog(intent: Intent) {
        val success = intent.getIntExtra(ConversionEngine.EXTRA_SUCCESS, 0)
        val failed = intent.getIntExtra(ConversionEngine.EXTRA_FAILED, 0)
        val output = intent.getStringExtra(ConversionEngine.EXTRA_OUTPUT) ?: ""
        val message = getString(R.string.result_message, success, failed, output)
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.result_title)
            .setMessage(message)
            .setPositiveButton(R.string.result_ok, null)
            .show()
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            var granted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) granted = false
            }
            if (!granted) {
                toast(getString(R.string.permission_denied))
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
