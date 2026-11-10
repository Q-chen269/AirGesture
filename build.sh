#!/bin/bash
# 本地一键编译：./build.sh
set -e

ENV="/Users/xiaodu-wuwenbin/Desktop/小程序/android-env"
export JAVA_HOME="$ENV/jdk/Contents/Home"
export ANDROID_HOME="$ENV/sdk"
export PATH="$JAVA_HOME/bin:$ENV/gradle-8.7/bin:$PATH"

cd "$(dirname "$0")"
gradle :app:assembleDebug --console=plain "$@"

APK="$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "编译完成：$APK"
ls -lh "$APK"
