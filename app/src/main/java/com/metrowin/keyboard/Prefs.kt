package com.metrowin.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.metrowin.keyboard.theme.Accents
import java.util.Locale

/** The keys of the settings; they are the same strings as in res/xml/prefs_*.xml. */
object K {
    const val LANGUAGES = "languages"
    const val CURRENT_LANGUAGE = "current_language"

    const val THEME_STYLE = "theme_style"
    const val THEME_MODE = "theme_mode"
    const val ACCENT_INDEX = "accent_index"
    const val CUSTOM_ACCENT = "custom_accent"          // ARGB int, 0 = off
    const val DYNAMIC_COLOR = "dynamic_color"
    const val IMAGE_BACKGROUND = "image_background"
    const val IMAGE_DIM = "image_dim"
    const val ADAPTIVE_COLOR = "adaptive_color"
    const val OLED_BLACK = "oled_black"
    const val LAUNCHER_SYNC = "launcher_sync"

    const val KEY_HEIGHT = "key_height"
    const val KEY_HEIGHT_LAND = "key_height_land"
    const val KEY_GAP = "key_gap"
    const val KEYBOARD_WIDTH = "keyboard_width"
    const val KEYBOARD_ALIGN = "keyboard_align"
    const val NUMBER_ROW = "number_row"
    const val KEY_HINTS = "key_hints"

    const val DOUBLE_SPACE = "double_space_period"
    const val LONG_PRESS_SYMBOLS = "long_press_symbols"
    const val LONG_PRESS_MS = "long_press_ms"
    const val AUTO_CAPS = "auto_caps"
    const val SPACE_CURSOR = "space_cursor"

    const val SOUND_MODE = "sound_mode"
    const val SOUND_VOLUME = "sound_volume"
    const val VIBRATION = "vibration"
    const val VIBRATION_INTENSITY = "vibration_intensity"

    const val SUGGESTIONS = "suggestions"
    const val AUTOCORRECT = "autocorrect"
    const val NEXT_WORD = "next_word"
    const val PHRASES = "phrase_completion"
    const val BLOCK_OFFENSIVE = "block_offensive"
    const val CUSTOM_BLOCKED = "custom_blocked"
    const val LEARN = "learn_personal"
    const val SWIPE = "swipe_typing"

    const val CLIPBOARD = "clipboard"
    const val CLIPBOARD_MAX = "clipboard_max"
    const val CLIPBOARD_IMAGES = "clipboard_images"

    const val GIF_SOURCE = "gif_source"
    const val GIF_API_KEY = "gif_api_key"
    const val GIF_FOLDER = "gif_folder"
    const val GIF_CUSTOM_URL = "gif_custom_url"
    const val GIF_CUSTOM_LIST = "gif_custom_list"
    const val GIF_CUSTOM_PREVIEW = "gif_custom_preview"
    const val GIF_CUSTOM_FULL = "gif_custom_full"
    const val GIF_LINK_FALLBACK = "gif_link_fallback"

    const val VOICE_CONTINUOUS = "voice_continuous"
    const val VOICE_OFFLINE = "voice_offline"

    const val EMOJI_RECENT = "emoji_recent"
    const val SOUND_FILE = "sound_file_set"
    const val BG_IMAGE_SET = "bg_image_set"
    const val QUICK_TOOL = "quick_tool"
    const val KEY_PREVIEW = "key_preview"
    const val BOTTOM_PADDING = "bottom_padding"
    const val ONBOARDING_DONE = "onboarding_done"
    const val TUTORIAL_SEEN = "tutorial_seen_version"
    const val TRANSLATE_PROVIDER = "translate_provider"
    const val TRANSLATE_KEY = "translate_key"
    const val TRANSLATE_URL = "translate_url"
    const val TRANSLATE_SOURCE = "translate_source"
    const val TRANSLATE_TARGET = "translate_target"
    const val PICTURE_ACCENT = "picture_accent"
    const val USE_PICTURE_ACCENT = "use_picture_accent"
}

