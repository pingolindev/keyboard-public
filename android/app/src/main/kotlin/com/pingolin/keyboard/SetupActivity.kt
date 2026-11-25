// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Shows whether the keyboard is on, opens the system screens that turn it on,
 * and holds the settings.
 */
class SetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var enable: Button
    private lateinit var select: Button
    private lateinit var languageList: LinearLayout
    private lateinit var settings: KeyboardSettings
    private lateinit var languages: Languages
    private val io = Executors.newSingleThreadExecutor()

    /** What the next export file holds, chosen in the export dialog. */
    private var exportSettings = true
    private var exportWords = true
    private var exportClips = true

    /** The pinned clips to export, taken when the user chose (the history lives on the main thread). */
    private var pendingClips: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        settings = KeyboardSettings(this)
        languages = Languages.get(this)
        status = findViewById(R.id.status)
        enable = findViewById(R.id.enable)
        select = findViewById(R.id.select)
        languageList = findViewById(R.id.languages)
        // Section titles are headings for TalkBack, on every Android version.
        for (id in listOf(R.id.appearance_title, R.id.languages_title, R.id.voice_title, R.id.clipboard_title, R.id.learning_title)) {
            ViewCompat.setAccessibilityHeading(findViewById(id), true)
        }
        enable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        select.setOnClickListener { inputMethods().showInputMethodPicker() }

        val suggestions = findViewById<Switch>(R.id.suggestions)
        val autocorrect = findViewById<Switch>(R.id.autocorrect)
        // Like autocorrect, these work with suggestions on.
        val autoSpace = findViewById<Switch>(R.id.auto_space)
        val suggestEmoji = findViewById<Switch>(R.id.suggest_emoji)
        suggestions.isChecked = settings.suggestions
        autocorrect.isChecked = settings.autocorrect
        autocorrect.isEnabled = settings.suggestions
        autoSpace.isChecked = settings.autoSpace
        autoSpace.isEnabled = settings.suggestions
        suggestEmoji.isChecked = settings.suggestEmoji
        suggestEmoji.isEnabled = settings.suggestions
        suggestions.setOnCheckedChangeListener { _, on ->
            settings.suggestions = on
            autocorrect.isEnabled = on
            autoSpace.isEnabled = on
            suggestEmoji.isEnabled = on
        }
        autocorrect.setOnCheckedChangeListener { _, on -> settings.autocorrect = on }
        autoSpace.setOnCheckedChangeListener { _, on -> settings.autoSpace = on }
        suggestEmoji.setOnCheckedChangeListener { _, on -> settings.suggestEmoji = on }
        findViewById<Switch>(R.id.glide).run {
            isChecked = settings.glideTyping
            setOnCheckedChangeListener { _, on -> settings.glideTyping = on }
        }

        findViewById<Button>(R.id.languages_get).setOnClickListener { chooseLanguageDownload() }
        findViewById<Button>(R.id.languages_open).setOnClickListener { pick(REQUEST_LANGUAGE) }
        val other = findViewById<Switch>(R.id.languages_other)
        other.isChecked = settings.allowOtherLanguageFiles
        other.setOnCheckedChangeListener { _, on -> settings.allowOtherLanguageFiles = on }

        setUpAppearance()
        setUpVoice()
        setUpClipboard()
        if (savedInstanceState == null) askForMicrophone(intent, openedForIt = true)

        val learn = findViewById<Switch>(R.id.learn)
        learn.isChecked = settings.learnWords
        learn.setOnCheckedChangeListener { _, on -> settings.learnWords = on }
        val autoIncognito = findViewById<Switch>(R.id.auto_incognito)
        autoIncognito.text = withHint(getString(R.string.setting_auto_incognito), getString(R.string.setting_auto_incognito_hint))
        autoIncognito.isChecked = settings.autoIncognito
        autoIncognito.setOnCheckedChangeListener { _, on -> settings.autoIncognito = on }
        findViewById<Button>(R.id.forget).setOnClickListener { confirmForget() }
        findViewById<Button>(R.id.export).setOnClickListener { chooseExport() }
        findViewById<Button>(R.id.import_file).setOnClickListener { pick(REQUEST_IMPORT) }
        findViewById<Button>(R.id.privacy_policy).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY)))
        }
        showLanguages()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // A file opened from the browser may have added a language or a voice model meanwhile.
        showLanguages()
        showVoice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askForMicrophone(intent, openedForIt = false)
    }

    /** A switch's label with a hint under it, smaller and lighter. TalkBack reads both. */
    private fun withHint(label: String, hint: String): CharSequence {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val color = attrs.getColor(0, Color.GRAY)
        attrs.recycle()
        return SpannableStringBuilder(label).append('\n').apply {
            val start = length
            append(hint)
            setSpan(RelativeSizeSpan(HINT_SIZE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Closes after the answer when this screen was opened only to ask. */
    private var closeAfterMicrophone = false

    /** The keyboard sends the user here when voice typing needs the microphone. */
    private fun askForMicrophone(intent: Intent, openedForIt: Boolean) {
        if (!intent.getBooleanExtra(EXTRA_ASK_MIC, false)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        closeAfterMicrophone = openedForIt
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && closeAfterMicrophone) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Runs again when the system's keyboard picker closes.
        if (hasFocus) refresh()
    }

    private fun refresh() {
        val enabled = inputMethods().enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.substringBefore('/') == packageName
        enable.isEnabled = !enabled
        select.isEnabled = enabled && !selected
        status.setText(
            when {
                selected -> R.string.setup_status_ready
                enabled -> R.string.setup_status_on
                else -> R.string.setup_status_off
            },
        )
    }

    /** Colors and height. The keyboard follows at once, so the try field shows them. */
    private fun setUpAppearance() {
        val themeButton = findViewById<Button>(R.id.theme)
        val themes = KeyboardTheme.IDS.filter { it != KeyboardTheme.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
        val showTheme = { themeButton.text = getString(R.string.theme_button, getString(themeName(settings.theme))) }
        showTheme()
        themeButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.theme_title)
                .setSingleChoiceItems(themes.map { getString(themeName(it)) }.toTypedArray(), themes.indexOf(settings.theme)) { dialog, which ->
                    settings.theme = themes[which]
                    showTheme()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val height = findViewById<SeekBar>(R.id.height)
        val label = findViewById<TextView>(R.id.height_label)
        val step = HEIGHT_STEP
        height.max = (KeyboardSettings.MAX_HEIGHT - KeyboardSettings.MIN_HEIGHT) / step
        height.progress = (settings.heightPercent - KeyboardSettings.MIN_HEIGHT) / step
        label.text = getString(R.string.height_label, settings.heightPercent)
        height.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val percent = KeyboardSettings.MIN_HEIGHT + progress * step
                label.text = getString(R.string.height_label, percent)
                if (fromUser) settings.heightPercent = percent
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun setUpVoice() {
        val models = VoiceModels.get(this)
        findViewById<Button>(R.id.voice_get).setOnClickListener {
            val list = models.official
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_get)
                .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                    val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${list[which].file}"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        findViewById<Button>(R.id.voice_open).setOnClickListener { pick(REQUEST_VOICE) }
        findViewById<Button>(R.id.voice_remove).setOnClickListener {
            models.remove()
            showVoice()
        }
        showVoice()
    }

    private fun showVoice() {
        val installed = VoiceModels.get(this).installed()?.first
        findViewById<TextView>(R.id.voice_status).text =
            installed?.let { getString(R.string.voice_installed, it.name) } ?: getString(R.string.voice_none)
        findViewById<Button>(R.id.voice_remove).visibility = if (installed == null) View.GONE else View.VISIBLE
    }

    /** A language file or voice model the user picked: added, and the screen shows it. */
    private fun openKeyboardFile(uri: Uri) {
        val message = KeyboardFiles.add(this, uri)
        runOnUiThread {
            toast(message)
            showLanguages()
            showVoice()
        }
    }

    /**
     * The official languages not on the phone yet, each linked to its file in
     * this version's release. Open in the browser then adds it (OpenFileActivity).
     */
    private fun chooseLanguageDownload() {
        val have = languages.available(true).map { it.tag }.toSet()
        val missing = languages.official.filterKeys { it !in have }.entries.sortedBy { it.value.name }
        if (missing.isEmpty()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Languages.DOWNLOAD_PAGE)))
            return
        }
        val version = appVersion()
        AlertDialog.Builder(this)
            .setTitle(R.string.languages_get)
            .setItems(missing.map { it.value.name }.toTypedArray()) { _, which ->
                val url = KeyboardFiles.languageUrl(version, missing[which].key)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun appVersion(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return info.versionName.orEmpty()
    }

    private fun clips(): ClipboardStore = ClipboardStore.get(this).also { it.load(settings.keepClipboard) }

    private fun setUpClipboard() {
        val keep = findViewById<Switch>(R.id.keep_clipboard)
        keep.isChecked = settings.keepClipboard
        keep.setOnCheckedChangeListener { _, on ->
            settings.keepClipboard = on
            clips().run {
                history.keepAll = on
                save()
            }
        }
        findViewById<Button>(R.id.forget_clipboard).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clipboard_forget_confirm)
                .setPositiveButton(R.string.clipboard_forget) { _, _ ->
                    clips().forget()
                    toast(R.string.clipboard_forgotten)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun themeName(id: String): Int = when (id) {
        KeyboardTheme.LIGHT_ID -> R.string.theme_light
        KeyboardTheme.DARK_ID -> R.string.theme_dark
        KeyboardTheme.BLACK_ID -> R.string.theme_black
        KeyboardTheme.DYNAMIC -> R.string.theme_dynamic
        else -> R.string.theme_system
    }

    /** One row per language: a checkbox to type in it, and Remove for opened files. */
    private fun showLanguages() {
        languageList.removeAllViews()
        val available = languages.available(unlocked = true)
        val usable = { settings.languages.filter { tag -> available.any { it.tag == tag } } }
        val enabled = usable().ifEmpty { listOf(available.firstOrNull()?.tag ?: Languages.FALLBACK) }
        for (language in available) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val check = CheckBox(this).apply {
                text = language.name
                isChecked = language.tag in enabled
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnCheckedChangeListener { box, on ->
                    val now = usable().toMutableList()
                    when {
                        on -> now += language.tag
                        now.size > 1 -> now -= language.tag
                        else -> {
                            box.isChecked = true
                            toast(R.string.languages_last)
                            return@setOnCheckedChangeListener
                        }
                    }
                    settings.languages = now
                    if (settings.language !in now) settings.language = now.first()
                }
            }
            row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!language.builtIn) {
                row.addView(
                    Button(this).apply {
                        setText(R.string.languages_remove)
                        setOnClickListener {
                            languages.remove(language.tag)
                            settings.languages = settings.languages.filter { it != language.tag }
                            showLanguages()
                        }
                    },
                )
            }
            languageList.addView(row)
        }
    }

    @Suppress("DEPRECATION") // the platform Activity's result API; no androidx.activity here
    /** The system's file picker, opened in Downloads, where the browser saves. */
    private fun pick(request: Int) {
        val downloads = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads)
        startActivityForResult(intent, request)
    }

    @Suppress("DEPRECATION")
    private fun chooseExport() {
        val labels = arrayOf(getString(R.string.export_settings), getString(R.string.export_words), getString(R.string.export_clips))
        val chosen = booleanArrayOf(exportSettings, exportWords, exportClips)
        AlertDialog.Builder(this)
            .setTitle(R.string.export)
            .setMultiChoiceItems(labels, chosen) { _, which, on -> chosen[which] = on }
            .setPositiveButton(R.string.export) { _, _ ->
                exportSettings = chosen[0]
                exportWords = chosen[1]
                exportClips = chosen[2]
                if (!exportSettings && !exportWords && !exportClips) return@setPositiveButton
                pendingClips = if (exportClips) clips().history.toJson(everything = false) else null
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "pingolin-keyboard.json")
                startActivityForResult(intent, REQUEST_EXPORT)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setMessage(R.string.learned_forget_confirm)
            .setPositiveButton(R.string.learned_forget) { _, _ ->
                io.execute {
                    LearnedWords.get(this).run {
                        load()
                        clear()
                    }
                    runOnUiThread { toast(R.string.learned_forgotten) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("The platform Activity's result API; this activity has no androidx.activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_LANGUAGE -> io.execute { openKeyboardFile(uri) }
            REQUEST_EXPORT -> io.execute { writeExport(uri) }
            REQUEST_IMPORT -> io.execute { readImport(uri) }
            REQUEST_VOICE -> io.execute { openKeyboardFile(uri) }
        }
    }

    /** A file the user picked, or null if it cannot be read or is over [limit] bytes. */
    private fun readBytes(uri: Uri, limit: Int): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) return@use null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun writeExport(uri: Uri) {
        val words = if (exportWords) LearnedWords.get(this).load().toJson() else null
        val text = KeyboardExport.write(KeyboardExport.Contents(settings.snapshot().takeIf { exportSettings }, words, pendingClips))
        val ok = try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
        runOnUiThread { toast(if (ok) R.string.export_done else R.string.file_unreadable) }
    }

    private fun readImport(uri: Uri) {
        val contents = try {
            readBytes(uri, KeyboardExport.MAX_BYTES)?.let { KeyboardExport.read(it.toString(Charsets.UTF_8)) }
        } catch (e: OutOfMemoryError) {
            null // a file far larger than any export, inside the size limit
        }
        runOnUiThread {
            if (contents == null) {
                toast(R.string.import_invalid)
                return@runOnUiThread
            }
            // The parts the file has, each one the user can leave out.
            val parts = listOfNotNull(
                R.string.export_settings.takeIf { contents.settings != null },
                R.string.export_words.takeIf { contents.wordsJson != null },
                R.string.export_clips.takeIf { contents.clipboardJson != null },
            )
            val chosen = BooleanArray(parts.size) { true }
            AlertDialog.Builder(this)
                .setTitle(R.string.import_file)
                .setMultiChoiceItems(parts.map { getString(it) }.toTypedArray(), chosen) { _, which, on -> chosen[which] = on }
                .setPositiveButton(R.string.import_file) { _, _ ->
                    val use = { part: Int -> parts.indexOf(part).let { it >= 0 && chosen[it] } }
                    if (use(R.string.export_clips)) {
                        val store = clips()
                        if (store.history.mergeJson(contents.clipboardJson ?: "") != null) store.save()
                    }
                    val useSettings = use(R.string.export_settings)
                    val useWords = use(R.string.export_words)
                    io.execute { applyImport(contents, useSettings, useWords) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun applyImport(contents: KeyboardExport.Contents, useSettings: Boolean, useWords: Boolean) {
        var ok = true
        if (useSettings) contents.settings?.let { settings.restore(it) }
        if (useWords) {
            val learned = LearnedWords.get(this)
            ok = try {
                learned.load().mergeJson(contents.wordsJson ?: "")
                learned.save()
                true
            } catch (e: Exception) {
                false
            }
        }
        runOnUiThread {
            toast(if (ok) R.string.import_done else R.string.import_invalid)
            recreate()
        }
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun inputMethods(): InputMethodManager = getSystemService(InputMethodManager::class.java)

    companion object {
        /** Google Play requires a link to it inside the app. */
        private const val PRIVACY_POLICY = "https://github.com/pingolindev/keyboard-public/blob/main/PRIVACY.md"

        /** Set by the keyboard: ask for the microphone at once. */
        const val EXTRA_ASK_MIC = "com.pingolin.keyboard.ASK_MIC"
        private const val HEIGHT_STEP = 5
        private const val HINT_SIZE = 0.85f
        private const val REQUEST_LANGUAGE = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_IMPORT = 3
        private const val REQUEST_VOICE = 4
        private const val REQUEST_MIC = 5
    }
}
