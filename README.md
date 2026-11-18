# 📅 우리들의 캘린더

친구·연인과 같은 방에서 일정을 함께 쓰는 공유 캘린더입니다. 웹앱과 안드로이드 앱(APK)이 같은 파일을 씁니다.

- 앱: https://cheesebyte99.github.io/our-calendar/
- 방 링크: `https://cheesebyte99.github.io/our-calendar/?room=<방ID>`
- 데이터: Firebase `jaeyeon-life` 프로젝트, `rooms/{방ID}/events` (규칙·알림 서버는 [our-firebase](https://github.com/cheesebyte99/our-firebase))
- APK 받기: [Releases](https://github.com/cheesebyte99/our-calendar/releases) 의 `our-calendar.apk`

> 예전 주소 `https://cheesebyte99.github.io/book-tracker/calendar/` 는 이 주소로 자동으로 넘어갑니다(`?room=` 그대로).

## 파일

| 파일 | 설명 |
| --- | --- |
| `index.html` | 캘린더 앱 본체 (HTML + CSS + JS 인라인) |
| `manifest.webmanifest`, `icon*.png`, `icon.svg`, `apple-touch-icon.png` | 홈 화면 아이콘·앱 정보 |
| `firebase-messaging-sw.js` | 웹·아이폰 홈 화면 앱의 푸시 알림 받기 |
| `.nojekyll` | GitHub Pages 가 그대로 서빙하도록 |
| `android/` | 안드로이드 앱(APK) 프로젝트 |
| `.github/workflows/android-apk.yml` | APK 자동 빌드·릴리스 |

## 기능

- **한 화면 달력**: 달력이 화면을 꽉 채우고, 칸마다 일정 제목이 색 칩으로 보입니다(기간 일정은 날짜를 가로지르는 바).
  날짜를 누르면 그 날 일정을 자세히 보는 창이 열리고, 옆으로 밀면 이전/다음 달로 넘어갑니다.
- **아이폰**: Safari 로 열고 공유(□↑) → **홈 화면에 추가** 하면 앱처럼 열립니다.
  `manifest.webmanifest` 에는 일부러 `start_url` 을 두지 않아, 추가할 때 열려 있던 방 주소(`?room=`)로 열립니다.
  「📤 친구 초대」는 휴대폰 공유 창(`navigator.share`)을 쓰고, 안 되는 브라우저에서는 링크를 복사합니다.
- **일정 글씨 크기**: 「글씨 크기 100%」 버튼에서 10%~100%. 이 기기의 localStorage(`shared-calendar:chip-scale`)에만 저장됩니다.
- **꾸미기 (⚙️ 설정)**: 테마 9가지와 배경 사진(이 기기 IndexedDB `shared-calendar-ui`) · 사진 투명도. 모두 이 기기에만 저장됩니다.
  안드로이드 앱은 테마에 맞춰 상태 막대 색도 바꿉니다(`AndroidApp.setSystemBars`).
- **카테고리 편집**: 일정 창의 「✏️ 편집」. 방마다 `rooms/{방ID}/categories/{id}` 에 저장되어 함께 씁니다.
- **기간 일정**: 「기간 일정 (여러 날)」을 켜면 시작~종료 날짜(`endDate`)를 고릅니다.

## 🔔 푸시 알림

- **설정(⚙️)**: 알림 받기 · 당일 알림 시간(5분 단위, 기본 09:00) · 일정 추가·수정·삭제 알림 · 내 이름.
  알림 설정은 기기마다 따로이고(localStorage `shared-calendar:push`), 켜면 `rooms/{방ID}/devices/{기기ID}` 에 등록합니다.
- **일정마다 알림**: 당일 · 1일 전 · 2일 전 · 3일 전 · 1주 전 중 여러 개(`reminders`).
- **받는 쪽**: 안드로이드 앱은 네이티브 FCM(`android/app/google-services.json`), 웹·아이폰 홈 화면 앱은
  `firebase-messaging-sw.js` + VAPID 키. 알림을 누르면 그 방·날짜 상세가 열립니다(`?room=…&date=…`).
- 보내는 쪽(서버)은 [our-firebase](https://github.com/cheesebyte99/our-firebase) 의 `eventChanged`·`dailyReminders` 입니다.

## 📱 안드로이드 앱 (APK) — `android/`

맨 위의 `index.html` 을 빌드 때 그대로 앱 안에 넣으므로 웹판을 고치면 앱도 다음 빌드에 같이 바뀝니다.

- 앱은 페이지를 `https://cheesebyte99.github.io/our-calendar/` 주소로 열되, 파일은 앱 안의 것을 씁니다(`WebViewAssetLoader`).
  그래서 인터넷이 잠깐 끊겨도 화면은 바로 뜨고, 초대 링크는 웹 주소 그대로라 앱이 없는 친구도 브라우저로 들어옵니다.
- 앱에서만: **📤 친구 초대**(안드로이드 공유 창) · 방 설정에 초대 링크를 통째로 붙여 넣어도 방 ID 만 골라 이동 ·
  **공유 → 우리들의 캘린더** 로 보내면 그 방이 열림 · 뒤로 가기는 열린 창부터 닫음.
- 초대 링크를 눌러 앱이 바로 열리게 하려면: 앱 정보 → **기본으로 열기** → `cheesebyte99.github.io` 링크 허용.
  새 주소(`/our-calendar`)와 예전 주소(`/book-tracker/calendar`) 링크를 모두 받습니다.

```bash
cd android
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk  (ANDROID_HOME 필요)
```

GitHub Actions 가 push 때마다 APK 를 만들어 **Artifacts** 에 올리고, `main` 이면 **Releases** 에 `our-calendar.apk` 로 올립니다.
예전 저장소에서 1.0.19 까지 나갔으므로 이 저장소는 **2.0.N (versionCode 100+N)** 으로 이어 갑니다(덮어쓰기 업데이트 가능).

서명: 저장소 비밀값 `ANDROID_KEYSTORE_BASE64`·`ANDROID_KEYSTORE_PASSWORD`·`ANDROID_KEY_ALIAS`·`ANDROID_KEY_PASSWORD` 가 있으면 그 키,
없으면 `android/debug.keystore`(비밀번호 `android`) 로 서명합니다. 같은 키로 서명돼야 덮어쓰기 업데이트가 됩니다.

GitHub Pages 는 `main` 브랜치 루트를 그대로 서빙합니다(Settings → Pages → Deploy from a branch → `main` / `/ (root)`).
