# Third-party code

- The streaming OpenAI client, PCM conversion, vocabulary helpers, and cleanup
  fidelity guard in `src/vendor/` originate from [Ramblr](https://github.com/trevornk/ramblr)
  and its local development contributions. They are provided under GPL-3.0.
  Application-specific logging types were removed from the portable client
  lifecycle file, and the PCM default sample rate was made self-contained.
- The previously developed microphone integration was extracted from the
  personal PixelBoard work into this independent project. No PixelBoard updater,
  visual modifications, or Google feature-unlocking code is incorporated.
- Gboard is proprietary Google software. Users provide their own APKs; its source
  and binaries are not part of this repository.
- Build/runtime dependencies include OkHttp and Okio (Square, Apache-2.0), Kotlin
  (JetBrains, Apache-2.0), ASM (OW2, BSD-3-Clause), and Apktool (Apache-2.0).
  Their pinned artifact URLs and hashes are listed in `dependencies.json`.
