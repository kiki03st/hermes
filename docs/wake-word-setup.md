# "제우스" 웨이크워드 — 리포 밖 수동 준비

> 이 문서가 다루는 건 코드로 자동화할 수 없는 부분뿐이다. 코드 쪽 구조(서비스/버스/화면
> 연동)는 `WakeWordService.kt`/`WakeWordBus.kt`/`ui/HermesApp.kt`를 볼 것. 여기 나온 단계를
> 끝내기 전엔 웨이크워드 토글을 켜도 `PorcupineManager` 초기화가 실패하고 조용히 비활성
> 상태로 남는다(크래시는 안 남 — `WakeWordService.startPorcupine()`이 `runCatching`으로
> 감쌈, logcat에서 `WakeWordService` 태그로 실패 사유 확인 가능).

## 1. Picovoice 계정 + 커스텀 웨이크워드 학습

1. https://console.picovoice.ai 에서 무료 계정 생성(개인/비상업 티어 — 카드 불필요,
   월 3계정/월 3개 커스텀 단어 학습 제한, 기간 제한 없음)
2. 콘솔에서 **Create Wake Word** → 언어 **Korean** 선택 → "제우스" 입력 → 학습 요청
3. **비영어는 즉시 안 나온다 — 학습에 몇 시간 걸림.** 완료되면 콘솔에서 알림/다운로드 가능
4. 완료된 `.ppn` 파일을 다운로드해 `android/app/src/main/assets/zeus_ko.ppn`로 저장
   (`assets/` 디렉터리가 지금 이 모듈에 없으면 새로 만들 것)

## 2. 한국어 베이스 모델 파일

Porcupine 자체 GitHub 리포(`Picovoice/porcupine`)의 `lib/common/` 아래에 언어별 파라미터
파일이 있다 — `porcupine_params_ko.pv`를 받아 같은 위치에 저장:
`android/app/src/main/assets/porcupine_params_ko.pv`

## 3. AccessKey

1. Picovoice Console 대시보드에서 AccessKey 문자열 확인(계정마다 하나)
2. `android/local.properties`(git에 안 올라감)에 추가:
   ```properties
   picovoice.accessKey=<콘솔에서 복사한 값>
   ```
3. `android/app/build.gradle.kts`가 이걸 읽어 `BuildConfig.PICOVOICE_ACCESS_KEY`로 주입한다
   (기존 `hermes.serverUrl`/`hermes.apiKey`와 동일한 패턴)

## 확인

세 가지(`zeus_ko.ppn`, `porcupine_params_ko.pv`, `local.properties`의 AccessKey)가 전부
갖춰진 뒤:
1. 앱 빌드·설치
2. 설정 화면 → "제우스" 항상 듣기 토글 켜기 → 마이크/알림 권한 승인
3. logcat에서 `WakeWordService` 태그에 초기화 실패 로그가 없는지 확인
4. "제우스"라고 말해서 시스템 음성인식 팝업이 뜨는지 확인 — 뜨면 이어서 말한 내용이
   자동으로 채팅에 전송됨(계획 §1, 확인 없이 즉시 전송)

## 이번에 안 만든 것 (알고 있을 것)

- **재부팅 후 자동 재시작 없음** — 매번 설정에서 토글 다시 켜야 함(최신 Android의
  마이크 포그라운드 서비스 백그라운드 시작 제한 때문에 `BOOT_COMPLETED` 리시버를 안 만듦)
- 다른 앱이 마이크를 쓰고 있으면 Porcupine 시작/재시작이 실패할 수 있음 — 로그만 남고
  크래시 안 함, 재시도 안 함(토글 껐다 켜면 재시도)
- 배터리 최적화 예외는 설정 화면의 버튼으로 수동 진행(자동 프롬프트 안 뜸) — 삼성 기기는
  이걸 안 하면 서비스가 얼마 안 가 꺼질 수 있음
