# OpenAI dictation for stock Gboard

An **unofficial, independently maintained patch project** that adds OpenAI voice
dictation and cleanup to a locally built copy of Google's Gboard.

## The problem it solves

Keep Gboard's familiar keyboard, themes, layouts, and normal settings, while using
its microphone as a predictable manual start/stop control:

1. Tap the microphone to start recording.
2. Speak naturally. Pauses do not finish the recording.
3. A full-screen voice view shows a live waveform. Tap anywhere to finish,
   or use the close button to cancel.
4. OpenAI transcribes the audio and cleans up filler words, explicit corrections,
   punctuation, and lists. The result is inserted into the current text field.

In normal text fields, cleanup uses the **whole current message** and the selected
insertion/replacement position to fit the new words into context. It returns only
the new text; the surrounding message is not rewritten. Local insertion adds
boundary spaces only where adjacent text needs them, reuses existing spaces, and
does not append a trailing space at the end of the field. Email, URL, password and
numeric fields keep the exact result and are excluded from message-context capture.

Place the cursor to insert new words, or select existing words before dictating
to replace them. Consecutive dictations fit the text on either side of the cursor
without leaving an automatic trailing space.

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

This is **not offline/on-device AI**: audio, transcript text, and (with message
context enabled) the current message are sent to OpenAI.
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

## Getting started

1. Build and install your own Gboard APK set using the prerequisites and commands
   in [DEVELOPMENT.md](DEVELOPMENT.md#build-and-install).
2. Select **Gboard + OpenAI** as your keyboard and grant microphone permission.
3. Long-press the keyboard microphone, enter your own OpenAI API key, and choose
   **Save on this phone**. The settings screen includes a field for trying dictation.

The optional Messages shortcut is enabled separately; the regular keyboard
microphone works without it.

## Bring your own API key

After installing and selecting the keyboard, **long-press its idle microphone**
to open **OpenAI dictation** settings. Enter your own OpenAI API key and save it.
Optional vocabulary and cleanup instructions are configured in the same screen.
Configured vocabulary also preserves its exact capitalization in the inserted
text; these personal spellings stay in your encrypted phone configuration.
**Use the whole message as cleanup context** is enabled by default and can be
turned off there. Context is held only for the current dictation, never saved or
logged. If the editor cannot provide a complete message, cleanup falls back to
the transcript alone. Messages over 200,000 characters are excluded rather than
silently truncated. Capture cross-checks the extracted message against separate
before/after-selection reads; it relies on the host editor exposing its full text.
If the captured message or selection changes before insertion,
the result is not applied to a different target.

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
Both the recording waveform and the processing shape are centered horizontally
and vertically in the full-screen view.
Leaving the input field also cancels and restores
the keyboard. A ten-minute recording limit prevents an accidentally forgotten
microphone from running indefinitely. System-disabled animations are respected.

Opening the full-screen voice view requests temporary audio focus so cooperating
media apps pause their playback. Focus is returned when the view closes, after
cleanup and insertion or cancellation, allowing the player to resume. The keyboard
does not change media volume or send a blanket Play command. Playback behavior is
controlled by the media app: some players may ignore focus changes or require a
manual resume. No additional media or notification access is requested.

The full-screen view stays in the keyboard's own window, preserving its connection
to the original text field. It needs no floating-overlay permission or separate
recording activity. System navigation remains available; temporary window and
touch-region and focus changes are restored when dictation ends. While recording
or processing, the voice window takes focus so the editor's selection handles
cannot float above it; the selected range is preserved for replacement or cancel.

## Optional Google Messages shortcut

Long-press the keyboard microphone to open dictation settings, then choose **Set up Messages shortcut**. Enable **Messages dictation shortcut** in Android accessibility settings. This integration is optional and disabled until you enable it.

In a supported Google Messages conversation with an empty draft, tap the existing gray waveform button to open the full-screen OpenAI dictation flow directly. The keyboard may appear briefly during the handoff. The keyboard's normal microphone also starts dictation; no button positions or icons are swapped. Once Messages replaces its voice-note button with Send, that button is left alone.

Both entry points produce editable text; this shortcut does not create or send
voice-note attachments. Use the keyboard microphone to add dictation to a draft
that already contains text.

Messages renders its own button, including its animation as the keyboard opens and closes. A transparent touch layer changes only its tap action. Taps are checked against the current control and conversation at both press and release; a stale hit area, a drag, or multiple fingers cannot trigger dictation. Messages search and unsupported fields keep normal keyboard dictation.

This is a Messages-specific shortcut layer, not a modification of Google Messages. It never presses Send. It requires the observed English recorder label and identifiable conversation controls; unknown layouts keep their normal behavior. The accessibility service inspects Messages controls, the current draft and conversation header locally to validate the target. It does not save them. The existing OpenAI API key and dictation data handling remain the same. Disable the service in Android accessibility settings to restore the normal button behavior.

## Development status

Implementation and validation are maintained on feature branches and reviewed
through pull requests. Compatibility is deliberately restricted to tested APK
versions rather than guessing at obfuscated methods in an unknown release.

Current target: **Gboard 18.5.6.993608717, arm64**, including its installed splits.
The on-phone microphone integration is currently validated with the English
Gboard interface; other interface languages have not been validated.
Instructions and test commands are in [DEVELOPMENT.md](DEVELOPMENT.md).

Feature PRs document [full-screen dictation (#2)](https://github.com/josephismikhail/gboard-openai-dictation/pull/2),
[contextual cleanup, spacing, and selection handling (#3)](https://github.com/josephismikhail/gboard-openai-dictation/pull/3),
[centered voice animations (#4)](https://github.com/josephismikhail/gboard-openai-dictation/pull/4),
and [the native Messages entry point (#5)](https://github.com/josephismikhail/gboard-openai-dictation/pull/5).

## License and attribution

The patch source is GPL-3.0-only; see [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Google's application, branding,
and resources remain Google's property and are not relicensed by this project.
