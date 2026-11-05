# Security and data handling

The public project must never contain real API keys, private vocabulary,
recordings, transcripts, signing keys, or Google APK files. Development and CI
tests use synthetic examples and a local mock WebSocket server.

The installed extension stores API configuration encrypted using Android
Keystore. Configuration is private to the application; app backups are disabled.
The setup screen does not repopulate the API-key field with an existing key.

Audio is held in memory during a recording and sent directly to OpenAI. The
cleanup request includes the transcript and configured vocabulary. With message
context enabled (the default), it also includes the complete current editor
message split around the selection. This context is not written to storage or
logs. Password and structured fields are excluded; detected incomplete or oversized
editor snapshots are not sent. Extraction and independent before/after-selection
reads must agree. Android allows host editors to limit these reads, so complete
context depends on the editor exposing its full text. Requests use
HTTPS/WSS and do not follow redirects. No project-maintainer server, update
checker, telemetry service, or account backend is included.

Local diagnostic metrics contain timing, model usage counts, and the reported
service tier. They do not contain transcript text or credentials. OpenAI's
processing and retention policies still apply even with `store: false`.

This is an unofficial modification of a proprietary application, not a complete
security audit of Gboard. The extension and the supplied Google application run
in the same Android application process. Keystore encryption protects stored
configuration; it is not isolation from other code in that process or a rooted
device. Obtain the base APK set from a source you trust.

The patch deliberately supports a specific tested version. Do not broaden the
version check without reviewing the bytecode targets and testing on a device.
Report issues without including keys, transcripts, or private device data.

## Optional Messages integration

The Messages shortcut is an opt-in accessibility service. Its Android capability permits reading window content; its configured event scope and implementation target Google Messages. It reads the visible composer, recorder controls and conversation header only to validate the intended editor. These values are ephemeral and never logged or saved. A short one-use request must still match the conversation, composer, draft and known selection before the keyboard starts dictation.

The service never invokes Send and never identifies the recorder by coordinates alone. Missing, disabled, hidden, ambiguous or unrecognized controls disable the shortcut. The keyboard action invokes the native recorder only after a fresh check. Disabling the service cancels pending requests and removes its overlay. No exported recording command or new server is added.

The service declaration is protected by Android's BIND_ACCESSIBILITY_SERVICE permission. A resource-only split supplies its metadata within the same app; Google's original resource tables and IDs are preserved. All locally installed splits must be kept together.
