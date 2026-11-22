# fot 네이티브 릴리스 검증

작성 시각: 2026-11-22 17:47 Asia/Seoul. Android·Windows의 실제 업그레이드 검증과 기존 채널 게시·운영 읽기 확인을 완료했다. iOS는 Apple 약관 동의가 필요해 제출하지 않았다. 전체 플랫폼 출시 완료로 표시하지 않는다.

## 소스·산출물

각 저장소의 `codex/fot-native-release-20261122` 브랜치에서 작업했다. 공유 플랫폼의 부모 변경과 index는 수정하지 않았다. 표시명은 fot이며 기존 패키지·번들 ID, 인증서, 로그인·저장 경로, 웹/API와 업데이트 채널을 보존한다.

| 플랫폼 | 패키징 소스 | 버전 | 실제 산출물 |
| --- | --- | --- | --- |
| Android | `beca9c1533d0c18040d1dfcaf1e8bd7baf115243` | 1.3.3(13300) | 기존 키 서명 production APK와 staging APK |
| Windows | `aafe5aeb720202735d3dd364a18aa4ad8106e295` | 1.3.3 | 기존 설치형 EXE·portable ZIP 파일명/데이터 경로 유지 |
| iOS/iPadOS | `353b91d498dc7fdbfa8df51e25ef058f6e8069ea` | 1.3.2(11) | 기존 production origin과 배포 인증서의 IPA |

Android 검사 소스 `9ec66b861a586162d29e1c037630ed1b32589813`, Windows 검사 소스 `0f6fa599ce3ae621e9af0e64d80a2fc4265cffae`는 후속 helper 변경이며 위 바이너리를 재빌드하지 않았다. 웹 자산 통합 커밋은 Android `5a7058c2279fc939794ea00a57ec4d3aeb1e9eaf`, Windows `9388eeb0504fc9b86fd0d674240431cc66c1aa99`다. 부모가 최종 웹 `f2da655`와 플랫폼 `b51fe49`의 통합 CI44987393249 및 staging173/173 통과를 확인했다. production 배포는 부모 소유이며 앱 검증을 승격 receipt로 대체하지 않았다.

## 실제 검증

