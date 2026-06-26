#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_root="$repo_root/android-app"
local_toolchain="${ANDROID_BUILD_TOOLCHAIN:-}"

if [ -n "$local_toolchain" ] && [ -d "$local_toolchain/jdk/17" ]; then
  export JAVA_HOME="$local_toolchain/jdk/17"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -n "$local_toolchain" ] && [ -d "$local_toolchain/sdk" ]; then
  export ANDROID_HOME="$local_toolchain/sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
fi

if [ -n "$local_toolchain" ] && [ -d "$local_toolchain/gradle/gradle-8.10.2" ]; then
  export PATH="$local_toolchain/gradle/gradle-8.10.2/bin:$PATH"
fi

if ! command -v gradle >/dev/null 2>&1 && [ ! -x "$android_root/gradlew" ]; then
  cat >&2 <<'EOF'
Android build toolchain is missing.

Install Android Studio or command-line Android SDK plus Gradle, then run:
  cd android-app
  gradle :app:assembleDebug

If Gradle is installed, you can also generate a wrapper:
  cd android-app
  gradle wrapper --gradle-version 8.10.2
  ./gradlew :app:assembleDebug
EOF
  exit 127
fi

cd "$android_root"
if [ -x ./gradlew ]; then
  ./gradlew :app:assembleDebug
else
  gradle :app:assembleDebug
fi
