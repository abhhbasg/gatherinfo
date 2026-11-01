package com.metrowin.keyboard.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.metrowin.keyboard.ui.Fonts
import androidx.preference.PreferenceFragmentCompat
import com.metrowin.keyboard.K
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.engine.LanguageEngine
import com.metrowin.keyboard.integration.LauncherLink
import com.metrowin.keyboard.service.ScreenColorService
import com.metrowin.keyboard.theme.Accents
import java.util.concurrent.Executors

/** Shared plumbing of the settings screens: a preference file, a title, and quick access to the app. */
abstract class BaseFragment(private val xml: Int, private val title: Int) : PreferenceFragmentCompat() {
    /** Rows in the Windows Phone manner: big light titles, small grey summaries, accent-coloured section names. */
    @SuppressLint("RestrictedApi")
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> = object : PreferenceGroupAdapter(preferenceScreen) {
        override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
            super.onBindViewHolder(holder, position)
            val ctx = holder.itemView.context
            val item = getItem(position)
            val titleView = holder.findViewById(android.R.id.title) as? TextView
            val summaryView = holder.findViewById(android.R.id.summary) as? TextView
            if (item is PreferenceCategory) {
                titleView?.apply { text = text.toString().uppercase(); typeface = Fonts.semibold(ctx); textSize = 13f; setTextColor(MetroUi.accent(ctx)) }
            } else {
                titleView?.apply { typeface = Fonts.light(ctx); textSize = 22f; setTextColor(MetroUi.fg(ctx)) }
                summaryView?.apply { typeface = Fonts.regular(ctx); textSize = 14f; setTextColor(MetroUi.subtle(ctx)) }
            }
        }
    }

    protected val app get() = KeyboardApp.of(requireContext())
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(xml, rootKey)
        setup()
    }

    abstract fun setup()

    override fun onResume() { super.onResume(); requireActivity().setTitle(title) }

    protected fun click(key: String, block: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener { block(); true }
    }

    protected fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    protected fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

    /** Runs [work] off the main thread and then [done] on it, if the screen is still there. */
    protected fun <T> background(work: () -> T, done: (T) -> Unit) {
        val ctx = requireContext().applicationContext
        io.execute {
            val r = work()
            android.os.Handler(ctx.mainLooper).post { if (isAdded) done(r) }
        }
    }

    protected fun chooseOne(titleRes: Int, labels: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(requireContext()).setTitle(titleRes).setItems(labels.toTypedArray()) { _, i -> onPick(i) }.setNegativeButton(R.string.cancel, null).show()
    }
}

