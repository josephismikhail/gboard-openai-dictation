# OpenAI dictation for stock Gboard

An **unofficial, independently maintained patch project** that adds OpenAI voice
dictation and cleanup to a locally built copy of Google's Gboard.

## The problem it solves

Keep Gboard's familiar keyboard, themes, layouts, and normal settings, while using
its microphone as a predictable push-to-talk control:

1. Tap the microphone to start recording.
2. Speak naturally. Pauses do not finish the recording.
3. A full-screen voice view shows a live waveform. Tap anywhere to finish,
   or use the close button to cancel.
4. OpenAI transcribes the audio and cleans up filler words, explicit corrections,
   punctuation, and lists. The result is inserted into the current text field.

In normal text fields, insertion adds a space for continuing by typing or
dictating again, while respecting nearby whitespace and punctuation. Existing
spaces are reused. Email, URL, password and numeric fields keep the exact result.
Only the two characters next to each side of the selection are checked locally
for spacing; this context is not sent to OpenAI.

This project does not add PixelBoard's visual changes, feature-unlocking options,
or update service. It is an independent repository, not a fork of PixelBoard.
It reuses attributed open-source speech-client code where appropriate.

## How it works

Gboard does not offer a supported plug-in for replacing its microphone provider.
The build patches a user-supplied Gboard APK set, adds a small in-process
microphone handler, and produces an independently signed package named
`org.gboardopenai.keyboard`. It installs alongside Google's original Gboard.

The original keyboard code and resources remain in the APK. The patch changes
package/provider identifiers for side-by-side installation, narrowly permits the
new package through the self-signature check, and hooks input-method lifecycle
events. It does not replace the keyboard layout or its theme settings.

Google's Gboard source code and APKs are **not included**. No prebuilt Google-derived
APK is distributed here. Supply your own installed APK set and build locally.
There is no affiliation with or endorsement by Google or OpenAI.

## Local application, cloud processing

The app, configuration, and encrypted API key live on your phone. Your phone
connects **directly to OpenAI**; there is no project-operated server, proxy,
account service, telemetry endpoint, or connection to the repository maintainer.

This is **not offline/on-device AI**: audio and transcript text are sent to OpenAI.
Normal Gboard functionality can still communicate with Google. This project
does not claim to remove or audit all Google networking.

Default pipeline:

- Streaming transcription: `gpt-live-transcribe`, with manual turn completion.
- Transcription fallback: `gpt-transcribe` if the streaming attempt fails.
- Cleanup: `gpt-6.1-sol`, low reasoning effort, requesting the `ultrafast` tier.
- Responses use `store: false`. This does not mean zero retention under every
  OpenAI policy; your OpenAI organization's applicable data terms still apply.

API usage is billed to **your own OpenAI API account**. A ChatGPT subscription
does not automatically authenticate this app. Any linked promotional API credits
are subject to their own eligibility and expiry rules.

## Bring your own API key

After installing and selecting the keyboard, **long-press its idle microphone**
to open **OpenAI dictation** settings. Enter your own OpenAI API key and save it.
Optional vocabulary and cleanup instructions are configured in the same screen.

The key is encrypted with an Android Keystore key in app-private storage. It is
not built into the APK or written to this repository. The key field is masked
and is not repopulated with the stored key. App backups are disabled for this
independently built package. Do not commit keys, recordings, personal vocabulary,
private preferences, signing keys, or your base APKs.

During recording, the voice view fills the screen. Its
waveform reflects the microphone level and settles during silence; silence does
not stop recording. Speech produces large, bright peaks above a dim, flat quiet
line. After you tap the view, the waveform contracts into a large, gently
breathing rounded shape until the result is inserted and the regular keyboard
returns. This animation indicates activity, not estimated progress. The view
follows the keyboard background with a contrasting neutral signal and adds no
routine toasts. Use the close button, Back, or a long press to cancel.
Leaving the input field also cancels and restores
the keyboard. A ten-minute recording limit prevents an accidentally forgotten
microphone from running indefinitely. System-disabled animations are respected.

The full-screen view stays in the keyboard's own window, preserving its connection
to the original text field. It needs no floating-overlay permission or separate
recording activity. System navigation remains available; temporary window and
touch-region changes are restored when dictation ends.

## Development status

Implementation and validation are maintained on feature branches and reviewed
through pull requests. Compatibility is deliberately restricted to tested APK
versions rather than guessing at obfuscated methods in an unknown release.

Current target: **Gboard 18.5.6.993608717, arm64**, including its installed splits.
The on-phone microphone integration is currently validated with the English
Gboard interface; other interface languages have not been validated.
Instructions and test commands are in [DEVELOPMENT.md](DEVELOPMENT.md).

## License and attribution

The patch source is GPL-3.0-only; see [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Google's application, branding,
and resources remain Google's property and are not relicensed by this project.
