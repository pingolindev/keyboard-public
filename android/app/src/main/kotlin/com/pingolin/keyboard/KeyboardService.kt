// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.pingolin.keyboard.engine.EditCommand
import com.pingolin.keyboard.engine.FieldKind
import com.pingolin.keyboard.engine.KeyAction
import com.pingolin.keyboard.engine.ModifierKey
import com.pingolin.keyboard.engine.builtinLayoutFor
import com.pingolin.keyboard.engine.initialLayoutId
import com.pingolin.keyboard.engine.terminalRows
import java.util.Locale

/**
 * The input method. Suggestions, autocorrect and predictions come from the
 * Rust engine. The text editing panel and the terminal rows send the keys a
 * hardware keyboard would.
 */
class KeyboardService : InputMethodService() {
    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStripView? = null
    private var autofill: AutofillBar? = null
    private var inputArea: View? = null
    private var clipPanel: ClipboardPanel? = null
    private var voicePanel: VoicePanel? = null
    private val voice = VoiceInput(object : VoiceInput.Listener {
        override fun onLevel(level: Float) {
            voicePanel?.setLevel(level)
        }

        override fun onWriting() {
            voicePanel?.setState(VoicePanel.State.WRITING)
        }

        override fun onText(text: String?) {
            when {
                text == null -> voicePanel?.setState(VoicePanel.State.FAILED)
                text.isEmpty() -> voicePanel?.setState(VoicePanel.State.NOTHING)
                else -> {
                    logic.onVoice(text)
                    hideVoice()
                }
            }
        }
    })
    private lateinit var clipboard: ClipboardStore
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener { recordClip() }
    private val shift = ShiftController()
    private val ctrl = Latch()
    private val alt = Latch()

    /** Select is on in the editing panel: moving keys extend the selection. */
    private var selecting = false
    private var layoutId = LETTERS

    /** What the field the keys last started on takes. */
    private var fieldKind = FieldKind.TEXT
    private lateinit var settings: KeyboardSettings
    private lateinit var languages: Languages
    private lateinit var engine: Engine
    private lateinit var logic: InputLogic

    /** The languages usable in this field, the current one first. */
    private var active: List<Language> = emptyList()