class RootFragment : BaseFragment(R.xml.prefs_root, R.string.app_name) {
    override fun setup() {
        click("setup_enable") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        click("setup_select") { (requireContext().getSystemService(InputMethodManager::class.java)).showInputMethodPicker() }
        click("setup_try") { startActivity(Intent(requireContext(), TestActivity::class.java)) }
        click("setup_guide") { startActivity(Intent(requireContext(), OnboardingActivity::class.java)) }
        click("setup_tutorial") { startActivity(Intent(requireContext(), TutorialActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        val pkg = requireContext().packageName
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == pkg }
        val active = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$pkg/") == true
        findPreference<Preference>("setup_enable")?.summary = getString(if (enabled) R.string.setup_enabled else R.string.setup_disabled)
        findPreference<Preference>("setup_select")?.summary = getString(if (active) R.string.setup_active else R.string.setup_inactive)
        findPreference<Preference>("setup_tutorial")?.summary = getString(if (com.metrowin.keyboard.tutorial.Tutorial.hasNew(app.prefs.tutorialSeen)) R.string.setup_tutorial_new else R.string.setup_tutorial_summary)
    }
}

class LanguagesFragment : BaseFragment(R.xml.prefs_languages, R.string.screen_languages) {
    private var pendingLang: String? = null

    private val openDictionary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingLang
        if (uri == null || id == null) return@registerForActivityResult
        val layout = app.layouts.get(id) ?: return@registerForActivityResult
        val ctx = requireContext().applicationContext
        background({ runCatching { ctx.contentResolver.openInputStream(uri)!!.use { LanguageEngine.importDictionary(ctx, layout.dictionaryId, layout.locale, it) } }.getOrDefault(0) }) { count ->
            if (count > 0) { app.reloadEngines(); toast(getString(R.string.import_done_words, count)) } else toast(R.string.import_failed)
        }
    }

    private val openLayout = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { requireContext().contentResolver.openInputStream(uri)!!.use { it.readBytes().take(64 * 1024).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
        val layout = text?.let { app.layouts.import(it) }
        if (layout != null) toast(getString(R.string.import_layout_done, layout.name)) else toast(R.string.import_failed)
    }

    override fun setup() {
        click("languages_pick") {
            val all = app.layouts.all().values.toList()
            val current = app.prefs.languages.toMutableSet()
            val checked = BooleanArray(all.size) { all[it].id in current }
            AlertDialog.Builder(requireContext()).setTitle(R.string.pref_languages_title)
                .setMultiChoiceItems(all.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(R.string.ok) { _, _ ->
                    val picked = all.filterIndexed { i, _ -> checked[i] }.map { it.id }.ifEmpty { listOf("en") }
                    app.prefs.languages = picked
                    if (app.prefs.currentLanguage !in picked) app.prefs.currentLanguage = picked.first()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
        click("import_dictionary") {
            val all = app.layouts.all().values.toList()
            chooseOne(R.string.dialog_pick_language, all.map { it.name }) { i -> pendingLang = all[i].id; openDictionary.launch(arrayOf("*/*")) }
        }
        click("remove_dictionary") {
            val withImport = app.layouts.all().values.filter { LanguageEngine.dictionaryFile(requireContext(), it.dictionaryId).exists() }
            if (withImport.isEmpty()) { toast(R.string.nothing_to_remove); return@click }
            chooseOne(R.string.dialog_pick_imported, withImport.map { it.name }) { i ->
                LanguageEngine.removeImportedDictionary(requireContext(), withImport[i].dictionaryId); app.reloadEngines(); toast(R.string.removed)
            }
        }
        click("import_layout") { openLayout.launch(arrayOf("*/*")) }
        click("remove_layout") {
            val mine = app.layouts.all().values.filter { !it.builtIn }
            if (mine.isEmpty()) { toast(R.string.nothing_to_remove); return@click }
            chooseOne(R.string.dialog_pick_imported, mine.map { it.name }) { i -> app.layouts.remove(mine[i].id); toast(R.string.removed) }
        }
    }
}

class AppearanceFragment : BaseFragment(R.xml.prefs_appearance, R.string.screen_appearance) {
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        background({ BackgroundImage.save(ctx, uri) }) { ok -> toast(if (ok) R.string.picture_saved else R.string.picture_failed) }
    }

    override fun setup() {
        click("accent_pick") { showAccentDialog() }
        click("image_pick") { pickImage.launch("image/*") }
        click("image_remove") { BackgroundImage.remove(requireContext()); toast(R.string.removed) }
        findPreference<Preference>("adaptive_color")?.setOnPreferenceChangeListener { _, value ->
            if (value == true) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { toast(R.string.adaptive_old_android); return@setOnPreferenceChangeListener false }
                if (!serviceEnabled()) {
                    AlertDialog.Builder(requireContext()).setTitle(R.string.adaptive_dialog_title).setMessage(R.string.adaptive_dialog_text)
                        .setPositiveButton(R.string.adaptive_open) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }
            true
        }
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(requireContext(), ScreenColorService::class.java).flattenToString()
        return Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any { it.equals(me, true) } == true
    }

    private fun showAccentDialog() {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val grid = GridLayout(ctx).apply { columnCount = 5; setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), 0) }
        val dialog = AlertDialog.Builder(ctx).setTitle(R.string.accent_dialog_title).setView(grid)
            .setNeutralButton(R.string.accent_custom, null).setNegativeButton(R.string.accent_reset, null).create()
        Accents.metro.forEachIndexed { i, color ->
            val dot = android.view.View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                setOnClickListener {
                    app.prefs.sp.edit().putInt(K.ACCENT_INDEX, i).putInt(K.CUSTOM_ACCENT, 0).putBoolean(K.DYNAMIC_COLOR, false).apply()
                    dialog.dismiss()
                }
            }
            grid.addView(dot, GridLayout.LayoutParams().apply { width = (44 * d).toInt(); height = (44 * d).toInt(); setMargins((5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt()) })
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val input = EditText(ctx).apply { hint = getString(R.string.accent_custom_hint); setSingleLine() }
                AlertDialog.Builder(ctx).setTitle(R.string.accent_custom).setView(input)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val parsed = runCatching { Color.parseColor(input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull()
                        if (parsed == null) toast(R.string.accent_invalid) else {
                            app.prefs.sp.edit().putInt(K.CUSTOM_ACCENT, parsed or 0xFF000000.toInt()).apply(); dialog.dismiss()
                        }
                    }.setNegativeButton(R.string.cancel, null).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                app.prefs.sp.edit().putInt(K.CUSTOM_ACCENT, 0).putBoolean(K.DYNAMIC_COLOR, true).apply(); dialog.dismiss()
            }
        }
        dialog.show()
    }
}

class SizeFragment : BaseFragment(R.xml.prefs_size, R.string.screen_size) { override fun setup() {} }

class TypingFragment : BaseFragment(R.xml.prefs_typing, R.string.screen_typing) { override fun setup() {} }

class SuggestionsFragment : BaseFragment(R.xml.prefs_suggestions, R.string.screen_suggestions) {
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        background({
            runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                    for (id in app.prefs.languages) {
                        w.append("# ").append(id).append('\n')
                        app.engine(id).user.write(w)
                    }
                }
            }.isSuccess
        }) { ok -> toast(if (ok) R.string.export_done else R.string.import_failed) }
    }

    override fun setup() {
        click("export_learned") { export.launch("metrowin-keyboard-learned.txt") }
        click("clear_learned") {
            AlertDialog.Builder(requireContext()).setMessage(R.string.clear_learned_confirm)
                .setPositiveButton(R.string.ok) { _, _ -> app.clearLearned(); toast(R.string.cleared) }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }
}

