# Discord activity ("Listening to StarrySound")

Shows the playing song on your Discord profile like Spotify does, through the official
[Discord Social SDK](https://discord.com/developers/social-sdk). The SDK is not in this repo (it is
downloaded from Discord under their terms). Without it the app builds and runs exactly as before, and
Settings > Online music > Discord says the SDK is missing.

## Setup

1. Discord Developer Portal > New Application. Copy its **Application ID**.
   Name the application `StarrySound` (the name Discord shows after "Listening to").
2. Rich Presence > Art Assets: upload an image with the key `starrysound` (shown as the large image).
3. Download the Social SDK for Android from the portal and unpack it into `app/src/main/cpp/discord/sdk/`, so that
   - `sdk/**/discordpp.h` exists, and
   - `sdk/lib/<abi>/libdiscord_partner_sdk.so` exists for `arm64-v8a` and `armeabi-v7a`
     (`sdk/<abi>/` and `sdk/libs/<abi>/` also work).
   If the package also contains a `.jar`, copy it to `app/libs/`.
4. Build (the Android NDK + CMake must be installed). Gradle only builds the native part when `sdk/` exists.
5. In the app: Settings > Online music > Discord: switch on and paste the Application ID.
   The Discord app must be installed and signed in on the phone.

## Notes

- Updates are throttled to one per 2 s, pausing / stopping / Private mode clears the activity.
- The bridge (`starry_discord.cpp`) was written against the public `discordpp.h` API (Activity, ActivityTimestamps,
  ActivityAssets, `Client::SetApplicationId`, `UpdateRichPresence`, `ClearRichPresence`, `RunCallbacks`). If your SDK version
  needs extra Android setup (for example handing it the JavaVM / Context), add it in `nativeInit`.
