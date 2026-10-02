package com.lonelytragedy.r1999trackerapp

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class BannerSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!JavaScriptSandbox.isSupported()) return Result.success()
        val source = withContext(Dispatchers.IO) { download() } ?: return Result.retry()
        return try {
            val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(applicationContext).await()
            try {
                val isolate = sandbox.createIsolate()
                try {
                    val raw = isolate.evaluateJavaScriptAsync(source + "\n;" + BannerScripts.EXTRACT).await()
                    BannerScheduler.update(applicationContext, raw)
                } finally {
                    isolate.close()
                }
            } finally {
                sandbox.close()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun download(): String? {
        val conn = URL(DATABASE_URL).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val DATABASE_URL = "https://lonelytragedy.github.io/r1999-tracker/database.js"
        private const val NAME = "banner_sync"

        fun enqueue(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<BannerSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
