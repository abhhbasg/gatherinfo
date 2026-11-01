# Contributing

Bug reports and ideas are welcome in the issue tracker, in English or Italian. For a bug, say the phone, the Android version and the keyboard version (*Settings → Privacy and about*). Please open an issue before a big pull request.

```sh
./gradlew testDebugUnitTest   # the engine, the keyboard and the tutorial, on the JVM
./gradlew assembleDebug
```

## Adding a feature: the checklist

1. Write it so the settings can switch it off and nothing is sent anywhere unless the person asked for it.
2. Put its strings in `values/strings.xml` **and** `values-it/strings.xml` (a test fails if a tutorial page misses a language).
3. **Add a tutorial page** in `tutorial/Tutorial.kt`: the next `VERSION` in `since`, the two strings `tut_<id>_title` and `tut_<id>_body`, and raise `VERSION`. People who finished the tutorial before see the new pages marked "New".
4. If it adds a setting, add the key to `Prefs.kt`, the screen in `res/xml/prefs_*.xml` and, if the screen is new, a fragment in `settings/Fragments.kt`.
5. Add a line to `CHANGELOG.md` and, for a release, `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
6. Tests: the text logic is tested through `ImeTest` (a real input method wired to a text box), the engine through `EngineTest`.

## Style

Plain Kotlin, no extra dependencies unless there is no other way (the keyboard has few on purpose). Comments say why, not what. Nothing that sends data to a third party may run without the person's action.

## Adding a language

See [docs/LAYOUTS.md](docs/LAYOUTS.md): a layout is a small text file. Dictionaries are word lists; send the layout, not the dictionary, unless its licence allows redistribution.
