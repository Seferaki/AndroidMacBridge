#!/usr/bin/env python3
"""Generate the macOS Localizable.strings catalogs from the shared UI catalog."""

import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
catalog = json.loads((ROOT / "shared/localization/ui-localization.json").read_text(encoding="utf-8"))
english = catalog["en"]
languages = {
    "en": "App language",
    "ru": "Язык приложения",
    "es": "Idioma de la aplicación",
    "fr": "Langue de l’app",
    "de": "App-Sprache",
    "it": "Lingua dell’app",
    "pt-BR": "Idioma do aplicativo",
    "tr": "Uygulama dili",
    "pl": "Język aplikacji",
    "uk": "Мова застосунку",
    "zh-Hans": "应用语言",
    "ja": "アプリの言語",
    "ko": "앱 언어",
    "ar": "لغة التطبيق",
    "hi": "ऐप की भाषा",
    "id": "Bahasa aplikasi",
    "nl": "App-taal",
    "da": "Appsprog",
    "sv": "Appspråk",
    "nb": "Appspråk",
    "fi": "Sovelluksen kieli",
    "el": "Γλώσσα εφαρμογής",
    "cs": "Jazyk aplikace",
    "sk": "Jazyk aplikácie",
    "hu": "Alkalmazás nyelve",
    "ro": "Limba aplicației",
    "bg": "Език на приложението",
    "hr": "Jezik aplikacije",
    "sr": "Језик апликације",
    "sl": "Jezik aplikacije",
    "et": "Rakenduse keel",
    "lv": "Lietotnes valoda",
    "lt": "Programos kalba",
    "he": "שפת היישום",
    "th": "ภาษาของแอป",
    "vi": "Ngôn ngữ ứng dụng",
    "ms": "Bahasa aplikasi",
    "bn": "অ্যাপের ভাষা",
    "ta": "செயலி மொழி",
    "te": "యాప్ భాష",
    "ur": "ایپ کی زبان",
    "fa": "زبان برنامه",
    "sw": "Lugha ya programu",
    "fil": "Wika ng app",
    "ca": "Idioma de l’aplicació",
    "eu": "Aplikazioaren hizkuntza",
    "gl": "Idioma da aplicación",
    "is": "Tungumál forrits",
    "bs": "Jezik aplikacije",
    "mk": "Јазик на апликацијата",
}
fallback_notes = {
    "en": "Some system messages may still appear in Russian.",
    "ru": "Некоторые системные сообщения пока могут отображаться на русском.",
    "es": "Algunos mensajes del sistema pueden seguir apareciendo en ruso.",
    "fr": "Certains messages système peuvent encore s’afficher en russe.",
    "de": "Einige Systemmeldungen können weiterhin auf Russisch erscheinen.",
    "it": "Alcuni messaggi di sistema potrebbero apparire ancora in russo.",
    "pt-BR": "Algumas mensagens do sistema ainda podem aparecer em russo.",
    "tr": "Bazı sistem mesajları hâlâ Rusça görünebilir.",
    "pl": "Niektóre komunikaty systemowe mogą nadal być wyświetlane po rosyjsku.",
    "uk": "Деякі системні повідомлення поки можуть відображатися російською.",
    "zh-Hans": "部分系统消息仍可能以俄语显示。",
    "ja": "一部のシステムメッセージはロシア語で表示される場合があります。",
    "ko": "일부 시스템 메시지는 러시아어로 표시될 수 있습니다.",
    "ar": "قد تظل بعض رسائل النظام معروضة باللغة الروسية.",
    "hi": "कुछ सिस्टम संदेश अभी भी रूसी में दिख सकते हैं।",
    "id": "Beberapa pesan sistem mungkin masih ditampilkan dalam bahasa Rusia.",
    "nl": "Sommige systeemberichten kunnen nog in het Russisch worden weergegeven.",
}
tags = tuple(languages)
resources = ROOT / "macos/Resources"
resources.mkdir(parents=True, exist_ok=True)
expected_directories = {f"{tag}.lproj" for tag in tags}
for existing in resources.glob("*.lproj"):
    if existing.name not in expected_directories:
        shutil.rmtree(existing)


def quoted(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


for tag in tags:
    language = tag.split("-")[0]
    if tag == "ru":
        entries = {key: key for key in english}
    else:
        entries = dict(english)
        entries.update(catalog.get(language, {}))
    entries["App language"] = languages[tag]
    entries["Some system messages may still appear in Russian."] = fallback_notes.get(tag, fallback_notes["en"])
    directory = resources / f"{tag}.lproj"
    directory.mkdir(parents=True, exist_ok=True)
    output = "\n".join(f"{quoted(key)} = {quoted(value)};" for key, value in sorted(entries.items())) + "\n"
    (directory / "Localizable.strings").write_text(output, encoding="utf-8")
