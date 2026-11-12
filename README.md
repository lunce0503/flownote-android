# Flownote Android

Android 10 이상용 독립 설치 APK. `com.flownote.mobile` 패키지로 설치하며 Expo Go가 필요 없다.

웹 전체 역량과 편집기를 유지하기 위해 Android 시스템 WebView에서 배포된 `flownote/`를 실행한다. 웹이 갱신되면 앱에도 같은 기능이 반영된다. 네트워크 연결이 필요하며 기존 웹 세션 저장 및 로컬 초안 정책을 그대로 사용한다. `apps/android/`는 별개의 Flownote Remote 앱이므로 이 앱과 혼동하지 않는다.

## 빌드

Java 17, Android SDK 35, Build Tools 35.0.0을 사용한다.

```bash
./gradlew assembleDebug lintRelease
# 운영 서명키와 비밀번호는 환경에만 주입한다.
./gradlew assembleRelease
```

운영 서명 설정은 `FLOWNOTE_ANDROID_KEYSTORE`, `FLOWNOTE_ANDROID_SIGNING_PASSWORD`와 별칭 `flownote-android`를 사용한다. 서명키를 잃으면 기존 설치를 유지한 업데이트가 불가능하므로 운영자 전용 저장소에 보관하고 저장소·APK·보고서에 넣지 않는다.

- debug: 패키지 `com.flownote.mobile.staging`, staging 웹에 연결.
- release: 패키지 `com.flownote.mobile`, production 웹에 연결.
- 앱 식별: WebView UA의 `FlownoteAndroid/<BuildConfig.VERSION_NAME>` marker.
- 기존 Flownote 헤더 대신 앱 전용 하단 탐색과 전체 기능 메뉴 사용.
- 파일: 시스템 문서 선택기와 HTTPS DownloadManager. 1.3.0 PDF는 최대 100MiB를 256KiB씩 private cache에 기록하고 시스템 저장 선택기로 내보낸다. 나머지 blob/data 내보내기는 기존 32MiB 한도를 유지한다. origin·총 크기·순서·PDF 헤더를 검사하고 취소/오류의 부분 파일을 정리한다.
- 보안: HTTPS 고정 origin, 외부 링크는 별도 앱, 파일 URL·혼합 콘텐츠 차단, JavaScript native bridge 없음, 백업 비활성화, release WebView 디버깅 비활성화.

## 배포

1. 정적 검사와 `assembleDebug lintRelease`, 서명 release APK 검증을 수행한다.
2. release APK는 `flownote/public/downloads/flownote-1.3.1.apk`, staging debug APK는 `flownote-1.3.1-staging.apk`로 분리한다. 이전 파일은 유지한다.
3. `node scripts/deploy-cloud.mjs release web`으로 staging E2E 통과 후 같은 소스를 production에 배포한다.
4. 승격 후 `/android.html` 안내와 `/downloads/flownote-1.3.1.apk`의 HTTP 상태·SHA-256을 확인한다. 2026-10-30 production과 GitHub android-v1.3.1 공개 및 서명/해시 readback을 완료했다.

## 자동 업데이트

1.1.0/versionCode 11000부터 네이티브 `AppUpdater`가 시작/재개/1시간 주기에 새 APK를 자동 다운로드한다. 무료 모바일 계정/Expo와 무관하며 직접 배포 APK 전용이다. OS 설치 확인 및 ‘이 출처 허용’은 사용자가 수행한다. 기존 앱을 삭제하지 않고 같은 서명키로 설치하면 WebView 로그인·초안을 유지한다. 1.0.0은 updater가 없어 한 번 수동 설치가 필요하다.

