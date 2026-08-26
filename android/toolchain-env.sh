# 이 리눅스 서버 전용 안드로이드 빌드 환경 변수.
# 사용: `source android/toolchain-env.sh` 후 `./gradlew ...` 실행.
# JDK 21 / Gradle 9.3.1 / Android SDK android-36.1, build-tools 36.1.0을
# ~/android-toolchain/ 아래 독립 설치했다 (sudo 불필요, 시스템 패키지 미변경).
export JAVA_HOME="$HOME/android-toolchain/jdk-21"
export ANDROID_HOME="$HOME/android-toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$HOME/android-toolchain/gradle-9.3.1/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
