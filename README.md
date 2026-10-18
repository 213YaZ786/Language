# Voice

Speech recognition for Android, on the phone. No account, no tracking, no ads.

- Writes what you say with the phone's own recognizer when it has your
  language, else with Whisper (Base or Small), fetched once when you ask.
- Try it in the app: words appear as you speak; Copy, Share, Clear.
- Set it as the phone's voice input in Android's settings.
- Nothing runs in the background. The microphone is open only while you
  listen.

The connections: the speech model (Hugging Face), once, only when you ask,
and a check for a new version on GitHub. Each can be turned off in Settings.

## Credits

- [Transcribro](https://github.com/soupslurpr/Transcribro) by soupslurpr
  (ISC), the model for this app.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) and OpenAI's
  [Whisper](https://github.com/openai/whisper) models (MIT).
- [Silero VAD](https://github.com/snakers4/silero-vad) (MIT), the voice
  detector.
- Android Open Source Project (Apache License 2.0).
- [Kõnele speechutils](https://github.com/Kaljurand/speechutils) (Apache
  License 2.0).
- Icons: Material Icons by Google, Apache License 2.0.

## Licence

MIT, see [LICENSE](LICENSE).

Transcribro's notice:

    ISC License

    Copyright (c) 2024-2025 soupslurpr

    Permission to use, copy, modify, and/or distribute this software for any
    purpose with or without fee is hereby granted, provided that the above
    copyright notice and this permission notice appear in all copies.

    THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
    WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
    MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
    ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
    WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
    ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
    OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
