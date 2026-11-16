<h1 align="center">
  <img src="cover.png" width="960" alt="AndroidMacBridge — общий буфер и передача файлов между Android и Mac">
</h1>

<p align="center"><strong>Скопируй здесь, вставь там.</strong> Отправляй файлы в обе стороны.</p>

<p align="center">
  <a href="https://github.com/Seferaki/AndroidMacBridge/releases/download/v1.0.0/AndroidMacBridge-1.0.0-Android.apk"><img src="download-android.png" width="216" alt="Скачать APK для Android 10 и новее"></a>
  &nbsp;
  <a href="https://github.com/Seferaki/AndroidMacBridge/releases/download/v1.0.0/AndroidMacBridge-1.0.0-macOS.zip"><img src="download-macos.png" width="216" alt="Скачать приложение для macOS 13 и новее"></a>
</p>

<p align="center">
  <a href="https://github.com/Seferaki/AndroidMacBridge/releases/tag/v1.0.0">Выпуск 1.0.0</a>
  · <a href="#установка">Установка</a>
  · <a href="#сборка-из-исходного-кода">Исходный код</a>
  · <a href="https://github.com/Seferaki/AndroidMacBridge/releases">Все версии</a>
</p>

<p align="center">Если проект оказался полезен — поставьте ⭐ этому репозиторию. Это помогает ему развиваться.</p>

---

## Что умеет AndroidMacBridge

- **Общий буфер.** Текст, ссылки и изображения передаются между Android и Mac.
- **Файлы в обе стороны.** На телефоне отправляй файл через «Поделиться»; на Mac — через меню Finder, перетаскивание или приложение.
- **Wi‑Fi или Bluetooth.** Устройства работают в одной локальной сети либо напрямую по Bluetooth, когда подключены к разным сетям.
- **Раздельная история.** История буфера и передач сохраняется отдельно для каждой пары устройств.
- **Передача без облака.** Устройства обмениваются данными напрямую по защищённому соединению.

Фоновое копирование проверено на Galaxy S25 и Redmi 15, в том числе в Telegram, WhatsApp и заметках. Совместимость и фоновые ограничения могут зависеть от модели Android и настроек энергосбережения.

## Установка

1. **Android.** Скачай APK и открой его. Если Android спросит, разреши установку из приложения, в котором открыл файл.
2. **Mac.** Распакуй ZIP и перенеси **AndroidMacBridge.app** в «Программы». При первом запуске разреши доступ к локальной сети.
3. **Сопряжение.** Подключи устройства к одной Wi‑Fi сети, отсканируй QR-код с экрана Mac и подтверди запрос на Mac.
4. **Общий буфер и Bluetooth.** По желанию включи нужную функцию в настройках обоих устройств. Bluetooth нужен, если устройства находятся в разных сетях.

<details>
<summary><strong>Если macOS блокирует первый запуск</strong></summary>

Приложение не нотариально заверено Apple. После попытки запуска открой **Системные настройки → Конфиденциальность и безопасность → Всё равно открыть**. [Инструкция Apple](https://support.apple.com/ru-ru/102445)

</details>

<details>
<summary><strong>Если Android не устанавливает APK поверх тестовой версии</strong></summary>

У опубликованного APK может отличаться ключ подписи. Удали тестовую версию и установи APK со страницы релизов. При удалении приложения сбросятся его сопряжения, настройки и локальная история.

</details>

## Сборка из исходного кода

### Android

- Установи JDK 17 и Android SDK Platform 37, прими лицензию платформы.
- Открой каталог `android/` в Android Studio или собери debug APK из терминала:

  ```sh
  cd android
  ./gradlew assembleDebug
  ```

- Результат: `android/app/build/outputs/apk/debug/app-debug.apk`.

### macOS

- Нужны Xcode и его инструменты командной строки.
- Из корня проекта запусти:

  ```sh
  ./scripts/build-macos-app.sh
  ```

- Приложение появится в `dist/AndroidMacBridge.app`. Для универсальной сборки под Apple Silicon и Intel используй `BRIDGE_MAC_UNIVERSAL=1 ./scripts/build-macos-app.sh`.

Ключи подписи и файлы `local.properties` храни только у себя. Не добавляй их в репозиторий.

## Структура проекта

- `android/` — приложение Android, клавиатура, фоновый буфер, Bluetooth и тесты.
- `macos/` — приложение Mac, сетевой протокол и расширение «Поделиться» для Finder.
- `scripts/` — сборка приложения Mac и подготовка Android SDK.

## Обратная связь

Нашёл ошибку или хочешь предложить улучшение? [Создай issue](https://github.com/Seferaki/AndroidMacBridge/issues/new). Не прикладывай содержимое личного буфера обмена, QR-коды сопряжения, токены или ключи подписи.

## Лицензия

Исходный код и оригинальные материалы проекта распространяются по лицензии [MIT](LICENSE). Словари и сторонние зависимости сохраняют собственные лицензии; см. [сторонние лицензии](THIRD_PARTY_NOTICES.md).
