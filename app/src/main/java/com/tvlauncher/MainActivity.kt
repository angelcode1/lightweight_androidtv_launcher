package com.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var repository: AppRepository
    private lateinit var appScroller: HorizontalScrollView
    private lateinit var appRow: LinearLayout
    private lateinit var wallpaperImage: ImageView
    private lateinit var wallpaperDim: View
    private lateinit var wallpaperCaption: TextView

    private var lastFocusedPosition = 0
    private var isHomeVisible = false
    private var wallpaperReceiverRegistered = false
    private var visibleEntries: List<AppEntry> = emptyList()

    @Volatile
    private var homeLoadGeneration = 0

    @Volatile
    private var wallpaperLoadGeneration = 0

    private data class HomeTile(
        val entry: AppEntry,
        val icon: Drawable
    )

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action == ControlReceiver.ACTION_WALLPAPER_CHANGED &&
                isHomeVisible
            ) {
                scheduleWallpaperLoad()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AppRepository(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        isHomeVisible = true
        registerWallpaperReceiver()
    }

    override fun onResume() {
        super.onResume()
        scheduleHomeRefresh()
        scheduleWallpaperLoad()
        refreshWallpaperIfNeeded(force = false)
    }

    override fun onStop() {
        isHomeVisible = false
        homeLoadGeneration++
        wallpaperLoadGeneration++
        unregisterWallpaperReceiver()
        releaseWallpaperBitmap()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterWallpaperReceiver()
        repository.clearIconCache()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            releaseWallpaperBitmap()
        }
        if (
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            repository.clearIconCache()
        }
        super.onTrimMemory(level)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (
            keyCode == KeyEvent.KEYCODE_BACK &&
            intent?.hasCategory(Intent.CATEGORY_HOME) == true
        ) {
            appRow.getChildAt(0)?.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun registerWallpaperReceiver() {
        if (wallpaperReceiverRegistered) return
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
        wallpaperReceiverRegistered = true
    }

    private fun unregisterWallpaperReceiver() {
        if (!wallpaperReceiverRegistered) return
        try {
            unregisterReceiver(wallpaperChangedReceiver)
        } catch (_: Exception) {
        } finally {
            wallpaperReceiverRegistered = false
        }
    }

    private fun releaseWallpaperBitmap() {
        wallpaperImage.setImageDrawable(null)
        wallpaperImage.visibility = View.GONE
        wallpaperDim.visibility = View.GONE
        if (::wallpaperCaption.isInitialized) {
            wallpaperCaption.text = ""
            wallpaperCaption.visibility = View.GONE
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

        val homeScrim = View(this).apply {
            setBackgroundResource(R.drawable.home_scrim)
        }
        root.addView(
            homeScrim,
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

        wallpaperCaption = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0x66000000)
            visibility = View.GONE
        }
        root.addView(
            wallpaperCaption,
            FrameLayout.LayoutParams(
                dp(520),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM
            ).apply {
                leftMargin = dp(28)
                bottomMargin = dp(172)
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(48), dp(36), dp(64), dp(28))
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        content.addView(buildHeader())

        content.addView(
            View(this),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(18), dp(8))
        }

        appScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            addView(
                appRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        content.addView(
            appScroller,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(132)
            )
        )

        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val clockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        clockContainer.addView(
            TextClock(this).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                setTextColor(Color.WHITE)
                textSize = 24f
            }
        )

        clockContainer.addView(
            TextClock(this).apply {
                format12Hour = "EEEE, MMMM d"
                format24Hour = "EEEE, d MMMM"
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 12f
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
            alpha = 0.78f
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnFocusChangeListener { view, focused ->
                view.alpha = if (focused) 1f else 0.78f
                val scale = if (focused) 1.03f else 1f
                view.scaleX = scale
                view.scaleY = scale
                view.elevation = if (focused) dp(3).toFloat() else 0f
            }
            setOnClickListener { showWallpaperSettings() }
        }
        header.addView(
            wallpaperButton,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(12)
            }
        )

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setBackgroundResource(R.drawable.header_button_background)
            contentDescription = getString(R.string.settings)
            isFocusable = true
            alpha = 0.78f
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnFocusChangeListener { view, focused ->
                view.alpha = if (focused) 1f else 0.78f
                val scale = if (focused) 1.03f else 1f
                view.scaleX = scale
                view.scaleY = scale
                view.elevation = if (focused) dp(3).toFloat() else 0f
            }
            setOnClickListener { showSystemSettingsMenu() }
        }
        header.addView(
            settingsButton,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(8)
            }
        )

        return header
    }

    private fun scheduleHomeRefresh() {
        val generation = ++homeLoadGeneration
        val iconSizePx = calculateIconSizePx()

        Thread {
            val entries = repository.getSelectedEntries()
            val tiles = entries.map { entry ->
                HomeTile(
                    entry = entry,
                    icon = repository.loadRoundedIcon(entry, iconSizePx)
                )
            }

            runOnUiThread {
                if (
                    !isHomeVisible ||
                    isFinishing ||
                    generation != homeLoadGeneration
                ) {
                    return@runOnUiThread
                }
                populateRow(tiles)
            }
        }.apply {
            name = "gazelle-home-load"
            isDaemon = true
        }.start()
    }

    private fun populateRow(tiles: List<HomeTile>) {
        appRow.removeAllViews()
        visibleEntries = tiles.map { it.entry }

        tiles.forEachIndexed { index, tile ->
            appRow.addView(
                createAppTile(tile.entry, tile.icon, index),
                tileLayoutParams()
            )
        }

        appRow.addView(createAddTile(tiles.size), tileLayoutParams())

        appRow.post {
            val target = lastFocusedPosition
                .coerceIn(0, (appRow.childCount - 1).coerceAtLeast(0))
            appRow.getChildAt(target)?.requestFocus()
        }
    }

    private fun createAppTile(
        entry: AppEntry,
        loadedIcon: Drawable,
        position: Int
    ): View {
        val tile = baseTile()

        val iconSizePx = calculateIconSizePx()
        val icon = ImageView(this).apply {
            setImageDrawable(loadedIcon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        tile.addView(icon, LinearLayout.LayoutParams(iconSizePx, iconSizePx))

        val label = TextView(this).apply {
            text = entry.label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            alpha = 0.92f
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            setPadding(dp(4), dp(5), dp(4), 0)
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
        val tile = baseTile(secondary = true)
        val plus = TextView(this).apply {
            text = "+"
            setTextColor(0xBFFFFFFF.toInt())
            textSize = 34f
            gravity = Gravity.CENTER
        }
        tile.addView(
            plus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        )

        val label = TextView(this).apply {
            text = getString(R.string.add_app)
            setTextColor(0xAFFFFFFF.toInt())
            textSize = 12.5f
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

    private fun baseTile(secondary: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            alpha = if (secondary) 0.72f else 1f
            setPadding(dp(6), dp(8), dp(6), dp(6))
            setBackgroundResource(R.drawable.app_slot_background)

            setOnFocusChangeListener { view, focused ->
                val scale = if (focused) 1.08f else 1f
                view.scaleX = scale
                view.scaleY = scale
                view.alpha = if (focused) 1f else if (secondary) 0.72f else 1f
                view.elevation = if (focused) dp(8).toFloat() else 0f

                if (focused && ::appScroller.isInitialized) {
                    appScroller.post {
                        appScroller.smoothScrollTo(
                            (view.left - dp(42)).coerceAtLeast(0),
                            0
                        )
                    }
                }
            }
        }
    }

    private fun calculateTileWidthPx(): Int {
        val usableWidth = resources.displayMetrics.widthPixels - dp(84)
        val target = (usableWidth / CAROUSEL_VISIBLE_ITEMS) - dp(12)
        return target.coerceIn(dp(96), dp(132))
    }

    private fun calculateIconSizePx(): Int {
        val contentWidth = calculateTileWidthPx() - dp(14)
        return minOf(dp(52), contentWidth.coerceAtLeast(dp(34)))
    }

    private fun tileLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            calculateTileWidthPx(),
            dp(108)
        ).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }
    }

    private fun showAppMenu(entry: AppEntry, position: Int) {
        val entriesSnapshot = visibleEntries
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(getString(R.string.app_menu_open))
        actions.add { repository.launch(entry) }

        if (position > 0) {
            val leftId = entriesSnapshot.getOrNull(position - 1)?.id
            if (leftId != null) {
                labels.add(getString(R.string.app_menu_move_left))
                actions.add {
                    if (repository.swapSelected(entry.id, leftId)) {
                        lastFocusedPosition = position - 1
                        scheduleHomeRefresh()
                    }
                }
            }
        }

        if (position < entriesSnapshot.size - 1) {
            val rightId = entriesSnapshot.getOrNull(position + 1)?.id
            if (rightId != null) {
                labels.add(getString(R.string.app_menu_move_right))
                actions.add {
                    if (repository.swapSelected(entry.id, rightId)) {
                        lastFocusedPosition = position + 1
                        scheduleHomeRefresh()
                    }
                }
            }
        }

        labels.add(getString(R.string.app_menu_info))
        actions.add { openAppInfo(entry.packageName) }

        labels.add(getString(R.string.remove))
        actions.add {
            repository.removeSelected(entry.id)
            lastFocusedPosition = (position - 1).coerceAtLeast(0)
            scheduleHomeRefresh()
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

    private data class FireTvSettingsTarget(
        val action: String,
        val className: String
    )

    private fun showSystemSettingsMenu() {
        val labels = arrayOf(
            getString(R.string.settings_network),
            getString(R.string.settings_display_sounds),
            getString(R.string.settings_applications),
            getString(R.string.settings_controllers),
            getString(R.string.settings_preferences),
            getString(R.string.settings_device),
            getString(R.string.settings_accessibility)
        )

        val targets = arrayOf(
            FireTvSettingsTarget(
                action = Settings.ACTION_WIRELESS_SETTINGS,
                className = "com.amazon.tv.settings.v2.tv.network.NetworkActivity"
            ),
            FireTvSettingsTarget(
                action = "com.amazon.device.settings.action.DISPLAY_AND_SOUNDS",
                className = "com.amazon.tv.settings.v2.tv.display_sounds.DisplayAndSoundsActivity"
            ),
            FireTvSettingsTarget(
                action = Settings.ACTION_APPLICATION_SETTINGS,
                className = "com.amazon.tv.settings.v2.tv.applications.ApplicationsActivity"
            ),
            FireTvSettingsTarget(
                action = "com.amazon.device.settings.action.CONTROLLERS",
                className = "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"
            ),
            FireTvSettingsTarget(
                action = "com.amazon.device.settings.action.PREFERENCES",
                className = "com.amazon.tv.settings.v2.tv.preferences.PreferencesActivity"
            ),
            FireTvSettingsTarget(
                action = "com.amazon.device.settings.action.DEVICE",
                className = "com.amazon.tv.settings.v2.tv.device.DeviceActivity"
            ),
            FireTvSettingsTarget(
                action = "com.amazon.device.settings.action.ACCESSIBILITY",
                className = "com.amazon.tv.settings.v2.tv.accessibility.AccessibilityActivity"
            )
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(labels) { _, which ->
                targets.getOrNull(which)?.let(::openFireTvSettingsPage)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openFireTvSettingsPage(target: FireTvSettingsTarget) {
        try {
            startActivity(
                Intent(target.action).apply {
                    component = ComponentName(
                        FIRE_TV_SETTINGS_PACKAGE,
                        target.className
                    )
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } catch (e: SecurityException) {
            Log.e(
                "GazelleLauncher",
                "Amazon Settings permission denied: action=${target.action} class=${target.className}",
                e
            )
            Toast.makeText(
                this,
                R.string.settings_open_failed,
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.w(
                "GazelleLauncher",
                "Amazon Settings launch failed: action=${target.action} class=${target.className}",
                e
            )
            openGenericSettingsFallback(target.action)
        }
    }

    private fun openGenericSettingsFallback(action: String) {
        val intents = listOf(
            Intent(action),
            Intent(Settings.ACTION_SETTINGS)
        )

        intents.forEach { intent ->
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }

        Toast.makeText(
            this,
            R.string.settings_open_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showWallpaperSettings() {
        val sourceKeys = arrayOf(
            NatureWallpaperManager.SOURCE_SOLID,
            NatureWallpaperManager.SOURCE_BING,
            NatureWallpaperManager.SOURCE_AMAZON,
            NatureWallpaperManager.SOURCE_NATURE,
            NatureWallpaperManager.SOURCE_CUSTOM
        )
        val sourceLabels = arrayOf(
            getString(R.string.wallpaper_source_solid),
            getString(R.string.wallpaper_source_bing),
            getString(R.string.wallpaper_source_amazon),
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
        var intervalIndex = GazelleLogic.intervalIndex(
            intervalValues,
            NatureWallpaperManager.getInterval(this),
            defaultIndex = 1
        )

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

        val caption = CheckBox(this).apply {
            text = getString(R.string.wallpaper_caption)
            isChecked = NatureWallpaperManager.isCaptionEnabled(this@MainActivity)
        }
        panel.addView(caption)

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
                NatureWallpaperManager.setCaptionEnabled(this, caption.isChecked)

                scheduleWallpaperLoad()
                refreshWallpaperIfNeeded(force = true)
            }
            .setNeutralButton(R.string.wallpaper_refresh_now) { _, _ ->
                NatureWallpaperManager.setSource(this, sourceKeys[sourceIndex])
                NatureWallpaperManager.setInterval(this, intervalValues[intervalIndex])
                NatureWallpaperManager.setCustomUrl(this, customUrl.text.toString())
                NatureWallpaperManager.setDimEnabled(this, dim.isChecked)
                NatureWallpaperManager.setCaptionEnabled(this, caption.isChecked)
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
        ) { result ->
            if (!isHomeVisible || isFinishing) return@refreshAsync
            when (result) {
                NatureWallpaperManager.RefreshResult.SUCCESS ->
                    scheduleWallpaperLoad()
                NatureWallpaperManager.RefreshResult.BUSY,
                NatureWallpaperManager.RefreshResult.STALE -> Unit
                NatureWallpaperManager.RefreshResult.FAILED -> {
                    if (force) {
                        Toast.makeText(
                            this,
                            R.string.wallpaper_refresh_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun scheduleWallpaperLoad() {
        val generation = ++wallpaperLoadGeneration
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        Thread {
            val source = NatureWallpaperManager.getSource(applicationContext)
            val drawable = if (source == NatureWallpaperManager.SOURCE_SOLID) {
                null
            } else {
                NatureWallpaperManager.loadCachedDrawable(
                    applicationContext,
                    width,
                    height
                )
            }
            val dimEnabled = NatureWallpaperManager.isDimEnabled(applicationContext)
            val captionEnabled =
                NatureWallpaperManager.isCaptionEnabled(applicationContext)
            val caption = NatureWallpaperManager.getCachedCaption(applicationContext)

            runOnUiThread {
                if (
                    !isHomeVisible ||
                    isFinishing ||
                    generation != wallpaperLoadGeneration
                ) {
                    return@runOnUiThread
                }
                applyLoadedWallpaper(
                    drawable = drawable,
                    dimEnabled = dimEnabled,
                    captionEnabled = captionEnabled,
                    caption = caption
                )
            }
        }.apply {
            name = "gazelle-wallpaper-decode"
            isDaemon = true
        }.start()
    }

    private fun applyLoadedWallpaper(
        drawable: Drawable?,
        dimEnabled: Boolean,
        captionEnabled: Boolean,
        caption: String
    ) {
        if (drawable == null) {
            releaseWallpaperBitmap()
            return
        }

        wallpaperImage.setImageDrawable(drawable)
        wallpaperImage.visibility = View.VISIBLE
        wallpaperDim.visibility =
            if (dimEnabled) View.VISIBLE else View.GONE

        if (captionEnabled && caption.isNotBlank()) {
            wallpaperCaption.text = caption
            wallpaperCaption.visibility = View.VISIBLE
            wallpaperCaption.postDelayed({
                if (wallpaperCaption.text == caption) {
                    wallpaperCaption.visibility = View.GONE
                }
            }, 8000L)
        } else {
            wallpaperCaption.text = ""
            wallpaperCaption.visibility = View.GONE
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val CAROUSEL_VISIBLE_ITEMS = 7
        private const val FIRE_TV_SETTINGS_PACKAGE = "com.amazon.tv.settings.v2"
    }
}
