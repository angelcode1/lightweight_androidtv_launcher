package com.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.LinkedHashSet
import java.util.Locale

class AppSelectionActivity : Activity() {
    private lateinit var repository: AppRepository
    private lateinit var listView: ListView
    private lateinit var badge: TextView
    private lateinit var search: EditText
    private lateinit var adapter: EntryAdapter

    private val selected = LinkedHashSet<String>()
    private var allEntries: List<AppEntry> = emptyList()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AppRepository(this)
        selected.addAll(repository.getSelectedIds())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(22), dp(30), dp(22))
            setBackgroundColor(Color.rgb(14, 14, 18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = getString(R.string.select_apps)
            setTextColor(Color.WHITE)
            textSize = 26f
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        badge = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setPadding(dp(12), 0, dp(16), 0)
        }
        header.addView(badge)

        val done = Button(this).apply {
            text = getString(R.string.done)
            isFocusable = true
            setOnClickListener {
                repository.saveSelectedIds(selected)
                finish()
            }
        }
        header.addView(done)

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(
            search,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        )

        listView = ListView(this).apply {
            dividerHeight = 0
            isFocusable = true
        }
        adapter = EntryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position)
            if (selected.contains(entry.id)) {
                selected.remove(entry.id)
            } else {
                if (selected.size >= AppRepository.MAX_APPS) {
                    Toast.makeText(
                        this,
                        getString(R.string.slot_limit_reached, AppRepository.MAX_APPS),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnItemClickListener
                }
                selected.add(entry.id)
            }
            updateBadge()
            adapter.notifyDataSetChanged()
        }

        root.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        updateBadge()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                query = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                applyFilter()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        Thread {
            val loaded = repository.queryLaunchableApps()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                allEntries = loaded
                applyFilter()
                listView.requestFocus()
            }
        }.start()
    }

    private fun updateBadge() {
        badge.text = getString(
            R.string.selected_badge,
            selected.size,
            AppRepository.MAX_APPS
        )
    }

    private fun applyFilter() {
        val filtered = if (query.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry ->
                entry.label.lowercase(Locale.ROOT).contains(query) ||
                    entry.packageName.lowercase(Locale.ROOT).contains(query) ||
                    entry.activityName.lowercase(Locale.ROOT).contains(query)
            }
        }
        adapter.items = filtered
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        repository.clearIconCache()
        super.onDestroy()
    }

    private inner class EntryAdapter : BaseAdapter() {
        var items: List<AppEntry> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppEntry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val icon: ImageView
            val labels: TextView
            val mark: TextView

            if (convertView is LinearLayout && convertView.tag is RowHolder) {
                row = convertView
                val holder = row.tag as RowHolder
                icon = holder.icon
                labels = holder.labels
                mark = holder.mark
            } else {
                row = LinearLayout(this@AppSelectionActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    minimumHeight = dp(72)
                }

                icon = ImageView(this@AppSelectionActivity)
                row.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))

                labels = TextView(this@AppSelectionActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    maxLines = 2
                    setPadding(dp(14), 0, dp(10), 0)
                }
                row.addView(
                    labels,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )

                mark = TextView(this@AppSelectionActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    gravity = Gravity.CENTER
                }
                row.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.tag = RowHolder(icon, labels, mark)
            }

            val entry = getItem(position)
            icon.setImageDrawable(repository.loadRoundedIcon(entry, dp(52)))
            val kind = if (entry.isTvApp) "TV" else "Mobile"
            labels.text = entry.label + "\n" + kind + " · " + entry.packageName
            mark.text = if (selected.contains(entry.id)) "✓" else ""

            return row
        }
    }

    private data class RowHolder(
        val icon: ImageView,
        val labels: TextView,
        val mark: TextView
    )

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
