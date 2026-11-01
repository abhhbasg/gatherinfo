# Working with MetroWin Launcher

The keyboard (`com.metrowin.keyboard`) and the launcher (`com.metrowin.launcher`) are separate apps, so they share no files or settings. They meet in four places.

## 1. The keyboard knows when it is inside the launcher

When the editor belongs to `com.metrowin.launcher` (the search box of the app list, the settings search), the keyboard turns off completions, automatic correction, next-word prediction and learning. A search for "gm" is not a sentence, and what is typed there never becomes a "learned" word. Digits, accents, swipe typing and the rest work as usual.

## 2. Same looks, same colours

The keyboard's three looks are the launcher's: `classic`, `glass`, `droid`; and its 20 accent colours come in the same order as the launcher's `MetroAccents`.

## 3. Import the launcher's theme file

*Keyboard settings → MetroWin Launcher → Import a launcher theme file…* reads a `metrowin-theme` file (version 1) exported by the launcher. Only `themeStyle`, `themeMode`, `accentIndex` and `customAccent` are used; anything else in the file is ignored.

## 4. Live sync by broadcast (needs a few lines in the launcher)

The keyboard has an exported receiver for the action `com.metrowin.keyboard.action.APPLY_THEME`. When the launcher's theme changes it can send:

```kotlin
context.sendBroadcast(
    Intent("com.metrowin.keyboard.action.APPLY_THEME")
        .setPackage("com.metrowin.keyboard")
        .putExtra("style", settings.themeStyle.code)        // classic | glass | droid
        .putExtra("mode", settings.themeMode.code)          // dark | light | system
        .putExtra("accent_index", settings.accentIndex)     // 0..19, when no custom colour
        .putExtra("accent", settings.customAccent)          // ARGB int, only when there is a custom colour
)
```

Values are checked against known sets; the receiver changes colours and style only, and only while "Follow the launcher's look" is on (the default). The launcher needs a `<queries><package android:name="com.metrowin.keyboard" /></queries>` entry to target it on Android 11+. This part is not done in the launcher yet: the keyboard side is ready and tested.