/** Typed access to the settings, with the defaults in one place. */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d

    /** Active languages in the order of the globe key; the language of the phone the first time. */
    var languages: List<String>
        get() = sp.getString(K.LANGUAGES, null)?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf(Locale.getDefault().language.lowercase())
        set(v) { sp.edit().putString(K.LANGUAGES, v.joinToString(",")).apply() }

    var currentLanguage: String?
        get() = sp.getString(K.CURRENT_LANGUAGE, null)
        set(v) { sp.edit().putString(K.CURRENT_LANGUAGE, v).apply() }

    val themeStyle get() = s(K.THEME_STYLE, "classic")
    val themeMode get() = s(K.THEME_MODE, "system")
    val accentIndex get() = i(K.ACCENT_INDEX, Accents.DEFAULT_INDEX).coerceIn(0, Accents.metro.size - 1)
    val customAccent get() = i(K.CUSTOM_ACCENT, 0)
    val dynamicColor get() = b(K.DYNAMIC_COLOR, true)
    val imageBackground get() = b(K.IMAGE_BACKGROUND, false)
    val imageDim get() = i(K.IMAGE_DIM, 55)
    val adaptiveColor get() = b(K.ADAPTIVE_COLOR, false)
    val oledBlack get() = b(K.OLED_BLACK, false)
    val usePictureAccent get() = b(K.USE_PICTURE_ACCENT, true)
    val pictureAccent get() = i(K.PICTURE_ACCENT, 0)
    val launcherSync get() = b(K.LAUNCHER_SYNC, true)

    val keyHeight get() = i(K.KEY_HEIGHT, 100)
    val keyHeightLand get() = i(K.KEY_HEIGHT_LAND, 100)
    val keyGap get() = i(K.KEY_GAP, 3)
    val keyboardWidth get() = i(K.KEYBOARD_WIDTH, 100)
    val keyboardAlign get() = s(K.KEYBOARD_ALIGN, "center")
    val numberRow get() = b(K.NUMBER_ROW, false)
    val quickTool get() = s(K.QUICK_TOOL, "clipboard")
    val keyPreview get() = b(K.KEY_PREVIEW, true)
    val bottomPadding get() = i(K.BOTTOM_PADDING, 0)
    val onboardingDone get() = b(K.ONBOARDING_DONE, false)
    var tutorialSeen: Int
        get() = i(K.TUTORIAL_SEEN, 0)
        set(v) { sp.edit().putInt(K.TUTORIAL_SEEN, v).apply() }
    val translateProvider get() = s(K.TRANSLATE_PROVIDER, "google")
    val translateKey get() = s(K.TRANSLATE_KEY, "").trim()
    val translateUrl get() = s(K.TRANSLATE_URL, "").trim()
    var translateSource: String
        get() = s(K.TRANSLATE_SOURCE, "auto")
        set(v) { putString(K.TRANSLATE_SOURCE, v) }
    var translateTarget: String
        get() = sp.getString(K.TRANSLATE_TARGET, null) ?: (languages.map { it.substringBefore('-') }.firstOrNull { it != java.util.Locale.getDefault().language } ?: "en")
        set(v) { putString(K.TRANSLATE_TARGET, v) }
    val keyHints get() = b(K.KEY_HINTS, true)

    val doubleSpace get() = b(K.DOUBLE_SPACE, true)
    val longPressSymbols get() = b(K.LONG_PRESS_SYMBOLS, true)
    val longPressMs get() = i(K.LONG_PRESS_MS, 200)
    val autoCaps get() = b(K.AUTO_CAPS, true)
    val spaceCursor get() = b(K.SPACE_CURSOR, true)

    val soundMode get() = s(K.SOUND_MODE, "metro")
    val soundVolume get() = i(K.SOUND_VOLUME, 60)
    val vibration get() = b(K.VIBRATION, true)
    val vibrationIntensity get() = i(K.VIBRATION_INTENSITY, 50)

    val suggestions get() = b(K.SUGGESTIONS, true)
    val autocorrect get() = b(K.AUTOCORRECT, true)
    val nextWord get() = b(K.NEXT_WORD, true)
    val phrases get() = b(K.PHRASES, true)
    val blockOffensive get() = b(K.BLOCK_OFFENSIVE, true)
    val customBlocked get() = s(K.CUSTOM_BLOCKED, "")
    val learn get() = b(K.LEARN, true)
    val swipe get() = b(K.SWIPE, true)

    val clipboard get() = b(K.CLIPBOARD, true)
    val clipboardMax get() = i(K.CLIPBOARD_MAX, 20).coerceIn(1, 500)
    val clipboardImages get() = b(K.CLIPBOARD_IMAGES, true)

    val gifSource get() = s(K.GIF_SOURCE, "giphy")
    val gifApiKey get() = s(K.GIF_API_KEY, "").trim()
    val gifFolder get() = s(K.GIF_FOLDER, "")
    val gifCustomUrl get() = s(K.GIF_CUSTOM_URL, "").trim()
    val gifCustomList get() = s(K.GIF_CUSTOM_LIST, "data").trim()
    val gifCustomPreview get() = s(K.GIF_CUSTOM_PREVIEW, "images.fixed_width_small.url").trim()
    val gifCustomFull get() = s(K.GIF_CUSTOM_FULL, "images.original.url").trim()
    val gifLinkFallback get() = b(K.GIF_LINK_FALLBACK, true)

    val voiceContinuous get() = b(K.VOICE_CONTINUOUS, false)
    val voiceOffline get() = b(K.VOICE_OFFLINE, false)

    var emojiRecent: List<String>
        get() = sp.getString(K.EMOJI_RECENT, "")!!.split('\u001F').filter { it.isNotEmpty() }
        set(v) { sp.edit().putString(K.EMOJI_RECENT, v.joinToString("\u001F")).apply() }

    fun putString(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun putBoolean(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
}