class FeedbackFragment : BaseFragment(R.xml.prefs_feedback, R.string.screen_feedback) {
    private val pickSound = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        background({
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                require(bytes.size in 1..(1024 * 1024))
                java.io.File(ctx.filesDir, "custom_sound").writeBytes(bytes)
            }.isSuccess
        }) { ok ->
            if (ok) { app.prefs.putString(K.SOUND_MODE, "custom"); preferenceScreen.findPreference<androidx.preference.ListPreference>(K.SOUND_MODE)?.value = "custom"; toast(R.string.sound_saved) } else toast(R.string.import_failed)
        }
    }

    override fun setup() { click("sound_pick") { pickSound.launch(arrayOf("audio/*")) } }
}

class ClipboardFragment : BaseFragment(R.xml.prefs_clipboard, R.string.screen_clipboard) {
    override fun setup() {
        findPreference<Preference>("clipboard_max")?.setOnPreferenceChangeListener { _, _ -> view?.post { app.clipboard.enforceLimit() }; true }
        click("clipboard_clear") { app.clipboard.clear(includePinned = false); toast(R.string.cleared) }
    }
}

class GifFragment : BaseFragment(R.xml.prefs_gif, R.string.screen_gif) {
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        app.prefs.putString(K.GIF_FOLDER, uri.toString())
        toast(R.string.gif_folder_saved)
    }

    override fun setup() {
        click("gif_folder_pick") { pickFolder.launch(null) }
        findPreference<Preference>("gif_source")?.setOnPreferenceChangeListener { _, v -> showFor(v as String); true }
        showFor(app.prefs.gifSource)
    }

    /** Only the settings the chosen source uses are shown. */
    private fun showFor(source: String) {
        val online = source == "giphy" || source == "tenor" || source == "custom"
        findPreference<Preference>("gif_api_key")?.isVisible = online
        findPreference<Preference>("gif_folder_pick")?.isVisible = source == "folder"
        listOf("gif_custom_url", "gif_custom_list", "gif_custom_preview", "gif_custom_full").forEach { findPreference<Preference>(it)?.isVisible = source == "custom" }
        findPreference<Preference>("gif_link_fallback")?.isVisible = online
    }
}

