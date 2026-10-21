package com.example.later

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var themeButton: MaterialButton
    private lateinit var statsText: TextView
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var permissionCard: MaterialCardView
    private lateinit var permissionButton: MaterialButton
    private lateinit var tabs: MaterialButtonToggleGroup
    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var digestButton: MaterialButton

    private lateinit var store: ShotStore
    private val adapter = ShotAdapter(onDone = ::toggleDone, onRetry = ::retry)
    private var askedOnce = false

    // Screenshots access (+ notifications for the digest on Android 13+), asked together once
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            askedOnce = true
            refresh()
            if (Screenshots.hasPermission(this)) scanNow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the saved theme before the screen is built (no light-then-dark flash)
        store = ShotStore(this)
        applyTheme(store.darkMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        themeButton = findViewById(R.id.themeButton)
        statsText = findViewById(R.id.statsText)
        scanProgress = findViewById(R.id.scanProgress)
        permissionCard = findViewById(R.id.permissionCard)
        permissionButton = findViewById(R.id.permissionButton)
        tabs = findViewById(R.id.tabs)
        list = findViewById(R.id.list)
        emptyText = findViewById(R.id.emptyText)
        scanButton = findViewById(R.id.scanButton)
        digestButton = findViewById(R.id.digestButton)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        showThemeIcon(store.darkMode)
        themeButton.setOnClickListener {
            store.darkMode = !store.darkMode
            applyTheme(store.darkMode)
        }
        tabs.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) refresh() }
        permissionButton.setOnClickListener { askPermissions() }
        scanButton.setOnClickListener { scanNow() }
        digestButton.setOnClickListener { previewDigest() }

        // Background scanning every ~15 min + the 8 PM digest, even with the app closed
        Scheduler.schedule(this)

        if (Screenshots.hasPermission(this)) scanNow() else askPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()   // the background worker may have read new screenshots meanwhile
    }

    // ---------- permissions ----------

    private fun askPermissions() {
        val wanted = mutableListOf(Screenshots.permission)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS

        val blocked = askedOnce && !shouldShowRequestPermissionRationale(Screenshots.permission)
        if (blocked && !Screenshots.hasPermission(this)) {
            // Android stops showing the dialog after two "Don't allow" taps
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            permissions.launch(wanted.toTypedArray())
        }
    }

    // ---------- scanning ----------

    private fun scanNow() {
        if (!Screenshots.hasPermission(this)) {
            refresh()
            return
        }
        scanButton.isEnabled = false
        scanProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = Scanner.scan(this@MainActivity) { reading ->
                statsText.text = getString(R.string.reading_now, reading)
            }
            scanProgress.visibility = View.GONE
            scanButton.isEnabled = true
            refresh()
            result.error?.let { e ->
                val message = if (e.code == 429) R.string.scan_busy else R.string.scan_offline
                if (e.code == 429 || e.code == 0) Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
                else Snackbar.make(root, e.message.orEmpty(), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleDone(shot: Shot) {
        store.update(shot.mediaId) { it.copy(handled = !it.handled) }
        refresh()
    }

    private fun retry(shot: Shot) {
        Scanner.retry(this, shot.mediaId)
        scanNow()
    }

    private fun previewDigest() {
        val summary = Digest.summarize(this)
        val shown = Digest.show(this, summary)
        Snackbar.make(root, if (shown) R.string.digest_sent else R.string.digest_need_permission, Snackbar.LENGTH_LONG).show()
    }

    // ---------- showing things ----------

    private fun refresh() {
        val hasAccess = Screenshots.hasPermission(this)
        permissionCard.visibility = if (hasAccess) View.GONE else View.VISIBLE
        permissionButton.setText(
            if (askedOnce && !shouldShowRequestPermissionRationale(Screenshots.permission)) R.string.permission_settings
            else R.string.permission_button
        )

        val all = store.all()
        val toHandle = all.filter { (it.isActionable && !it.handled) || it.status != ShotStatus.READ }
        val done = all.filter { it.isActionable && it.handled }
        val skipped = all.filter { it.status == ShotStatus.READ && !it.isActionable }

        statsText.text = if (all.isEmpty()) getString(R.string.stats_empty)
        else getString(
            R.string.stats_line,
            all.size,
            all.count { it.isActionable && !it.handled },
            all.count { it.kind == Kind.SKIP },
            all.count { it.kind == Kind.PRIVATE }
        )

        val (shown, emptyMessage) = when (tabs.checkedButtonId) {
            R.id.tabDone -> done to R.string.empty_done
            R.id.tabSkipped -> skipped to R.string.empty_skipped
            else -> toHandle to R.string.empty_todo
        }
        adapter.submit(shown.sortedByDescending { it.takenAtSec })
        emptyText.setText(emptyMessage)
        emptyText.visibility = if (shown.isEmpty() && hasAccess) View.VISIBLE else View.GONE
    }

    // ---------- theme ----------

    private fun applyTheme(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun showThemeIcon(dark: Boolean) {
        themeButton.setIconResource(if (dark) R.drawable.ic_moon else R.drawable.ic_sun)
        themeButton.contentDescription =
            getString(if (dark) R.string.switch_to_light else R.string.switch_to_dark)
    }
}
