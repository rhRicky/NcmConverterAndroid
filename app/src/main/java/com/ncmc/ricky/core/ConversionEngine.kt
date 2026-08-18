package com.ncmc.ricky.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ncmc.ricky.model.ConversionItem
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 转换引擎：2 线程并发处理 + 聚合进度 + 取消支持。
 * 通过 LocalBroadcastManager 向 UI / 前台服务广播进度。
 */
object ConversionEngine {

    private const val TAG = "ConversionEngine"

    const val ACTION_START = "com.ncmc.ricky.action.START"
    const val ACTION_CANCEL = "com.ncmc.ricky.action.CANCEL"
    const val ACTION_PROGRESS = "com.ncmc.ricky.action.PROGRESS"
    const val ACTION_FINISHED = "com.ncmc.ricky.action.FINISHED"

    const val EXTRA_INPUT = "input_dir"
    const val EXTRA_OUTPUT = "output_dir"
    const val EXTRA_THREADS = "threads"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_FAILED = "failed"

    @Volatile
    var running = false

    @Volatile
    var cancelled = false

    private val successCounter = AtomicInteger(0)
    private val failedCounter = AtomicInteger(0)

    @Volatile
    var outputDir: String = ""

    @Volatile
    var threadCount = 2

    @Volatile
    var totalBytes: Long = 0

    @Volatile
    var startTime: Long = 0

    val items: MutableList<ConversionItem> =
        java.util.Collections.synchronizedList(mutableListOf<ConversionItem>())
    val completedBytes = AtomicLong(0)

    private var executor: java.util.concurrent.ExecutorService? = null

    @Synchronized
    fun start(inputDir: File, outDir: File, threads: Int, context: Context) {
        if (running) return

        val files = inputDir.listFiles { _, name ->
            name.toLowerCase().endsWith(".ncm")
        }?.sortedBy { it.name } ?: emptyList()

        cancelled = false
        running = true
        threadCount = threads.coerceIn(1, 8)
        successCounter.set(0)
        failedCounter.set(0)
        completedBytes.set(0)
        startTime = System.currentTimeMillis()
        outputDir = outDir.absolutePath

        items.clear()
        var total = 0L
        for (f in files) total += f.length()
        totalBytes = total
        items.addAll(files.map { ConversionItem(it, it.length()) })

        if (files.isEmpty()) {
            markDone(context)
            return
        }
        executor?.shutdown()
        val pool = Executors.newFixedThreadPool(threadCount)
        executor = pool
        for (item in items) {
            pool.execute { convertOne(item, outDir, context) }
        }
    }

    fun cancel(context: Context) {
        cancelled = true
        Log.i(TAG, "已请求取消")
    }

    private fun convertOne(item: ConversionItem, outDir: File, context: Context) {
        if (cancelled) {
            item.status = ConversionItem.Status.SKIPPED
            item.message = "已取消"
            registerFinished(context)
            return
        }
        item.status = ConversionItem.Status.CONVERTING
        item.message = "转换中"
        broadcastProgress(context)
        try {
            NcmConverter.convert(
                inputFile = item.file,
                outputDir = outDir,
                listener = { done, _ ->
                    val delta = done - item.progress
                    if (delta > 0) completedBytes.addAndGet(delta)
                    item.progress = done
                    broadcastProgress(context)
                },
                isCancelled = { cancelled }
            )
            val delta = item.size - item.progress
            if (delta > 0) completedBytes.addAndGet(delta)
            item.progress = item.size
            item.status = ConversionItem.Status.DONE
            item.message = "已完成"
            successCounter.incrementAndGet()
        } catch (e: NotNcmException) {
            item.status = ConversionItem.Status.FAILED
            item.message = "跳过：非 NCM 文件"
            failedCounter.incrementAndGet()
        } catch (e: ConversionCancelledException) {
            item.status = ConversionItem.Status.SKIPPED
            item.message = "已取消"
        } catch (e: Exception) {
            item.status = ConversionItem.Status.FAILED
            item.message = e.message ?: "转换失败"
            Log.e(TAG, "转换失败: ${item.file.name}", e)
            failedCounter.incrementAndGet()
        }
        registerFinished(context)
        broadcastProgress(context)
    }

    @Synchronized
    private fun registerFinished(context: Context) {
        if (!running || items.isEmpty()) return
        val done = items.count { it.status != ConversionItem.Status.PENDING && it.status != ConversionItem.Status.CONVERTING }
        if (done >= items.size) {
            markDone(context)
        }
    }

    private fun markDone(context: Context) {
        running = false
        executor?.shutdown()
        executor = null
        val intent = Intent(ACTION_FINISHED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_SUCCESS, successCounter.get())
            .putExtra(EXTRA_FAILED, failedCounter.get())
            .putExtra(EXTRA_OUTPUT, outputDir)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    private fun broadcastProgress(context: Context) {
        val intent = Intent(ACTION_PROGRESS).setPackage(context.packageName)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}
