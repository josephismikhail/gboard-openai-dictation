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

Do not add telemetry, an update service, or a hosted proxy. Do not publish a
Google-derived APK in releases. Public CI tests source and mock services only.
