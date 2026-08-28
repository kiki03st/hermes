# "제우스" 웨이크워드 — 리포 밖 수동 준비

> 이 문서가 다루는 건 코드로 자동화할 수 없는 부분뿐이다. 코드 쪽 구조는
> `WakeWordService.kt`/`WakeWordBus.kt`/`ui/HermesApp.kt`를 볼 것.
>
> **엔진은 Vosk(한국어 소형 모델) + Silero VAD다, Picovoice Porcupine이 아니다** —
> Porcupine Console 가입이 "회사 이메일만" 요구하는 벽에 막혀서(2026-08-28 실측) 계정
> 자체가 필요 없는 조합으로 교체했다. 이 조합은 **계정도, API 키도 필요 없다** — 아래
> 모델 파일 하나만 받아서 넣으면 끝.

## 1. 한국어 Vosk 모델 받기

로그인/계정 불필요, 공개 다운로드:

```
https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip  (약 82MB)
```

압축 풀면 `vosk-model-small-ko-0.22/` 폴더 안에 `am/`, `conf/`, `graph/`, `ivector/`,
`README`가 있음 — 이 폴더를 통째로 **`model-ko`로 이름만 바꿔서**
`android/app/src/main/assets/model-ko/`에 놓는다(안에 있는 `am/`/`conf/`/... 파일들이
`model-ko/` 바로 아래 와야 함, 한 단계 더 안 들어가게 주의).

**+ `uuid` 파일 하나 직접 만들어야 함** — 모델 zip 안에는 없는데, Vosk Android의
`StorageService.unpack()`이 `model-ko/uuid` 파일을 무조건 읽으려고 시도한다(버전 추적용,
없으면 있으면 `FileNotFoundException`으로 조용히 실패 — 실측 확인, 2026-08-29). 아무
문자열이나 한 줄이면 됨:

```powershell
# 예시 (PowerShell)
Invoke-WebRequest -Uri "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip" -OutFile "vosk-model-small-ko-0.22.zip"
Expand-Archive -Path "vosk-model-small-ko-0.22.zip" -DestinationPath "."
Move-Item "vosk-model-small-ko-0.22" "android\app\src\main\assets\model-ko"
[guid]::NewGuid().ToString() | Out-File -Encoding ascii -NoNewline "android\app\src\main\assets\model-ko\uuid"
```

풀면 250MB 넘게 나옴 — `.gitignore`에 `android/app/src/main/assets/`가 이미 등록돼 있어
git엔 안 올라간다. **기기를 옮기거나 새로 빌드할 때마다 이 폴더(+`uuid` 파일)가 있는지
확인할 것.**

## 2. Silero VAD

**별도 다운로드 없음** — `android-vad` 라이브러리(Gradle 의존성)가 모델을 자체적으로
내장하고 있음. `android/settings.gradle.kts`에 이미 JitPack 저장소가 등록돼 있고
`build.gradle.kts`에 의존성도 이미 추가돼 있어서 빌드만 하면 됨.

## 3. AccessKey 같은 거 없음

Porcupine 때와 달리 `local.properties`에 넣을 키가 없다 — Vosk/VAD 둘 다 완전 로컬,
계정 연동 자체가 없음.

## 확인

`assets/model-ko/`만 갖춰지면:
1. 앱 빌드·설치
2. 설정 화면 → "제우스" 항상 듣기 토글 켜기 → 마이크/알림 권한 승인
3. logcat에서 `WakeWordService` 태그에 "모델 로드 실패" 에러가 없는지 확인
4. "제우스"라고 말해서 시스템 음성인식 팝업이 뜨는지 확인 — 뜨면 이어서 말한 내용이
   자동으로 채팅에 전송됨(확인 없이 즉시 전송)

## 이번에 안 만든 것 (알고 있을 것)

- **재부팅 후 자동 재시작 없음** — 매번 설정에서 토글 다시 켜야 함(최신 Android의
  마이크 포그라운드 서비스 백그라운드 시작 제한 때문에 `BOOT_COMPLETED` 리시버를 안 만듦)
- **Vosk는 Porcupine 같은 전용 웨이크워드망이 아니라 범용 STT 엔진을 grammar로 제한해서
  쓰는 것** — Silero VAD로 침묵 구간엔 디코더를 안 돌려서 완화했지만, 말소리가 있을 때는
  여전히 Porcupine보다 CPU를 더 씀. 실기기에서 하루 써보고 배터리 체감 확인 필요
- 다른 앱이 마이크를 쓰고 있으면 `AudioRecord` 시작이 실패할 수 있음 — 로그만 남고
  크래시 안 함, 재시도 안 함(토글 껐다 켜면 재시도)
- 배터리 최적화 예외는 설정 화면의 버튼으로 수동 진행(자동 프롬프트 안 뜸) — 삼성 기기는
  이걸 안 하면 서비스가 얼마 안 가 꺼질 수 있음
