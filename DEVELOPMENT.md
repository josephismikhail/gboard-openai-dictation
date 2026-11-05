# Development

Use a feature branch and submit a pull request. Keep Google APKs, test artifacts,
API keys, signing keys, and personal configuration outside the source tree.

## Requirements

- Python 3.10 or later.
- Java 21 or later.
- Android SDK platform 36 and build-tools 36.0.0; ADB for installation.
- Your own installed Gboard 18.5.6.993608717 arm64 APK set.

Set `JAVA_HOME` and `ANDROID_HOME`, or pass explicit `--java` and `--sdk` paths.
The scripts use pinned dependencies from `dependencies.json`, verify SHA-256,
and reuse a matching local Gradle cache when available.

## Test without an API key or Google APK

```sh
python run_tests.py --work ../gboard-openai-private
```

Tests cover package/provider rewriting, class-name preservation, binary-XML
Unicode preservation, manual streaming turn boundaries, audio order and final
result matching, endpoint validation, and cleanup fidelity/vocabulary behavior.
The WebSocket tests use a local mock server and make no OpenAI requests.

## Obtain your installed APK set

With USB debugging authorized, run:

```sh
adb shell pm path com.google.android.inputmethod.latin
```

Pull each returned APK into a private directory named `stock/`. Retain the file
names: `base.apk` and all installed split APKs. Do not commit this directory.

## Build and install

```sh
python build.py --stock ../gboard-openai-private/stock --work ../gboard-openai-private
python install.py --work ../gboard-openai-private
```

The installer grants microphone permission, enables the new keyboard, selects it,
and verifies the selection. This matters after updates: Android may temporarily
fall back to the original keyboard. To install manually on Windows PowerShell:

```powershell
$apks = Get-ChildItem ../gboard-openai-private/output/*.apk | ForEach-Object FullName
adb install-multiple -r $apks
```

Enable **Gboard + OpenAI** in Android's keyboard settings, select it, and grant
microphone permission. Long-press its idle microphone to enter your own API key.
The original Google-signed keyboard remains installed for rollback.

The private work directory contains the local signing key. Keep that key to
install future updates over the same package. Losing it requires uninstalling
the independently signed app, which clears its local configuration.

## Compatibility and review

The patch refuses unrecognized Gboard versions. When supporting another version,
review the input-method hierarchy and narrowly scoped self-signature hook, add
fixtures/tests, and run a device test before changing the allowlist.

Verify that a recording survives silence, stops on the second tap, inserts text
only into the original editor, cancels when the editor is dismissed, and returns
to idle after network errors. Compare the stock keyboard and settings screen to
confirm no unrelated visual or feature changes.

Voice-panel validation: tap the center and edges of the panel to stop, tap again
while processing (must not restart recording), and dismiss the keyboard while
recording/processing (must cancel without inserting into another editor).
Check light/dark themes, full-screen coverage, a flat waveform in silence,
and restoration of the original keyboard height after insertion or an error. JVM tests cover PCM
level measurement and the fast-stop/late-start race. The optional locally signed
Android QA harness provides an empty text field and `panelTest` instrumentation
for touch coverage, accessibility, opacity, teardown, deterministic animation
rendering and window restoration; it needs no API key. Python tests verify that
the insets hook runs at every normal return, preserves other methods and is
idempotent across repeated builds.

```sh
python tools/build_setup.py --work ../gboard-openai-private --sdk "$ANDROID_HOME" --java "$JAVA_HOME"
adb install -r ../gboard-openai-private/qa.apk
adb shell am instrument -w -e action panelTest org.gboardopenai.test/.SetupInstrumentation
adb shell am instrument -w -e action insertionTest org.gboardopenai.test/.SetupInstrumentation
adb shell ime set org.gboardopenai.keyboard/com.android.inputmethod.latin.LatinIME
adb shell am start -S -n org.gboardopenai.test/.VoiceTestActivity
```

Instrumentation may reset the default keyboard, so always restore it afterward.
The insertion checks use Android's actual editable input connection to verify
consecutive dictations, continuing by typing, selected-text replacement (including possessives), reuse
of an existing space, punctuation and structured fields. All examples are
synthetic and no API request is needed.
Remove the temporary harness with `adb uninstall org.gboardopenai.test` when done.

To test full-screen touch routing in the actual IME without a microphone or API
request, start the following command, then use a second terminal to select the
keyboard and open `VoiceTestActivity` as above within ten seconds:

```sh
adb shell am instrument -w -e action windowTest org.gboardopenai.test/.SetupInstrumentation
```

This check only operates on the QA package's editor. It opens the real voice
window with a synthetic recording state, taps above the old keyboard area to
verify the insets hook, cancels during processing, checks the original input
connection and window attributes, tests a resize while microphone discovery is
throttled, then tests Back cancellation. The rendering checks also exercise
mdpi, xhdpi and xxhdpi contexts without changing the phone's display settings. The reflection
used to arrange this synthetic state exists only in the optional QA APK.

The optional full-screen interaction study runs entirely inside the QA app:

```sh
adb shell am start -n org.gboardopenai.test/.FullscreenVoicePreviewActivity
```

It simulates speaking, tap-to-finish, processing and a sample result without
recording audio, calling a model or changing the live keyboard. Its explicit
preview labels distinguish it from real dictation.

Do not add telemetry, an update service, or a hosted proxy. Do not publish a
Google-derived APK in releases. Public CI tests source and mock services only.
