package com.ncmc.ricky

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * 内置文件夹选择器：基于文件系统导航，兼容旧版存储路径
 */
class FolderPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START = "start_path"
        const val EXTRA_RESULT = "result_path"
    }

    private class Row(val display: String, val target: File?, val isParent: Boolean)

    private lateinit var tvPath: TextView
    private lateinit var rv: RecyclerView
    private var currentDir: File? = null
    private val rows = mutableListOf<Row>()

    private inner class FolderAdapter : RecyclerView.Adapter<FolderAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivFolderIcon)
            val name: TextView = view.findViewById(R.id.tvFolderName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_folder, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.name.text = row.display
            holder.icon.setImageResource(if (row.isParent) R.drawable.ic_folder_open else R.drawable.ic_folder)
            val color = if (row.isParent) {
                ContextCompat.getColor(this@FolderPickerActivity, R.color.textSecondary)
            } else {
                ContextCompat.getColor(this@FolderPickerActivity, R.color.colorPrimary)
            }
            ImageViewCompat.setImageTintList(holder.icon, ColorStateList.valueOf(color))
            holder.itemView.setOnClickListener {
                row.target?.let { dir ->
                    currentDir = dir
                    loadDir()
                }
            }
        }
    }

    private val adapter = FolderAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_picker)

        tvPath = findViewById(R.id.tvPath)
        rv = findViewById(R.id.rvFolders)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.folder_picker_title)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btnChoose).setOnClickListener {
            val dir = currentDir
            if (dir != null) {
                val intent = Intent().putExtra(EXTRA_RESULT, dir.absolutePath)
                setResult(RESULT_OK, intent)
                finish()
            }
        }

        val start = intent.getStringExtra(EXTRA_START)
        var dir = if (start.isNullOrEmpty()) null else File(start)
        if (dir == null || !dir.isDirectory) {
            dir = File(Environment.getExternalStorageDirectory().absolutePath)
        }
        currentDir = dir
        loadDir()
    }

    private fun loadDir() {
        val dir = currentDir ?: return
        tvPath.text = dir.absolutePath
        rows.clear()
        dir.parentFile?.let { parent ->
            if (parent.absolutePath != "/") {
                rows.add(Row(getString(R.string.folder_picker_up), parent, true))
            }
        }
        val sub = dir.listFiles { f -> f.isDirectory && !f.isHidden }
        sub?.sortedBy { it.name }?.forEach { rows.add(Row(it.name, it, false)) }
        adapter.notifyDataSetChanged()
    }
}