1.3.1(13100)은 하단 네이티브 바를 없애고 **전체 → 설정 → 앱 업데이트**에서 버전·상태·확인·설치·이력을 제공한다. 네이티브가 제한된 HTTPS/main-frame/사용자 클릭 경로만 처리하며 범용 JavaScript interface를 노출하지 않는다. 서명·해시·고정 채널·롤백 방어·설치 확인은 유지한다. 현재 staging/production APK와 Android 서명 피드는 모두1.3.1이다. 최종129/129 및 Windows 양쪽 Notes+Reader/정확한 Android APK 검증 뒤 운영 승격·[GitHub 공개](https://github.com/lunce0503/flownote-service/releases/tag/android-v1.3.1)를 완료했다. [최종 기록](https://github.com/lunce0503/flownote-platform/blob/main/logs/report/2026-10-30-0316-note-app-release-dictionary-paused.md)을 따른다. 자동 사전 조회는 사용자 요청으로 보류하며 단어 보관·직접 메모는 유지한다.

플랫폼 저장소 루트에서 서명키 경로를 `FLOWNOTE_UPDATE_SIGNING_KEY` 환경에 넣고 `node scripts/sign-app-update.mjs android staging 1.3.1 flownote/public/downloads 13100` 및 production을 실행한다. 이 파일 서명만으로 운영 피드가 바뀌지 않으며 staging 검증 후 같은 소스를 승격해야 한다. feed 서명키는 APK 서명키와 별개이며 앱에 공개키를 고정한다. 상세 채널·파일 검증 계약은 [소유 사양](https://github.com/lunce0503/flownote-platform/blob/main/docs/product-specs/android-app.md#120-자동-업데이트)을 따른다.

책장 원본·메타·단어장은 [책장 클라우드 계약](https://github.com/lunce0503/flownote-platform/blob/main/docs/product-specs/textbook-library.md)에 따라 계정별로 동기화하며 IndexedDB와 기기 전용 OFF/ON 편집도 유지한다. 앱 업데이트 시 이를 삭제하지 않는다. 일반 Android API35 에뮬레이터에서 `node flownote-android/scripts/library-smoke.mjs <new-debug-apk>`로 시스템 파일 선택·100MiB 등록·1.2→1.3 보존·내보내기 해시를 확인한다. 실제 하드웨어 검증과 구분한다.

`node scripts/test-app-updates.mjs`는 Node updater 및 순수 JVM 정책 테스트다. debug에만 `-PtestVersionCode=10001 -PtestVersionName=1.0.1`로 업데이트 이전 버전을 만들어 cloud staging에서 시험할 수 있다. release 작업은 test version override를 거부한다.

통합 검사는 `node flownote-android/scripts/settings-update-smoke.mjs <정확한 공개 staging APK> <동일 구현의 낮은 버전 debug fixture APK>`로 실행한다. 주입 옵션 없이 실제 cloud Settings·업데이트·OS 허용/설치·노트/수식을 검사한다. CDP 한글 조합 검사는 AVD `hw.keyboard=yes`로 재시작한 에뮬레이터가 필요하다. 스크립트는 AVD 설정 파일(`FLOWNOTE_TEST_AVD_CONFIG`로 경로 지정 가능)의 keyboard=yes와 QWERTY 구성을 함께 확인하고 화면 키보드 옵션을 임시 제어·복원한다. `hw.keyboard=no`에서 옵션0만으로는 일반 contenteditable의 조기 종료·중복을 막지 못했고, hardware=yes 및 IME 비활성화 단독 대조에서도 실패가 남았다. 이를 앱 버그로 단정하지 않는다. Android CDP 드라이버는 같은 세 입력 명령을 같은 연결 순서로 pipeline 전송해 호스트 round trip 사이의 간섭을 줄인다. 명령/문자/단계 수는 유지하며 Windows의 순차 전송은 바꾸지 않는다. 동일 문장·중간 4글자 선택·같은 CDP 세션 순서의 React 없는 대조군 5회와 앱 검사를 함께 수행하며 정확한 문자와 조합 시작/종료 1회 조건을 유지한다. 이는 물리 Gboard/손가락 입력 검증이 아니다.

사용자 동작 사양은 [Android 앱 사양](https://github.com/lunce0503/flownote-platform/blob/main/docs/product-specs/android-app.md)을 따른다.

GitHub Actions의 **Android staging APK**에서 디버그 APK를 받을 수 있다. 운영 서명과 릴리스는 플랫폼의 앱 릴리스 절차를 따른다.

통합 검증 및 배포 절차는 [flownote-platform](https://github.com/lunce0503/flownote-platform)에서 관리한다.
