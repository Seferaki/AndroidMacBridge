# App languages

AndroidMacBridge provides an in-app language selector on Android and macOS. English is selected on first launch; each device remembers its own choice.

The selector offers 50 languages: English, Russian, Spanish, French, German, Italian, Brazilian Portuguese, Turkish, Polish, Ukrainian, Simplified Chinese, Japanese, Korean, Arabic, Hindi, Indonesian, Dutch, Danish, Swedish, Norwegian Bokmål, Finnish, Greek, Czech, Slovak, Hungarian, Romanian, Bulgarian, Croatian, Serbian, Slovenian, Estonian, Latvian, Lithuanian, Hebrew, Thai, Vietnamese, Malay, Bengali, Tamil, Telugu, Urdu, Persian, Swahili, Filipino, Catalan, Basque, Galician, Icelandic, Bosnian, and Macedonian.

The main controls have translations for the most common languages. Other interface text falls back to English where a translation is available; some operating-system and transfer status messages may still appear in Russian. Language options are not intended to imply that every message has been translated.

## Localization files

- `shared/localization/ui-localization.json` is the shared source catalog used by Android and macOS.
- Android loads this catalog from its assets and applies the selected locale to the app activity.
- `scripts/generate-localization-resources.py` generates macOS `.lproj/Localizable.strings` files from the shared catalog. The Mac app build script runs the generator before packaging.

To add or revise a translation, update the shared catalog and regenerate the macOS resources with:

```sh
python3 scripts/generate-localization-resources.py
```
