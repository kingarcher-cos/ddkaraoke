# DDKaraoke

Our chef at Doughbaybee.sg had a few free weeks and ended up hyperfixating on building a home server from his old laptop. Installing karaoke on it started as a joke, but then he realised he could actually make something he'd use.

He didn't want to keep paying for karaoke outside when the places didn't have his songs or the features he wanted. So, he started customising it himself, putting a really dusty IT diploma he hadn't used in years and bare bones knowledge of Visual Studios back to work (further disclaimers below; if anything does not work as intended, please let us know. Apologies in advance 🥺).

It's built on PiKaraoke and has been heavily customised along the way. Now it runs directly on an Android TV (you can also use it on an Android tablet) without needing a separate server, and he's sharing it for free for anyone who wants to try it.

## What it does

The TV shows a QR code, and everyone scans it with their phone to pick songs, cheer and see the queue. No app is needed on the phones.

Needs Android 7.0 or newer and a recent Android System WebView. Tested on an NVIDIA Shield. Other TVs used as the screen have not been tested yet.

## How to install

### Option 1: the Downloader app (easiest)

1. On your TV, open the app store and install the free app called **Downloader**.
2. Open Downloader and type this number in the box: **6729408**
3. Press Go, wait for it to download, then press Install.
4. If the TV asks, allow Downloader to install apps. This is a normal Android safety question.
5. Open DDKaraoke and press **I understand**.

Prefer typing the full address? Use `https://github.com/kingarcher-cos/ddkaraoke/releases/latest/download/DDKaraoke.apk`

### Option 2: a USB stick

1. On your computer, go to the [Releases page](https://github.com/kingarcher-cos/ddkaraoke/releases/latest) and download **DDKaraoke.apk**.
2. Copy it onto a USB stick and plug the stick into your TV.
3. On the TV, open a file manager app (many TVs have one, or install a free one from the app store), find the file and open it.
4. Press Install. If the TV asks, allow installs from this file manager.
5. Open DDKaraoke and press **I understand**.

## Disclaimer

This app was built from PiKaraoke and has been manually and heavily customised.

As an important disclaimer, Claude, an AI assistant, was mainly used to assist porting the app to Android TV and add features such as Interact, the vocal toggle, and the settings screens.

No generative AI images are used in the app's own artwork. We refuse to use any. The button icons come from open icon sets, Fontello and Material Design. Thumbnails in search results come from the sources themselves.

## Usage note

This app does not come with any karaoke songs or videos. Content comes from third-party sources the host chooses, such as YouTube or song databases.

All content rights remain with their respective owners. The host is responsible for the sources they choose and how the content is used, including following each source's terms and applicable copyright laws. Anything downloaded stays on the host's own device.

The app is provided as is. Content availability and functionality may change.

## Credits

- Splash logo: original art by @ichigobunart, edited by kingarcher
- Mascots: loading buddies by @ichigobunart
- Splash video: Retro synthwave vaporwave 80s neon, by agp_studios (Pixabay)
- Background music: Lobby Time, by Kevin MacLeod (incompetech.com), licensed under Creative Commons: By Attribution 4.0 (creativecommons.org/licenses/by/4.0)
- Score sounds: drum roll by Iwan Sounds and DIY, applause by Sandermotions and Amada44 (all CC0, via Wikimedia Commons)

More detail on everything that is used is in [NOTICE.md](NOTICE.md).

## One small privacy note

To decide whether to show a local food suggestion (Singapore only), the app asks a free service, api.country.is, which country your network is in. That service can see your TV's public internet address. Nothing else is sent. If the check fails, the TV's time zone and region are used instead.

## For people who want to build it

You need JDK 17 and the Android SDK (platform 34, build tools 34.0.0).

```
cd app-project
gradle assembleDebug
```

The app file ends up in `app-project/app/build/outputs/apk/debug/`. A signed release build needs your own key, put in `app-project/release-signing/keystore.properties` (that folder is kept out of git and should never be shared).

## Licence

GPL-3.0, see `LICENSE`. Parts come from other projects, see `NOTICE.md`.
