Fixes from the full review of AniBox, the phone app and CastApp TV.

- Episodes should play now: the app's private address for the player is 127.0.0.1 (Android had given the IPv6 one, which mpv wasn't told about)
- The player has its own connection to the Shield, so a sync in the background can't cut the stream
- A stream that drops is read again; if the player still stops early, it reopens at the same place (twice) before showing an error, and your place is kept
- Opening an episode that never plays no longer moves its saved place
- Un-marking an episode by hand now reaches the PC and your other devices
- Posters that failed to load are tried again after two minutes
- File names that end in a space play