class VoiceFragment : BaseFragment(R.xml.prefs_voice, R.string.screen_voice) {
    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    override fun setup() { click("voice_permission") { ask.launch(Manifest.permission.RECORD_AUDIO) } }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        val ok = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        findPreference<Preference>("voice_permission")?.summary = if (ok) getString(R.string.setup_enabled) else getString(R.string.pref_voice_permission_summary)
    }
}

class LauncherFragment : BaseFragment(R.xml.prefs_launcher, R.string.screen_launcher) {
    private val importTheme = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { requireContext().contentResolver.openInputStream(uri)!!.use { it.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
        val look = text?.let { LauncherLink.parseThemeFile(it) }
        if (look != null) { LauncherLink.apply(app.prefs, look); toast(R.string.theme_imported) } else toast(R.string.theme_invalid)
    }

    override fun setup() {
        click("launcher_import_theme") { importTheme.launch(arrayOf("*/*")) }
        click("launcher_open") { LauncherLink.launchIntent(requireContext())?.let { startActivity(it) } }
    }

    override fun onResume() {
        super.onResume()
        val installed = LauncherLink.isInstalled(requireContext())
        findPreference<Preference>("launcher_status")?.summary = getString(if (installed) R.string.launcher_found else R.string.launcher_missing)
        findPreference<Preference>("launcher_open")?.isEnabled = installed
    }
}

class AboutFragment : BaseFragment(R.xml.prefs_about, R.string.screen_about) {
    override fun setup() {
        val v = runCatching { requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName }.getOrNull().orEmpty()
        findPreference<Preference>("about_version")?.summary = v
    }
}

class TranslateFragment : BaseFragment(R.xml.prefs_translate, R.string.screen_translate) {
    private val translator by lazy { com.metrowin.keyboard.translate.Translator(app.prefs) }

    override fun setup() {
        findPreference<Preference>("translate_provider")?.setOnPreferenceChangeListener { _, v ->
            showFor(v as String); view?.post { checkUsage() }; true
        }
        findPreference<Preference>("translate_key")?.setOnPreferenceChangeListener { _, _ -> view?.post { checkUsage() }; true }
        click("translate_usage") { checkUsage() }
        showFor(app.prefs.translateProvider)
    }

    override fun onResume() { super.onResume(); checkUsage() }

    private fun showFor(code: String) {
        val p = com.metrowin.keyboard.translate.Provider.from(code)
        findPreference<Preference>("translate_key")?.isVisible = p.needsKey || p == com.metrowin.keyboard.translate.Provider.LIBRE
        findPreference<Preference>("translate_url")?.isVisible = p.needsUrl
        // Only DeepL tells how much of the plan is left.
        findPreference<Preference>("translate_usage")?.isVisible = com.metrowin.keyboard.translate.Providers.usageRequest(p, "x") != null
    }

    /** Asks the service how much of the free plan is left and shows it. Only DeepL offers this. */
    private fun checkUsage() {
        val pref = findPreference<Preference>("translate_usage") ?: return
        if (!pref.isVisible) return
        if (app.prefs.translateKey.isBlank()) { pref.summary = getString(R.string.pref_translate_usage_summary); return }
        pref.summary = getString(R.string.usage_checking)
        translator.usage { r ->
            if (!isAdded || r == null) return@usage
            val nf = java.text.NumberFormat.getIntegerInstance()
            pref.summary = r.fold(
                onSuccess = { u ->
                    if (u.limit > 0) getString(R.string.usage_result, nf.format(u.used), nf.format(u.limit), nf.format(u.remaining))
                    else getString(R.string.usage_unlimited, nf.format(u.used))
                },
                onFailure = { e -> getString(if (e.message?.contains("403") == true) R.string.usage_bad_key else R.string.usage_error) },
            )
        }
    }

    override fun onDestroyView() { translator.shutdown(); super.onDestroyView() }
}
