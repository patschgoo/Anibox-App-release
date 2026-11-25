AniBox for Android 0.2.1 (test version)

- **Send debug info**: Settings → "Something not working?" → Send debug info, or the button in the player's error box. It makes one text file (phone, app settings without passwords or user names, library and poster counts, and the app's log with what the shares, the player and mpv said) and opens Android's share sheet, so you can send it by mail, Messenger or Drive.
- The player's error box now names mpv's error and its last warning. When a file fails, the player tries once more with safer video and sound output and software decoding.
- Coming back from the player no longer downloads the whole library again: the sync only reads library.json and the PC's progress when they changed.
- Missing posters: update AniBox on the PC to 1.17.1 and press Settings → Phone app → Write now once; it now writes posters for shows that had none.