    /** Colors, height and automatic incognito follow the setup screen at once (it runs in this process). */
    private val styleChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KeyboardSettings.THEME || key == KeyboardSettings.HEIGHT) applyStyle()
        if (key == KeyboardSettings.AUTO_INCOGNITO || key == KeyboardSettings.AUTO_SPACE) {
            currentInputEditorInfo?.let { logic.updatePolicy(policyFor(it, isUnlocked())) }
            updateStrip()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        settings.listen(styleChanged)
        clipboard = ClipboardStore.get(this)
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipChanged)
        languages = Languages.get(this)
        engine = Engine(this)
        val field = ConnectionTextField({ currentInputConnection }) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        logic = InputLogic(field, engine) { strip?.show(it) }
    }

    override fun onDestroy() {
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipChanged)
        clipboard.save()
        engine.save()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val keys = KeyboardView(this, ::onKey, engine::setKeys, ::nextLanguage, ::onGlide, ::deleteWord)
        val suggestions = SuggestionStripView(this, logic::onPick, ::onTool)
        val passwords = AutofillBar(this)
        // A password manager's chips cover the words, not the tools button.
        val stripArea = FrameLayout(this).apply {
            addView(suggestions)
            addView(
                passwords,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = stripHeight()
                },
            )
        }
        val clips = ClipboardPanel(this, ::paste, ::hideClipboard) { clipboard.save() }
        val speech = VoicePanel(this, ::hideVoice, ::onVoiceAction)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stripArea)
            addView(keys)
            addView(clips)
            addView(speech)
        }
        container.setOnApplyWindowInsetsListener { v, insets ->
            val edges = keyEdges(insets)
            v.setPadding(edges[0], 0, edges[1], edges[2])
            insets
        }
        keyboard = keys
        strip = suggestions
        autofill = passwords
        clipPanel = clips
        voicePanel = speech
        inputArea = container
        applyStyle()
        showLayout(layoutId)
        return container
    }

    /** The user's colors and keyboard height. */
    private fun applyStyle() {
        val theme = theme()
        keyboard?.setStyle(theme, settings.heightPercent)
        strip?.setTheme(theme)
        autofill?.setBackgroundColor(theme.background)
        inputArea?.setBackgroundColor(theme.background)
        colorNavigationBar(theme.background)
    }

    /**
     * Up to Android 14 the system draws the navigation bar under the
     * keyboard in the window's color. Later the keyboard draws behind it, so
     * the bar gets no gray layer of its own. Either way its buttons contrast
     * with [color].
     */
    private fun colorNavigationBar(color: Int) {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            w.isNavigationBarContrastEnforced = false
        } else {
            @Suppress("DEPRECATION") // only used before Android 15
            w.navigationBarColor = color
        }
        val light = ColorUtils.calculateLuminance(color) > 0.5
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val flag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (light) flag else 0, flag)
        } else {
            @Suppress("DEPRECATION")
            val flags = w.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (light) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    private fun theme(): KeyboardTheme = KeyboardTheme.of(this, settings.theme)

    private fun stripHeight(): Int = (STRIP_DP * resources.displayMetrics.density).toInt()

    /** Asks the password manager for suggestions shown in the strip (Android 11+). */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        AutofillBar.request(this, (CHIP_DP * resources.displayMetrics.density).toInt(), resources.displayMetrics.widthPixels, theme())

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val bar = autofill ?: return false
        bar.show(response.inlineSuggestions)
        return true
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Until the keys show, typing comes from a hardware keyboard: no
        // composing, suggestions or learning (onStartInputView sets them).
        logic.start(InputPolicy.PLAIN, attribute.initialSelStart, attribute.initialSelEnd)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyStyle()
        hideClipboard()
        hideVoice()
        val unlocked = isUnlocked()
        if (unlocked) clipboard.load(settings.keepClipboard)
        chooseLanguages(unlocked)
        // A browser moves between the fields of a page as a restart of one
        // field: a field of another kind (a number after a name) still gets
        // its own layout. The same kind keeps the layout the user is on.
        val kind = FieldPolicy.fieldKind(info.inputType)
        if (!restarting || kind != fieldKind) {
            shift.reset()
            ctrl.reset()
            alt.reset()
            showLayout(initialLayoutId(kind))
        }
        fieldKind = kind
        val policy = policyFor(info, unlocked)
        logic.start(policy, info.initialSelStart, info.initialSelEnd)
        keyboard?.setGlideEnabled(settings.glideTyping && policy.compose)
        strip?.closeTools()
        updateStrip()
        updateAutoCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        autofill?.hide()
        hideVoice()
        logic.finish()
        engine.save()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        logic.finish()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        logic.onSelectionUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
    }

    private fun isUnlocked(): Boolean = getSystemService(UserManager::class.java)?.isUserUnlocked ?: false

    /** The enabled languages that can be used now (opened files need the phone unlocked). */
    private fun chooseLanguages(unlocked: Boolean) {
        val available = languages.available(unlocked).associateBy { it.tag }
        val enabled = settings.languages.mapNotNull { available[it] }
            .ifEmpty { listOfNotNull(available[Languages.FALLBACK] ?: available.values.firstOrNull()) }
        val current = enabled.firstOrNull { it.tag == settings.language } ?: enabled.firstOrNull()
        active = listOfNotNull(current) + enabled.filter { it != current }
        engine.configure(active.map { it.tag }, unlocked)
    }

    /** A long press on Space: the next enabled language, round the list. */
    private fun nextLanguage() {
        if (active.size < 2) return
        logic.finish() // the word so far stays as typed
        val unlocked = isUnlocked()
        active = active.drop(1) + active.first()
        settings.language = active.first().tag
        engine.configure(active.map { it.tag }, unlocked)
        currentInputEditorInfo?.let { logic.updatePolicy(policyFor(it, unlocked)) }
        // The same layout again: letters change with the language, the others keep.
        showLayout(layoutId)
    }

    /**
     * No suggestions in passwords. In incognito (the field's or the user's)
     * and before the first unlock, nothing is learned or used.
     */
    private fun policyFor(info: EditorInfo, unlocked: Boolean): InputPolicy {
        val compose = settings.suggestions && FieldPolicy.mayCompose(info.inputType)
        val personal = unlocked && !settings.incognito && FieldPolicy.mayLearn(info.inputType, info.imeOptions, settings.autoIncognito)
        return InputPolicy(
            compose = compose,
            autocorrect = compose && settings.autocorrect && FieldPolicy.mayAutocorrect(info.inputType),
            autoSpace = compose && settings.autoSpace && FieldPolicy.mayAutocorrect(info.inputType),
            personal = personal,
            learn = personal && settings.learnWords,
            attaching = if (locale().language == "fr") ".," else ".,!?:;",
        )
    }

    private fun locale(): Locale = Locale.forLanguageTag(active.firstOrNull()?.tag ?: Languages.FALLBACK)

    private fun onTool(tool: Tool) {
        when (tool) {
            Tool.INCOGNITO -> {
                settings.incognito = !settings.incognito
                currentInputEditorInfo?.let { logic.updatePolicy(policyFor(it, isUnlocked())) }
            }
            Tool.TEXT_EDITING -> {
                hideVoice()
                hideClipboard()
                showLayout(EDITING)
            }
            Tool.TERMINAL -> {
                currentInputEditorInfo?.packageName?.let { app ->
                    settings.setTerminalKeys(app, !settings.terminalKeys(app))
                }
                hideVoice()
                hideClipboard()
                showLayout(layoutId)
            }
            Tool.CLIPBOARD -> {
                hideVoice()
                showClipboard()
            }
            Tool.VOICE -> startVoice()
            Tool.SETTINGS -> {
                hideVoice()
                requestHideSelf(0)
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        updateStrip()
    }

    /**
     * Copied text goes into the history, except clips the app marks
     * sensitive (password managers do), and nothing in incognito.
     */
    private fun recordClip() {
        if (settings.incognito) return
        val info = currentInputEditorInfo
        if (info != null && (appIncognito(info) || FieldPolicy.isPassword(info.inputType))) return
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true) return
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return
        if (clipboard.history.add(text)) clipboard.save()
    }

    private fun showClipboard() {
        val keys = keyboard ?: return
        val panel = clipPanel ?: return
        logic.finishWord()
        if (isUnlocked()) clipboard.load(settings.keepClipboard)
        panel.show(clipboard.history, theme(), keys.height)
        keys.visibility = View.GONE
    }

    private fun hideClipboard() {
        val panel = clipPanel ?: return
        if (panel.visibility == View.GONE) return
        panel.visibility = View.GONE
        keyboard?.visibility = View.VISIBLE
    }

    /** Voice typing: the panel in place of the keys, listening at once when it can. */
    private fun startVoice() {
        val keys = keyboard ?: return
        val panel = voicePanel ?: return
        logic.finishWord()
        hideClipboard()
        panel.show(theme(), keys.height)
        keys.visibility = View.GONE
        listen()
    }

    private fun listen() {
        val panel = voicePanel ?: return
        if (!isUnlocked()) return panel.setState(VoicePanel.State.LOCKED)
        val (model, file) = VoiceModels.get(this).installed() ?: return panel.setState(VoicePanel.State.NO_MODEL)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return panel.setState(VoicePanel.State.NO_MIC)
        }
        val language = if (model.allLanguages) VoiceModels.whisperLanguage(active.firstOrNull()?.tag ?: Languages.FALLBACK) else "en"
        panel.setState(VoicePanel.State.LISTENING)
        voice.start(file, language)
    }

    private fun onVoiceAction(state: VoicePanel.State) {
        when (state) {
            VoicePanel.State.LISTENING -> voice.stop()
            VoicePanel.State.NOTHING, VoicePanel.State.FAILED -> listen()
            VoicePanel.State.NO_MODEL -> openSetup(askMic = false)
            VoicePanel.State.NO_MIC -> openSetup(askMic = true)
            VoicePanel.State.WRITING, VoicePanel.State.LOCKED -> Unit
        }
    }

    private fun openSetup(askMic: Boolean) {
        hideVoice()
        requestHideSelf(0)
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(SetupActivity.EXTRA_ASK_MIC, askMic),
        )
    }

    private fun hideVoice() {
        voice.cancel()
        val panel = voicePanel ?: return
        if (panel.visibility == View.GONE) return
        panel.visibility = View.GONE
        keyboard?.visibility = View.VISIBLE
    }

    private fun paste(text: String) {
        logic.onPaste(text)
        hideClipboard()
    }

    /** The app asks for incognito here, and the user lets it. */
    private fun appIncognito(info: EditorInfo?): Boolean =
        info != null && settings.autoIncognito && FieldPolicy.asksIncognito(info.imeOptions)

    /** The strip's incognito mark and switched-on tools. */
    private fun updateStrip() {
        strip?.setIncognito(settings.incognito || appIncognito(currentInputEditorInfo))
        strip?.setChecked(
            buildSet {
                if (settings.incognito) add(Tool.INCOGNITO)
                if (settings.terminalKeys(currentInputEditorInfo?.packageName)) add(Tool.TERMINAL)
            },
        )
    }

    private fun onKey(action: KeyAction, touch: PointF?) {
        strip?.closeTools()
        // Typing a letter means the password manager's chips are not wanted.
        if (action is KeyAction.Text) autofill?.hide()
        val modifiers = (if (ctrl.isOn) KeyCodes.CTRL else 0) or (if (alt.isOn) KeyCodes.ALT else 0)
        when (action) {
            is KeyAction.Text -> {
                val text = if (shift.state == ShiftState.OFF) action.text else upperCase(action.text, locale())
                val key = if (modifiers != 0) KeyCodes.of(text) else null
                // The single shift ends before the edit: a field in this process
                // reports its cursor during it, and after ". " that shifts again.
                val autoShifted = shift.isAutomatic
                shift.onTyped()
                if (key != null) {
                    sendKey(key.first, modifiers or (if (key.second) KeyCodes.SHIFT else 0))
                } else {
                    logic.onText(text, touch?.x, touch?.y, autoShifted = autoShifted)
                }
            }
            KeyAction.Shift -> shift.onShiftTap(SystemClock.uptimeMillis())
            KeyAction.Backspace -> if (modifiers != 0) sendKey(KeyEvent.KEYCODE_DEL, modifiers) else logic.onBackspace()
            KeyAction.Space -> if (modifiers != 0) sendKey(KeyEvent.KEYCODE_SPACE, modifiers) else logic.onSpace()
            KeyAction.Enter -> if (modifiers != 0) sendKey(KeyEvent.KEYCODE_ENTER, modifiers) else enter()
            is KeyAction.SwitchLayout -> showLayout(action.layoutId)
            is KeyAction.Edit -> edit(action.command, modifiers)
            is KeyAction.Modifier -> {
                (if (action.key == ModifierKey.CTRL) ctrl else alt).tap(SystemClock.uptimeMillis())
                showKeyStates()
            }
        }
        // Automatic capitals follow in onUpdateSelection, once the field has
        // the edit. Not here: that would undo a Shift tap that cancels them.
        keyboard?.setShift(shift.state)
    }

    private fun onGlide(points: List<GlidePoint>) {
        strip?.closeTools()
        autofill?.hide()
        val state = shift.state
        shift.onTyped()
        logic.onGlide(points, state)
        keyboard?.setShift(shift.state)
    }

    private fun enter() {
        val editorAction = currentInputEditorInfo?.let { FieldPolicy.enterAction(it.imeOptions) }
        logic.onEnter(isAction = editorAction != null)
        val connection = currentInputConnection
        if (editorAction != null && connection != null) {
            connection.performEditorAction(editorAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Delete held down, a word at a time. With Ctrl or Alt on, the field gets the key instead. */
    private fun deleteWord() {
        if (ctrl.isOn || alt.isOn) {
            onKey(KeyAction.Backspace, null)
        } else {
            strip?.closeTools()
            logic.onDeleteWord()
        }
    }

    /** An editing key: the word being typed stays as typed, then the key goes to the field. */
    private fun edit(command: EditCommand, modifiers: Int) {
        val connection = currentInputConnection ?: return
        when (command) {
            EditCommand.SELECT -> {
                logic.finishWord()
                selecting = !selecting
                if (!selecting) connection.clearMetaKeyStates(KeyEvent.META_SHIFT_ON)
                showKeyStates()
            }
            EditCommand.SELECT_ALL -> contextAction(android.R.id.selectAll, endSelect = false)
            EditCommand.CUT -> contextAction(android.R.id.cut, endSelect = true)
            EditCommand.COPY -> contextAction(android.R.id.copy, endSelect = true)
            EditCommand.PASTE -> contextAction(android.R.id.paste, endSelect = true)
            else -> {
                val code = KeyCodes.of(command) ?: return
                val select = if (selecting && KeyCodes.moves(command)) KeyCodes.SHIFT else 0
                sendKey(code, modifiers or select)
            }
        }
    }

    private fun contextAction(id: Int, endSelect: Boolean) {
        logic.finishWord()
        currentInputConnection?.performContextMenuAction(id)
        if (endSelect && selecting) {
            selecting = false
            currentInputConnection?.clearMetaKeyStates(KeyEvent.META_SHIFT_ON)
            showKeyStates()
        }
    }

    /**
     * Sends a key press with [meta] held, as a hardware keyboard does. With
     * Shift, the Shift key goes down first, since text fields select only
     * while it is down. Ctrl and Alt held for one key are released after it.
     */
    private fun sendKey(code: Int, meta: Int) {
        val connection = currentInputConnection ?: return
        logic.finishWord()
        val shifted = (meta and KeyEvent.META_SHIFT_ON) != 0
        if (shifted) connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, meta))
        connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, code, meta))
        connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP, code, meta))
        if (shifted) {
            connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, meta and KeyCodes.SHIFT.inv()))
            connection.clearMetaKeyStates(KeyEvent.META_SHIFT_ON)
        }
        if (ctrl.isOn || alt.isOn) {
            ctrl.used()
            alt.used()
            showKeyStates()
        }
    }

    private fun keyEvent(action: Int, code: Int, meta: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        )
    }

    private fun showKeyStates() {
        val on = mutableSetOf<KeyAction>()
        val locked = mutableSetOf<KeyAction>()
        for ((latch, key) in listOf(ctrl to ModifierKey.CTRL, alt to ModifierKey.ALT)) {
            when (latch.state) {
                Latch.State.ONCE -> on += KeyAction.Modifier(key)
                Latch.State.LOCKED -> locked += KeyAction.Modifier(key)
                Latch.State.OFF -> Unit
            }
        }
        if (selecting) on += KeyAction.Edit(EditCommand.SELECT)
        keyboard?.setKeyStates(on, locked)
    }

    /**
     * Shows a layout; "letters" means the current language's letters. The
     * terminal rows go on top only in apps where the user turned them on: a
     * field that takes raw keys is not enough (a search box on amazon.com said
     * it did).
     */
    private fun showLayout(id: String) {
        val language = active.firstOrNull()
        val resolved = if (id == LETTERS) language?.layout ?: LETTERS else id
        val tag = language?.tag.orEmpty()
        val base = builtinLayoutFor(resolved, tag) ?: builtinLayoutFor(LETTERS, tag) ?: return
        layoutId = id
        if (selecting) {
            selecting = false
            currentInputConnection?.clearMetaKeyStates(KeyEvent.META_SHIFT_ON)
        }
        val rawKeys = currentInputEditorInfo?.let { FieldPolicy.takesRawKeys(it.inputType) } ?: false
        keyboard?.holdDeletesWords = !rawKeys
        val terminal = id != EDITING && settings.terminalKeys(currentInputEditorInfo?.packageName)
        val layout = if (terminal) base.copy(rows = terminalRows() + base.rows) else base
        val label = if (active.size > 1) language?.name.orEmpty() else ""
        keyboard?.show(layout, shift.state, locale(), label)
        showKeyStates()
    }

    private fun updateAutoCaps() {
        val info = currentInputEditorInfo
        val connection = currentInputConnection
        if (info != null && connection != null) shift.onAutoCaps(connection.getCursorCapsMode(info.inputType) != 0)
        keyboard?.setShift(shift.state)
    }

    /**
     * Left, right and bottom space the keys keep clear. From Android 15 the
     * keyboard draws behind the navigation bar. With gesture navigation the bar
     * under a keyboard holds the hide and switch-keyboard buttons, which are
     * taller than the bar's own inset: tappableElement, not navigationBars
     * (24 dp against 48 dp on Android 16). The sides cover a bar or a camera
     * cutout in landscape.
     */
    private fun keyEdges(insets: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement() or
                WindowInsets.Type.displayCutout()
            val edges = insets.getInsets(types)
            intArrayOf(edges.left, edges.right, edges.bottom)
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }

    private companion object {
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); apps set it on older versions too. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** The strip's height (SuggestionStripView), and a password manager's chip in it. */
        const val STRIP_DP = 44
        const val CHIP_DP = 36
        const val LETTERS = LayoutIds.LETTERS
        const val EDITING = LayoutIds.EDITING
    }
}

/** A key's letter in upper case, unless that is not one letter (ß stays ß, not SS). */
fun upperCase(text: String, locale: Locale): String {
    val upper = text.uppercase(locale)
    return if (upper.codePointCount(0, upper.length) == text.codePointCount(0, text.length)) upper else text
}
