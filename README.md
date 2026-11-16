<h1 align="center">
  <img src="cover.png" width="960" alt="AndroidMacBridge — clipboard and file sharing between Android and Mac">
</h1>

<p align="center"><strong>Copy here. Paste there.</strong> Share files both ways.</p>

<p align="center">
  <a href="https://github.com/Seferaki/AndroidMacBridge/releases/download/v1.0.0/AndroidMacBridge-1.0.0-Android.apk"><img src="download-android.png" width="216" alt="Download for Android 10 and later"></a>
  &nbsp;
  <a href="https://github.com/Seferaki/AndroidMacBridge/releases/download/v1.0.0/AndroidMacBridge-1.0.0-macOS.zip"><img src="download-macos.png" width="216" alt="Download for macOS 13 and later"></a>
</p>

<p align="center">
  <a href="https://github.com/Seferaki/AndroidMacBridge/releases/tag/v1.0.0">Release 1.0.0</a>
  · <a href="#installation">Installation</a>
  · <a href="#building-from-source">Build from source</a>
  · <a href="https://github.com/Seferaki/AndroidMacBridge/releases">All releases</a>
</p>

<p align="center">If AndroidMacBridge is useful to you, please give this repository a ⭐ star. It helps the project grow.</p>

---

## What it does

- **Shared clipboard.** Sync text, links, and images between Android and Mac.
- **Two-way file transfer.** Share files from Android; send them from Finder, drag and drop, or the Mac app.
- **Wi-Fi or Bluetooth.** Use the same local network or connect directly over Bluetooth when the devices are on different networks.
- **Separate device-pair history.** Clipboard entries and transfers are stored separately for each Android–Mac pair.
- **No cloud relay.** Devices communicate directly over an encrypted connection.
- **50 language choices.** English is selected by default; other choices use available translations and fall back to English. See [language support](docs/LOCALIZATION.md).

Background clipboard sync has been confirmed on Galaxy S25 and Redmi 15, including Telegram, WhatsApp, and Notes. Android background behavior can vary by device and battery settings.

## Installation

1. **Android:** Download and open the APK. If prompted, allow installs from the app used to open the file.
2. **Mac:** Unzip the download and move **AndroidMacBridge.app** to Applications. Allow local network access when macOS asks.
3. **Pair the devices:** Connect them to the same Wi-Fi network, scan the QR code shown on Mac, and approve the request on Mac.
4. **Clipboard and Bluetooth:** Enable these features in settings on both devices as needed. Bluetooth is useful when the devices are on different networks.

<details>
<summary><strong>If macOS blocks the first launch</strong></summary>

The Mac app is not notarized by Apple. After trying to open it, go to **System Settings → Privacy & Security → Open Anyway**. See [Apple’s instructions](https://support.apple.com/en-us/102445).

</details>

<details>
<summary><strong>If Android will not install over a test build</strong></summary>

The published APK may use a different signing key. Uninstall the test build before installing the release. Uninstalling clears the app’s pairings, settings, and local history.

</details>

## Building from source

### Android

- Install JDK 17 and Android SDK Platform 37, then accept the platform license.
- Open `android/` in Android Studio or build a debug APK from a terminal:

  ```sh
  cd android
  ./gradlew assembleDebug
  ```

- Output: `android/app/build/outputs/apk/debug/app-debug.apk`.

### macOS

- Xcode and its command-line tools are required.
- From the project root, run:

  ```sh
  ./scripts/build-macos-app.sh
  ```

- The app is created at `dist/AndroidMacBridge.app`. For a universal Apple Silicon and Intel build, run `BRIDGE_MAC_UNIVERSAL=1 ./scripts/build-macos-app.sh`.

Keep signing keys and `local.properties` on your own machine. Never commit them.

## Project layout

- `android/` — Android app, keyboard, clipboard service, Bluetooth transport, and tests.
- `macos/` — Mac app, network protocol, and Finder Share extension.
- `scripts/` — Mac app build and Android SDK setup scripts.

## Feedback

Found a bug or have an idea? [Open an issue](https://github.com/Seferaki/AndroidMacBridge/issues/new). Please do not attach personal clipboard contents, pairing QR codes, tokens, or signing keys.

## License

The project source code and original materials are licensed under the [MIT License](LICENSE). Bundled word lists and third-party dependencies retain their own licenses; see [Third-Party Notices](THIRD_PARTY_NOTICES.md).
