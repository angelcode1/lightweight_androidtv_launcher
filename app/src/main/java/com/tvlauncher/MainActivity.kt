package com.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var repository: AppRepository
    private lateinit var appGrid: GridLayout
    private lateinit var wallpaperImage: ImageView
    private lateinit var wallpaperDim: View

    private var lastFocusedPosition = 0

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ControlReceiver.ACTION_WALLPAPER_CHANGED) {
                applyWallpaperFromCache()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AppRepository(this)
        setContentView(buildUi())
        registerWallpaperReceiver()
    }

    override fun onResume() {
        super.onResume()
        populateGrid()
        applyWallpaperFromCache()
        refreshWallpaperIfNeeded(force = false)
    }

    override fun onStop() {
        // Release icon and wallpaper bitmaps while another app is in the foreground.
        appGrid.removeAllViews()
        wallpaperImage.setImageDrawable(null)
        wallpaperImage.visibility = View.GONE
        wallpaperDim.visibility = View.GONE
        repository.clearIconCache()
        super.onStop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(wallpaperChangedReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        repository.clearIconCache()
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            wallpaperImage.setImageDrawable(null)
        }
        super.onTrimMemory(level)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            appGrid.getChildAt(0)?.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun registerWallpaperReceiver() {
        val filter = IntentFilter(ControlReceiver.ACTION_WALLPAPER_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                wallpaperChangedReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wallpaperChangedReceiver, filter)
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(14, 14, 18))
        }

        wallpaperImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(
            wallpaperImage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        wallpaperDim = View(this).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        root.addView(
            wallpaperDim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), dp(22), dp(34), dp(22))
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        content.addView(buildHeader())

        appGrid = GridLayout(this).apply {
            columnCount = COLUMNS
            rowCount = ROWS
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(
            appGrid,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        clockContainer.addView(
            TextClock(this).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                setTextColor(Color.WHITE)
                textSize = 26f
            }
        )

        clockContainer.addView(
            TextClock(this).apply {
                format12Hour = "EEEE, MMMM d"
                format24Hour = "EEEE, d MMMM"
                setTextColor(Color.LTGRAY)
                textSize = 13f
            }
        )

        header.addView(
            clockContainer,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val wallpaperButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_wallpaper)
            setBackgroundResource(R.drawable.header_button_background)
            contentDescription = getString(R.string.wallpaper_settings)
            isFocusable = true
            setOnClickListener { showWallpaperSettings() }
        }
        header.addView(
            wallpaperButton,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginEnd = dp(12)
            }
        )

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setBackgroundResource(R.drawable.header_button_background)
            contentDescription = getString(R.string.settings)
            isFocusable = true
            setOnClickListener { openSystemSettings() }
        }
        header.addView(
            settingsButton,
            LinearLayout.LayoutParams(dp(46), dp(46))
        )

        return header
    }

    private fun populateGrid() {
        appGrid.removeAllViews()
        val entries = repository.getSelectedEntries()

        entries.forEachIndexed { index, entry ->
            appGrid.addView(createAppTile(entry, index), tileLayoutParams())
        }

        appGrid.addView(createAddTile(entries.size), tileLayoutParams())

        appGrid.post {
            val target = lastFocusedPosition
                .coerceIn(0, (appGrid.childCount - 1).coerceAtLeast(0))
            appGrid.getChildAt(target)?.requestFocus()
        }
    }

    private fun createAppTile(entry: AppEntry, position: Int): View {
        val tile = baseTile()

        val icon = ImageView(this).apply {
            setImageDrawable(repository.loadRoundedIcon(entry, dp(68)))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(68), dp(68)))

        val label = TextView(this).apply {
            text = entry.label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(6), dp(7), dp(6), 0)
        }
        tile.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        tile.setOnClickListener {
            lastFocusedPosition = position
            if (!repository.launch(entry)) {
                Toast.makeText(this, R.string.app_launch_failed, Toast.LENGTH_SHORT).show()
            }
        }

        tile.setOnLongClickListener {
            lastFocusedPosition = position
            showAppMenu(entry, position)
            true
        }

        return tile
    }

    private fun createAddTile(position: Int): View {
        val tile = baseTile()
        val plus = TextView(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            textSize = 46f
            gravity = Gravity.CENTER
        }
        tile.addView(
            plus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60)
            )
        )

        val label = TextView(this).apply {
            text = getString(R.string.add_app)
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        tile.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        tile.setOnClickListener {
            lastFocusedPosition = position
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }
        return tile
    }

    private fun baseTile(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(10), dp(8), dp(8))
            setBackgroundResource(R.drawable.app_slot_background)

            setOnFocusChangeListener { view, focused ->
                val scale = if (focused) 1.05f else 1.0f
                view.scaleX = scale
                view.scaleY = scale
                view.elevation = if (focused) dp(8).toFloat() else 0f
            }
        }
    }

    private fun tileLayoutParams(): GridLayout.LayoutParams {
        val usableWidth = resources.displayMetrics.widthPixels - dp(68)
        val tileWidth = (usableWidth / COLUMNS) - dp(12)
        val usableHeight = resources.displayMetrics.heightPixels - dp(128)
        val tileHeight = (usableHeight / ROWS) - dp(12)

        return GridLayout.LayoutParams().apply {
            width = tileWidth.coerceAtLeast(dp(145))
            height = tileHeight.coerceIn(dp(118), dp(180))
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }
    }

    private fun showAppMenu(entry: AppEntry, position: Int) {
        val selectedCount = repository.getSelectedIds().size
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(getString(R.string.app_menu_open))
        actions.add { repository.launch(entry) }

        if (position > 0) {
            labels.add(getString(R.string.app_menu_move_left))
            actions.add {
                if (repository.moveSelected(position, position - 1)) {
                    lastFocusedPosition = position - 1
                    populateGrid()
                }
            }
        }

        if (position < selectedCount - 1) {
            labels.add(getString(R.string.app_menu_move_right))
            actions.add {
                if (repository.moveSelected(position, position + 1)) {
                    lastFocusedPosition = position + 1
                    populateGrid()
                }
            }
        }

        labels.add(getString(R.string.app_menu_info))
        actions.add { openAppInfo(entry.packageName) }

        labels.add(getString(R.string.remove))
        actions.add {
            repository.removeSelected(entry.id)
            lastFocusedPosition = (position - 1).coerceAtLeast(0)
            populateGrid()
        }

        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppInfo(packageName: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun openSystemSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent("android.settings.TV_SETTINGS"),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        )

        intents.forEach { intent ->
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun showWallpaperSettings() {
        val sourceKeys = arrayOf(
            NatureWallpaperManager.SOURCE_SOLID,
            NatureWallpaperManager.SOURCE_BING,
            NatureWallpaperManager.SOURCE_NATURE,
            NatureWallpaperManager.SOURCE_CUSTOM
        )
        val sourceLabels = arrayOf(
            getString(R.string.wallpaper_source_solid),
            getString(R.string.wallpaper_source_bing),
            getString(R.string.wallpaper_source_nature),
            getString(R.string.wallpaper_source_custom)
        )

        val intervalValues = longArrayOf(
            NatureWallpaperManager.INTERVAL_1H,
            NatureWallpaperManager.INTERVAL_6H,
            NatureWallpaperManager.INTERVAL_12H,
            NatureWallpaperManager.INTERVAL_24H
        )
        val intervalLabels = arrayOf(
            getString(R.string.wallpaper_interval_1h),
            getString(R.string.wallpaper_interval_6h),
            getString(R.string.wallpaper_interval_12h),
            getString(R.string.wallpaper_interval_24h)
        )

        var sourceIndex = sourceKeys.indexOf(
            NatureWallpaperManager.getSource(this)
        ).coerceAtLeast(0)
        var intervalIndex = intervalValues.indexOf(
            NatureWallpaperManager.getInterval(this)
        ).coerceAtLeast(1)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val sourceButton = Button(this).apply {
            text = sourceLabels[sourceIndex]
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.wallpaper_source)
                    .setSingleChoiceItems(sourceLabels, sourceIndex) { dialog, which ->
                        sourceIndex = which
                        text = sourceLabels[which]
                        dialog.dismiss()
                    }
                    .show()
            }
        }
        panel.addView(sourceButton)

        val intervalButton = Button(this).apply {
            text = intervalLabels[intervalIndex]
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.wallpaper_interval)
                    .setSingleChoiceItems(intervalLabels, intervalIndex) { dialog, which ->
                        intervalIndex = which
                        text = intervalLabels[which]
                        dialog.dismiss()
                    }
                    .show()
            }
        }
        panel.addView(intervalButton)

        val customUrl = EditText(this).apply {
            hint = getString(R.string.wallpaper_custom_url_hint)
            setSingleLine(true)
            setText(NatureWallpaperManager.getCustomUrl(this@MainActivity))
        }
        panel.addView(customUrl)

        val dim = CheckBox(this).apply {
            text = getString(R.string.wallpaper_dim)
            isChecked = NatureWallpaperManager.isDimEnabled(this@MainActivity)
        }
        panel.addView(dim)

        panel.addView(
            TextView(this).apply {
                text = getString(R.string.wallpaper_note)
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            }
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_settings)
            .setView(panel)
            .setPositiveButton(R.string.done) { _, _ ->
                NatureWallpaperManager.setSource(this, sourceKeys[sourceIndex])
                NatureWallpaperManager.setInterval(this, intervalValues[intervalIndex])
                NatureWallpaperManager.setCustomUrl(this, customUrl.text.toString())
                NatureWallpaperManager.setDimEnabled(this, dim.isChecked)

                applyWallpaperFromCache()
                refreshWallpaperIfNeeded(force = true)
            }
            .setNeutralButton(R.string.wallpaper_refresh_now) { _, _ ->
                NatureWallpaperManager.setSource(this, sourceKeys[sourceIndex])
                NatureWallpaperManager.setInterval(this, intervalValues[intervalIndex])
                NatureWallpaperManager.setCustomUrl(this, customUrl.text.toString())
                NatureWallpaperManager.setDimEnabled(this, dim.isChecked)
                refreshWallpaperIfNeeded(force = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshWallpaperIfNeeded(force: Boolean) {
        if (
            !force &&
            !NatureWallpaperManager.shouldRefresh(this)
        ) {
            return
        }

        NatureWallpaperManager.refreshAsync(
            applicationContext,
            force
        ) { success ->
            if (isFinishing) return@refreshAsync
            if (success) {
                applyWallpaperFromCache()
            } else if (force) {
                Toast.makeText(
                    this,
                    R.string.wallpaper_refresh_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun applyWallpaperFromCache() {
        if (NatureWallpaperManager.getSource(this) == NatureWallpaperManager.SOURCE_SOLID) {
            wallpaperImage.setImageDrawable(null)
            wallpaperImage.visibility = View.GONE
            wallpaperDim.visibility = View.GONE
            return
        }

        val metrics = resources.displayMetrics
        val drawable = NatureWallpaperManager.loadCachedDrawable(
            this,
            metrics.widthPixels,
            metrics.heightPixels
        )

        if (drawable == null) {
            wallpaperImage.setImageDrawable(null)
            wallpaperImage.visibility = View.GONE
            wallpaperDim.visibility = View.GONE
            return
        }

        wallpaperImage.setImageDrawable(drawable)
        wallpaperImage.visibility = View.VISIBLE
        wallpaperDim.visibility =
            if (NatureWallpaperManager.isDimEnabled(this)) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val COLUMNS = 6
        private const val ROWS = 3
    }
}
