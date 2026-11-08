#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-evidence
adb shell wm size 1280x720
# 固定横屏：测试用的 ComponentActivity 不带 sensorLandscape，API28 默认竖屏会把主台挤成近似竖条。
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell wm size | tee ui-evidence/screen.txt
adb shell wm density "$1"
adb shell settings put system font_scale "$2"
adb install -r apk/debug/app-debug.apk
adb install -r apk/androidTest/debug/app-debug-androidTest.apk
# 等横屏、密度、字号真正生效再跑测试：配置变化若在测试 Activity 启动之后才到，Activity 会被重建，
# Compose 测试就会报「No compose hierarchies found」（API 28 上偶发）。最多等 10 秒，不因等不到而失败。
rotation=""
for _ in $(seq 1 20); do
  displays="$(adb shell dumpsys window displays || true)"
  rotation="$(grep -Eo 'mRotation=[0-9]|mCurrentRotation=[0-9A-Z_]+' <<< "$displays" | head -n 1 || true)"
  case "$rotation" in
    *=1|*ROTATION_90) break ;;
  esac
  sleep 0.5
done
echo "rotation: ${rotation:-unknown}" | tee ui-evidence/rotation.txt
sleep 2
adb shell am instrument -w com.chuankangkk.wrenchlift.debug.test/androidx.test.runner.AndroidJUnitRunner | tee ui-evidence/instrumentation.txt
for preview in worker-remote-off worker-remote-on worker-params worker-guide; do
  if ! adb exec-out run-as com.chuankangkk.wrenchlift.debug cat "files/$preview.png" > "ui-evidence/$preview.png"; then
    rm -f "ui-evidence/$preview.png"
  fi
done
grep -Eq 'OK \([0-9]+ tests?\)' ui-evidence/instrumentation.txt
adb shell pm grant com.chuankangkk.wrenchlift.debug android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.chuankangkk.wrenchlift.debug android.permission.ACCESS_COARSE_LOCATION
if [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]; then
  adb shell pm grant com.chuankangkk.wrenchlift.debug android.permission.NEARBY_WIFI_DEVICES
fi
adb shell am start -W -n com.chuankangkk.wrenchlift.debug/com.chuankangkk.wrenchlift.MainActivity | tee ui-evidence/startup.txt
grep -q 'Status: ok' ui-evidence/startup.txt
adb shell dumpsys package com.chuankangkk.wrenchlift.debug | grep -E 'versionCode=|versionName=' > ui-evidence/version.txt
