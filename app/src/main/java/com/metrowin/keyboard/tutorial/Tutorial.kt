package com.metrowin.keyboard.tutorial

import androidx.annotation.StringRes
import com.metrowin.keyboard.R
import com.metrowin.keyboard.ui.Icon

/**
 * One page of the tutorial. The same list is shown in the app (first start, "Tutorial" in the settings) and inside the keyboard
 * (the "?" in the toolbox), so a feature is explained once.
 *
 * To add a section when a feature is added: add a page here with the next [VERSION] in [since], add its two strings
 * (`tut_<id>_title`, `tut_<id>_body`) in every strings.xml (default and each language), and bump [VERSION]. People who already finished the
 * tutorial then see the new pages marked as new (TutorialTest checks the strings exist in all languages).
 */
data class TutorialPage(
    val id: String,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val icon: Icon? = null,
    /** The tutorial version that added this page. */
    val since: Int = 1,
)

object Tutorial {
    /** Raise this when a page is added. */
    const val VERSION = 4

    val pages: List<TutorialPage> = listOf(
        TutorialPage("welcome", R.string.tut_welcome_title, R.string.tut_welcome_body),
        TutorialPage("basics", R.string.tut_basics_title, R.string.tut_basics_body),
        TutorialPage("hold", R.string.tut_hold_title, R.string.tut_hold_body),
        TutorialPage("swipe", R.string.tut_swipe_title, R.string.tut_swipe_body),
        TutorialPage("space", R.string.tut_space_title, R.string.tut_space_body),
        TutorialPage("suggestions", R.string.tut_suggestions_title, R.string.tut_suggestions_body),
        TutorialPage("toolbox", R.string.tut_toolbox_title, R.string.tut_toolbox_body, Icon.ELLIPSIS, since = 2),
        TutorialPage("clipboard", R.string.tut_clipboard_title, R.string.tut_clipboard_body, Icon.CLIPBOARD),
        TutorialPage("emoji", R.string.tut_emoji_title, R.string.tut_emoji_body, Icon.EMOJI),
        TutorialPage("smartemoji", R.string.tut_smartemoji_title, R.string.tut_smartemoji_body, Icon.EMOJI, since = 3),
        TutorialPage("gif", R.string.tut_gif_title, R.string.tut_gif_body, Icon.GIF),
        TutorialPage("translate", R.string.tut_translate_title, R.string.tut_translate_body, Icon.TRANSLATE, since = 2),
        TutorialPage("voice", R.string.tut_voice_title, R.string.tut_voice_body, Icon.MIC),
        TutorialPage("looks", R.string.tut_looks_title, R.string.tut_looks_body),
        TutorialPage("size", R.string.tut_size_title, R.string.tut_size_body),
        TutorialPage("smart", R.string.tut_smart_title, R.string.tut_smart_body, since = 4),
        TutorialPage("backup", R.string.tut_backup_title, R.string.tut_backup_body, since = 4),
        TutorialPage("calibration", R.string.tut_calibration_title, R.string.tut_calibration_body, since = 4),
        TutorialPage("update", R.string.tut_update_title, R.string.tut_update_body, since = 4),
        TutorialPage("languages", R.string.tut_languages_title, R.string.tut_languages_body, Icon.GLOBE),
        TutorialPage("privacy", R.string.tut_privacy_title, R.string.tut_privacy_body),
        TutorialPage("launcher", R.string.tut_launcher_title, R.string.tut_launcher_body),
    )

    /** Where to start for someone who has seen the tutorial up to version [seen]: the first page added after it, or the start. */
    fun startIndex(seen: Int): Int = if (seen <= 0) 0 else pages.indexOfFirst { it.since > seen }.takeIf { it >= 0 } ?: 0

    fun hasNew(seen: Int) = seen in 1 until VERSION
}
