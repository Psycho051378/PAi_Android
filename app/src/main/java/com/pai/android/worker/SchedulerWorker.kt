package com.pai.android.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.pai.android.agent.ScheduledTask
import com.pai.android.data.repository.MemoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@HiltWorker
class SchedulerWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val memoryRepository: MemoryRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val dayOfWeek = SimpleDateFormat("E", Locale.US).format(Date())
            val dayOfMonth = SimpleDateFormat("d", Locale.getDefault()).format(Date()).toIntOrNull() ?: 1

            // Load tasks from permanent memory
            val fact = memoryRepository.getFactByCategoryAndKey("scheduler", "tasks") ?: return Result.success()
            val json = JSONObject(fact.value)
            val tasksArray = json.getJSONArray("tasks")
            
            for (i in 0 until tasksArray.length()) {
                val obj = tasksArray.getJSONObject(i)
                val enabled = obj.optBoolean("enabled", true)
                if (!enabled) continue
                
                val cron = obj.getString("cron")
                val name = obj.getString("name")
                val prompt = obj.getString("prompt")
                val id = obj.getString("id")
                
                // Check if task should run
                val shouldRun = cron.split(",").any { cronPart ->
                    val parts = cronPart.trim().split(" ").filter { it.isNotBlank() }
                    when (parts.size) {
                        1 -> parts[0] == now
                        2 -> {
                            val time = parts[1]
                            if (time != now) false
                            else {
                                val first = parts[0]
                                when {
                                    first.contains("-") -> first == today
                                    first.length == 3 && first[0].isUpperCase() -> first == dayOfWeek
                                    else -> first.toIntOrNull() == dayOfMonth
                                }
                            }
                        }
                        else -> false
                    }
                }
                
                if (shouldRun) {
                    showNotification(name, prompt)
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(title: String, text: String) {
        val channelId = "scheduler_tasks"
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Scheduled Tasks",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications from scheduled tasks" }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify(title.hashCode(), notification)
    }

    companion object {
        private const val WORK_NAME = "scheduler_background_check"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<SchedulerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
