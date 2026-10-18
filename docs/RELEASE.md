# Выпуск APK и раздача сотрудникам

Release-APK ходит на `https://fuelapp.ru/api/v1/` и раздаётся по ссылке **https://fuelapp.ru/app.apk**
(Caddy отдаёт файл из `/opt/fuel-app/download/app.apk` на сервере). Сотрудникам удобнее давать
**https://fuelapp.ru** — там страница с кнопкой «Скачать для Android» и инструкцией по установке
(`backend/landing/index.html`, меняется вместе с кодом через `git pull`, перезапуск не нужен).

## Ключ подписи (один раз)
Android ставит обновление поверх установленного приложения, только если оно подписано **тем же ключом**.
Потерять ключ = каждому сотруднику удалять приложение и ставить заново, неотправленные данные пропадут.
Поэтому ключ хранится вне папки проекта и **в двух копиях** (ПК + флешка/облако), пароль — в менеджере паролей.

```
keytool -genkeypair -keystore F:\keys\fuel-release.jks -alias fuel -keyalg RSA -keysize 2048 -validity 10000
```
`keytool` входит в JDK (`C:\Users\<user>\.jdks\<jdk>\bin\keytool.exe` или `<Android Studio>\jbr\bin\keytool.exe`).

Затем `android/keystore.properties.example` → скопировать в `android/keystore.properties` (в git не попадает)
и вписать путь к ключу и пароли.

## Сборка
```
cd android
./gradlew assembleRelease
```
Готовый файл: `android/app/build/outputs/apk/release/app-release.apk`.
Без `keystore.properties` сборка падает с `Keystore file not set for signing config release`.

## Выкладка на сервер
```
scp android/app/build/outputs/apk/release/app-release.apk root@fuelapp.ru:/opt/fuel-app/download/app.apk.tmp
ssh root@fuelapp.ru mv /opt/fuel-app/download/app.apk.tmp /opt/fuel-app/download/app.apk
```
Сначала временное имя, потом переименование — никто не скачает недокачанный файл.

## Обновление
1. В `android/app/build.gradle.kts` увеличить `versionCode` на 1 (и `versionName`, напр. `1.1`).
2. Собрать и выложить, как выше. Сотрудникам — та же ссылка; новая версия встаёт поверх, данные сохраняются.

## Текст для сотрудников
> **Учёт топлива — установка на Android**
> 1. Откройте на телефоне https://fuelapp.ru и нажмите «Скачать для Android» — скачается файл `fuel-tracker.apk`.
> 2. Откройте скачанный файл. Если телефон спросит — разрешите установку из этого источника
>    (браузера или «Файлов»), вернитесь назад и нажмите «Установить».
> 3. Если появится предупреждение Play Защиты о неизвестном приложении — «Подробнее» → «Всё равно установить».
> 4. Откройте «Учёт топлива», введите свой номер телефона и код из SMS.
>
> Обновление — так же по ссылке, удалять старую версию не нужно.
