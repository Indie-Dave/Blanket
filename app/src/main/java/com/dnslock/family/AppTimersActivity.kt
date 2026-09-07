package com.dnslock.family

import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
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
        DeviceAuth.hideUntilUnlocked(this)
    }

    override fun onResume() {
        super.onResume()
        DeviceAuth.requireUnlock(this) {
            refreshUsageAccessStatus()
            showCachedAppsImmediately()
            loadAppsAsync()
        }
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
        loadingBar.visibility = View.VISIBLE

        bgExecutor.execute {
            val appContext = applicationContext
            // Always refresh so uninstall/reinstall while Blanket stays alive
            // cannot leave a stale launcher list (missing timed apps).
            val cached = InstalledAppsCache.refresh(appContext)
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
        val windowsLabel = entry.blockWindows
            .takeIf { it.isNotEmpty() }
            ?.let { AppTimersManager.formatWindows(this, it) }

        if (entry.isBlockedNow && windowsLabel != null) {
            return getString(R.string.app_timer_blocked_now, windowsLabel, usedLabel)
        }

        return when {
            entry.limitMinutes <= 0 && windowsLabel != null -> getString(
                R.string.app_timer_blocks_at,
                windowsLabel,
                usedLabel
            )
            entry.limitMinutes <= 0 -> getString(R.string.app_timer_no_limit, usedLabel)
            entry.isExceeded && windowsLabel != null -> getString(
                R.string.app_timer_exceeded_with_blocks,
                AppTimersManager.formatDuration(entry.limitMinutes),
                usedLabel,
                windowsLabel
            )
            entry.isExceeded -> getString(
                R.string.app_timer_exceeded,
                AppTimersManager.formatDuration(entry.limitMinutes),
                usedLabel
            )
            windowsLabel != null -> getString(
                R.string.app_timer_remaining_with_blocks,
                AppTimersManager.formatRemaining(entry.remainingMs),
                AppTimersManager.formatDuration(entry.limitMinutes),
                usedLabel,
                windowsLabel
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
        val windowsContainer = view.findViewById<LinearLayout>(R.id.blockWindowsContainer)
        val addWindowButton = view.findViewById<Button>(R.id.addBlockWindowButton)

        hoursPicker.minValue = 0
        hoursPicker.maxValue = 23
        minutesPicker.minValue = 0
        minutesPicker.maxValue = 59

        val current = entry.limitMinutes.coerceAtLeast(0)
        hoursPicker.value = current / 60
        minutesPicker.value = current % 60

        val windows = entry.blockWindows.toMutableList()
        bindBlockWindows(windowsContainer, windows)

        addWindowButton.setOnClickListener {
            if (windows.size >= MAX_BLOCK_WINDOWS) {
                Toast.makeText(this, R.string.app_timer_block_window_limit, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            windows.add(defaultBlockWindow())
            bindBlockWindows(windowsContainer, windows)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_app_timer_for, entry.label))
            .setView(view)
            .setPositiveButton(R.string.password_confirm) { _, _ ->
                val total = hoursPicker.value * 60 + minutesPicker.value
                val invalidCount = windows.count { it.startMinutes == it.endMinutes }
                val cleaned = windows.filter { it.startMinutes != it.endMinutes }
                if (invalidCount > 0 && cleaned.isEmpty() && windows.isNotEmpty()) {
                    Toast.makeText(this, R.string.app_timer_block_window_same_time, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (total <= 0) {
                    AppTimersManager.removeLimit(this, entry.packageName)
                } else {
                    AppTimersManager.setLimitMinutes(this, entry.packageName, total)
                }
                AppTimersManager.setBlockWindows(this, entry.packageName, cleaned)

                val toast = when {
                    total <= 0 && cleaned.isEmpty() -> getString(R.string.app_timer_removed)
                    total <= 0 -> getString(
                        R.string.app_timer_schedule_saved,
                        entry.label,
                        AppTimersManager.formatWindows(this, cleaned)
                    )
                    cleaned.isEmpty() -> getString(
                        R.string.app_timer_saved,
                        entry.label,
                        AppTimersManager.formatDuration(total)
                    )
                    else -> getString(
                        R.string.app_timer_saved_with_schedule,
                        entry.label,
                        AppTimersManager.formatDuration(total),
                        AppTimersManager.formatWindows(this, cleaned)
                    )
                }
                Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
                rebuildFromCache()
            }
            .setNeutralButton(R.string.remove_app_timer) { _, _ ->
                AppTimersManager.removeLimit(this, entry.packageName)
                AppTimersManager.setBlockWindows(this, entry.packageName, emptyList())
                Toast.makeText(this, R.string.app_timer_removed, Toast.LENGTH_SHORT).show()
                rebuildFromCache()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindBlockWindows(
        container: LinearLayout,
        windows: MutableList<AppTimersManager.BlockWindow>
    ) {
        container.removeAllViews()
        windows.forEachIndexed { index, window ->
            val row = layoutInflater.inflate(R.layout.item_app_block_window, container, false)
            val startButton = row.findViewById<Button>(R.id.blockWindowStartButton)
            val endButton = row.findViewById<Button>(R.id.blockWindowEndButton)
            val removeButton = row.findViewById<Button>(R.id.blockWindowRemoveButton)

            startButton.text = AppTimersManager.formatClock(this, window.startMinutes)
            endButton.text = AppTimersManager.formatClock(this, window.endMinutes)

            startButton.setOnClickListener {
                pickTime(windows[index].startMinutes) { minutes ->
                    windows[index] = windows[index].copy(startMinutes = minutes)
                    bindBlockWindows(container, windows)
                }
            }
            endButton.setOnClickListener {
                pickTime(windows[index].endMinutes) { minutes ->
                    windows[index] = windows[index].copy(endMinutes = minutes)
                    bindBlockWindows(container, windows)
                }
            }
            removeButton.setOnClickListener {
                windows.removeAt(index)
                bindBlockWindows(container, windows)
            }
            container.addView(row)
        }
    }

    private fun pickTime(currentMinutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hourOfDay, minute -> onPicked(hourOfDay * 60 + minute) },
            currentMinutes / 60,
            currentMinutes % 60,
            DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun defaultBlockWindow(): AppTimersManager.BlockWindow {
        val cal = Calendar.getInstance()
        val start = cal.get(Calendar.HOUR_OF_DAY) * 60
        val end = (start + 60) % (24 * 60)
        return AppTimersManager.BlockWindow(start, end)
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
            holder.setButton.text = if (entry.hasRestrictions) {
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

    companion object {
        private const val MAX_BLOCK_WINDOWS = 12
    }
}