- Android `testDebugUnitTest assembleDebug assembleRelease` SUCCESS. APK 내부 label fot, production `com.flownote.mobile`/staging `com.flownote.mobile.staging`, versionCode13300 확인. 새 APK 인증서는 기존 1.3.2와 같다.
- Windows 패키징 CI[44986098018](https://github.com/lunce0503/flownote-desktop/actions/runs/44986098018) SUCCESS. artifact14740021818을 실제 다운로드하고 archive SHA256 및 내부 checksum을 검증했다. 실제 업그레이드 CI[44987341971](https://github.com/lunce0503/flownote-desktop/actions/runs/44987341971) SUCCESS. 설치형·portable 각각 기존1.3.2의 packaged updater로 서명1.3.3 다운로드·해시 검증, 설치형 실행/portable 교체, 동일 프로필·로그인·로컬 marker, fot 표시명, 새 웹 entry, sandbox와 재시작을 확인했다. 주식/시장 API4개410 및 전체 기능 메뉴의 stocks/banpick/screw-puzzle/magic 링크0도 양쪽 통과했다.
- Android API35의 실제 WebView·OS 설치 확인으로13200→13300 자동 다운로드·SHA256·native 서명·캐시 재시작·OS 사용자 확인·설치 완료·세션/로컬 marker/UTF16CRLF 코드 노트·최신 버전 확인을 통과했다. 마지막 메뉴는 홈 화면의 별도 실행에서4개 비노출과 API4개410을 확인했다. 마지막 검사 entry `/assets/index-CYhs0Fys.js`. synthetic 문서 삭제·logout-all 후 이전 token401을 확인했으며 비밀값은 기록하지 않았다.
- Android 최초 실행은 이메일 locator45초 timeout, 두 번째는 CDP180초 timeout으로 실패했다. 11.5일 실행된 guest에서 Binder 응답도 멈춰 개인 AVD 백업을 검증하고 동일 AVD를 `-wipe-data` 없이 cold boot했다. 일반 권한 파일 복사 실패 후 컨테이너 내부 tar로 userdata/encryption key 포함 백업을 검증했다. 첫 재기동은 AVD 경로 미설정으로 실행되지 않았고 경로 지정 후 부팅했다. System UI ANR를 닫아 복구했으며 앱 데이터를 지우지 않았다.
- coldboot 원래 helper는 업그레이드·보존·API 검증 이후 마지막140행에서 native dialog interception 뒤 Playwright pending navigation으로45초 timeout, exit1이었다. 이 실행을 통째로 PASS로 표시하지 않는다. 같은 설치된13300의 일반 홈에서 마지막 메뉴만 별도로 PASS했다. 새 APK/코드/제한시간 변경, assertion 삭제 또는 재시도 완화가 없다.

실행 로그: `/tmp/fot-native-20261122/android-build.log`, `windows-upgrade.log`, `android-upgrade.log`, `android-upgrade-ready.log`, `android-upgrade-coldboot.log`, `android-menu-followup.log`. 임시 진단·기기 백업은 저장소 밖에 두며 사용자 파일·인증값을 보고서에 포함하지 않는다.

## iOS 처리와 남은 조건

EAS build `97531db0-22cd-4651-a5cf-1c92f0e2d970` FINISHED. 실제 IPA의 표시명 fot, 번들 `com.flownote.mobile`,1.3.2(11), distribution profile의 debug=false와 기존 인증서를 검증했다. IPA16952189바이트, SHA256 `f22ddd833488f981caeec27e0b4dee11d26c8b5c38bb3ddaf3bd53e28a9f1aa1`.

Apple API 사전 조회는403 `FORBIDDEN.REQUIRED_AGREEMENTS_MISSING_OR_EXPIRED`로 차단됐다. Account Holder에게 계정의 새 약관을 직접 확인·동의하도록 한 번 요청했으며 응답 대기다. [Apple 공식 접근 문제 안내](https://developer.apple.com/help/account/access/resolving-access-issues/)를 따른다. 제출 FINISHED, Apple VALID 또는 기존 내부 TestFlight 그룹 포함을 확인한 것으로 표현하지 않는다. 약관 동의 후 같은 build11을 제출하며 중복 빌드하지 않는다. 공개 App Store 배포는 요청 범위가 아니다.

물리 Android·iPhone·iPad가 연결되지 않았다. Android 에뮬레이터와 실제 Windows CI 앱 실행을 실기기 사용성 검증과 구분한다. 사전 기능 보류와 별도 Remote 작업은 유지한다.

## 게시·운영 읽기 확인

부모가 2026-11-22 08:48 UTC에 동일 source `b51fe49`, 지문 접두사 `fe7bdc450e14`, staging173/173 matching receipt의 운영 승격을 확인했다. 운영 API `7e45450a-a1e2-407f-809d-e384ce75c326`, Go `fea42f5c-96b8-4435-8d9d-d298733adc24`, Vercel `dpl_2k5hegDRdsLW5rPs6ow9a9h87dBH` READY/공식 alias 일치다. 네이티브 담당은 backend 중복 배포나 production 검증 데이터 생성을 하지 않았다.

운영 확인 후 08:49 UTC에 기존 [Windows454209628](https://github.com/lunce0503/flownote/releases/tag/windows-v1.3.3)을 안정 버전으로 전환했고 draft=false/prerelease=false를 확인했다. 기존 [Android454212772](https://github.com/lunce0503/flownote-service/releases/tag/android-v1.3.3)은 draft=false로 게시했다. Android 기록 저장소는 PRIVATE이며 공개로 바꾸지 않았다. 사용자 공개 다운로드는 [Vercel APK](https://flownote-react.vercel.app/downloads/flownote-1.3.3.apk)와 [설치 안내](https://flownote-react.vercel.app/android.html)다. 새 릴리스/바이너리를 중복 생성하지 않았다.

`production-readback.json/.log` 실제 읽기 확인 PASS:

- 운영·staging 두 alias에서 두 채널×두 플랫폼의8개 서명 feed가 frozen 자산과 바이트 일치, 기존 공개 키 서명 valid 및 no-store다.
- 양 Android APK는 인증 없는 공개 GET200, 실제 전체 바이트·크기·SHA256이 후보와 일치한다. 실제 다운로드 파일의 인증서가 production `600a7fe351c802a22b4d8d2a90904ba34f72d9f07c575400b6473609d3a38fad`, staging `2443050bfcbdfb90cf77940f3322f4779de2bfc847eea5aba45e17e62bf174f9`로 기존 키와 같다. production package/version/label은 `com.flownote.mobile`/13300/1.3.3/fot다.
- Windows 두 공개 자산 익명 HEAD200/정확한 Content-Length, GitHub digest와 로컬 checksum 일치다. 실제 전체 다운로드/업그레이드는 앞선 Windows CI44987341971에서 통과한 같은 바이트다.
- 양 환경 웹 entry GET200/SHA256, Android 설치 링크·기록 링크, API200·익명 me401·mobile config 환경 분리, 주식/시장4경로410을 확인했다. production 쓰기0이다.

운영 이후 iOS 제출 차단 상태는 변하지 않았다. EAS FINISHED를 TestFlight 배포 완료로 표시하지 않는다. Account Holder 약관 동의 후 같은 build11의 제출·Apple VALID·기존 내부 그룹 포함을 이어 확인해야 한다.

Android production APK72816바이트, SHA256 `25559e27bfd365709b91f76d6be7ed720f94e50423daf099222a64002a155a93`. Windows installer111838884바이트 `b39b15dd9db350ad514bb985aa6fa0fe409fb950bc73626a8fde303629590d99`, portable153458671바이트 `dd5bf0642a14d68c9ed7da22ab0087ff320daf6859d064e05cc0ba69b4dbe849`.
