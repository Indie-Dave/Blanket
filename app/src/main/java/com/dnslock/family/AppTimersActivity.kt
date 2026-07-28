package com.dnslock.family

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class AppTimersActivity : AppCompatActivity() {

    private lateinit var usageAccessStatusText: TextView
    private lateinit var openUsageAccessButton: Button
    private lateinit var searchInput: EditText
    private lateinit var loadingBar: ProgressBar
    private lateinit var listView: ListView
    private lateinit var adapter: AppTimerAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var allApps: List<AppTimersManager.AppTimerEntry> = emptyList()
    private var usageMap: Map<String, Long> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_timers)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.app_timers_title)

        usageAccessStatusText = findViewById(R.id.usageAccessStatusText)
        openUsageAccessButton = findViewById(R.id.openUsageAccessButton)
        searchInput = findViewById(R.id.appTimerSearchInput)
        loadingBar = findViewById(R.id.appTimersLoading)
        listView = findViewById(R.id.appTimersList)

        adapter = AppTimerAdapter()
        listView.adapter = adapter

        openUsageAccessButton.setOnClickListener {
            UsageStatsHelper.openUsageAccessSettings(this)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })

        InstalledAppsCache.preload(this)
        showCachedAppsImmediately()
    }

    override fun onResume() {
        super.onResume()
        refreshUsageAccessStatus()
        loadAppsAsync()
    }

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshUsageAccessStatus() {
        val hasAccess = UsageStatsHelper.hasUsageAccess(this)
        usageAccessStatusText.text = if (hasAccess) {
            getString(R.string.usage_access_status_enabled)
        } else {
            getString(R.string.usage_access_status_disabled)
        }
        openUsageAccessButton.visibility = if (hasAccess) View.GONE else View.VISIBLE
    }

    private fun showCachedAppsImmediately() {
        val cached = InstalledAppsCache.getAppsIfReady() ?: return
        allApps = AppTimersManager.buildEntries(this, cached, usageMap)
        applyFilter()
    }

    private fun loadAppsAsync() {
        val cacheReady = InstalledAppsCache.isReady()
        if (!cacheReady) {
            loadingBar.visibility = View.VISIBLE
        }

        bgExecutor.execute {
            val appContext = applicationContext
            val cached = InstalledAppsCache.getApps(appContext)
            val usage = UsageStatsHelper.getTodayUsageMap(appContext, forceRefresh = true)
            val entries = AppTimersManager.buildEntries(appContext, cached, usage)

            mainHandler.post {
                if (isFinishing) return@post
                usageMap = usage
                allApps = entries
                loadingBar.visibility = View.GONE
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.submit(filtered)
    }

    private fun statusText(entry: AppTimersManager.AppTimerEntry): String {
        val usedLabel = AppTimersManager.formatDurationMs(entry.usedTodayMs)

        return when {
            entry.limitMinutes <= 0 -> getString(R.string.app_timer_no_limit, usedLabel)
            entry.isExceeded -> getString(
                R.string.app_timer_exceeded,
                AppTimersManager.formatDuration(entry.limitMinutes),
                usedLabel
            )
            else -> getString(
                R.string.app_timer_remaining,
                AppTimersManager.formatRemaining(entry.remainingMs),
                AppTimersManager.formatDuration(entry.limitMinutes),
                usedLabel
            )
        }
    }

    private fun showSetTimerDialog(entry: AppTimersManager.AppTimerEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_set_app_timer, null)
        val hoursPicker = view.findViewById<NumberPicker>(R.id.timerHoursPicker)
        val minutesPicker = view.findViewById<NumberPicker>(R.id.timerMinutesPicker)

        hoursPicker.minValue = 0
        hoursPicker.maxValue = 23
        minutesPicker.minValue = 0
        minutesPicker.maxValue = 59

        val current = entry.limitMinutes.coerceAtLeast(0)
        hoursPicker.value = current / 60
        minutesPicker.value = current % 60

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_app_timer_for, entry.label))
            .setView(view)
            .setPositiveButton(R.string.password_confirm) { _, _ ->
                val total = hoursPicker.value * 60 + minutesPicker.value
                if (total <= 0) {
                    AppTimersManager.removeLimit(this, entry.packageName)
                    Toast.makeText(this, R.string.app_timer_removed, Toast.LENGTH_SHORT).show()
                } else {
                    AppTimersManager.setLimitMinutes(this, entry.packageName, total)
                    Toast.makeText(
                        this,
                        getString(
                            R.string.app_timer_saved,
                            entry.label,
                            AppTimersManager.formatDuration(total)
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                rebuildFromCache()
            }
            .setNeutralButton(R.string.remove_app_timer) { _, _ ->
                AppTimersManager.removeLimit(this, entry.packageName)
                Toast.makeText(this, R.string.app_timer_removed, Toast.LENGTH_SHORT).show()
                rebuildFromCache()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rebuildFromCache() {
        val cached = InstalledAppsCache.getAppsIfReady() ?: return
        allApps = AppTimersManager.buildEntries(this, cached, usageMap)
        applyFilter()
    }

    private inner class AppTimerAdapter : BaseAdapter() {
        private var items: List<AppTimersManager.AppTimerEntry> = emptyList()

        fun submit(newItems: List<AppTimersManager.AppTimerEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppTimersManager.AppTimerEntry = items[position]
        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_timer, parent, false)
            val holder = (view.tag as? ViewHolder) ?: ViewHolder(view).also { view.tag = it }

            val entry = items[position]
            holder.icon.setImageDrawable(entry.icon)
            holder.label.text = entry.label
            holder.status.text = statusText(entry)
            holder.setButton.text = if (entry.limitMinutes > 0) {
                getString(R.string.edit_app_timer)
            } else {
                getString(R.string.set_app_timer)
            }
            holder.setButton.setOnClickListener { showSetTimerDialog(entry) }
            return view
        }
    }

    private class ViewHolder(view: View) {
        val icon: ImageView = view.findViewById(R.id.appTimerIcon)
        val label: TextView = view.findViewById(R.id.appTimerLabel)
        val status: TextView = view.findViewById(R.id.appTimerStatus)
        val setButton: Button = view.findViewById(R.id.setAppTimerButton)
    }
}
