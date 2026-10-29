# DDKaraoke

A karaoke app for Android TV and Google TV. The TV shows a QR code, and everyone scans it with their phone to pick songs, cheer, send comments and see the queue. No app is needed on the phones, and no separate server is needed: everything runs on the TV box.

Made by DoughDaddy Karaoke.

## Get the app (easy way)

1. On your TV, install the free **Downloader** app from the TV's app store.
2. Open Downloader and type this address: `https://github.com/kingarcher-cos/ddkaraoke/releases/latest/download/DDKaraoke.apk`
3. Allow the install when the TV asks, then press Install.
4. Open DDKaraoke and press **I understand**.

You can also download the file from the **Releases** page of this repository and install it however you like.

Needs Android 7.0 or newer and a recent Android System WebView. Tested on an NVIDIA Shield. Other TVs, Fire TV, tablets and phones used as the screen have not been tested yet.

## What it does

- Queue with fair turns, play next, drag to reorder, favourites, history and rankings.
- Songs from YouTube and from song databases the host adds, downloaded to the TV box as they are needed.
- Vocal toggle (two styles), key change, scrubber, skip, score screen.
- Interact: sound board, overlay clips, scrolling comments.
- Host PIN, per party sessions, settings that are easy to use with a TV remote.

## Disclaimer

This app does not come with any karaoke songs or videos. Content comes from third-party sources the host chooses, such as YouTube or song databases. All content rights remain with their respective owners. The host is responsible for the sources they choose and how the content is used, including following each source's terms and applicable copyright laws. Anything downloaded stays on the host's own device.

The app is provided as is. Content availability and functionality may change.

This app was built from PiKaraoke and has been manually and heavily customised. Claude, an AI assistant, was mainly used to assist porting the app to Android TV and add features such as Interact, the vocal toggle, and the settings screens. No generative AI images are used in the app's own artwork.

## Privacy note

The app asks the free service api.country.is which country your network is in, only to decide whether to show a small local food suggestion (Singapore only). That service sees the box's public internet address. Nothing else is sent. If the check fails, the TV's time zone and region are used instead.

## Build it yourself

You need JDK 17 and the Android SDK (platform 34, build tools 34.0.0).

```
cd app-project
./gradlew assembleDebug
```

The app file appears in `app-project/app/build/outputs/apk/debug/`. A signed release build needs your own key: put it in `app-project/release-signing/keystore.properties` (this folder is ignored by git and must never be shared).

## Licence

GPL-3.0, see `LICENSE`. Parts come from other projects, see `NOTICE.md`.
