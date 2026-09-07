#!/usr/bin/env bash
# 万能转发器 纯命令行构建脚本（本地与 GitHub Actions 通用）
#
# 环境要求（可用环境变量覆盖默认路径）：
#   ANDROID_JAR : android.jar 路径（默认查找 platforms;android-34）
#   BT_DIR      : build-tools 目录（含 aapt2/d8/zipalign/apksigner），默认 34.0.0
#   JAVA        : java/javac 可执行前缀，默认使用 PATH 中的 javac
#
# 用法: ./build.sh [out.apk]
set -euo pipefail
cd "$(dirname "$0")"

OUT="${1:-app-release.apk}"
APP_DIR="app"
PKG_SRC="$APP_DIR/src"
CLS=build/classes
LIBS_OUT=build/libs

# ---------- 定位工具链 ----------
find_android_jar() {
  if [[ -n "${ANDROID_JAR:-}" ]]; then echo "$ANDROID_JAR"; return; fi
  for cand in \
    "$ANDROID_HOME/platforms/android-34/android.jar" \
    "$ANDROID_SDK_ROOT/platforms/android-34/android.jar" \
    /usr/lib/android-sdk/platforms/android-*/android.jar; do
    [[ -f "$cand" ]] && { echo "$cand"; return; }
  done
  echo "ERROR: android.jar 未找到，请设置 ANDROID_JAR" >&2; exit 1
}
find_btdir() {
  if [[ -n "${BT_DIR:-}" ]]; then echo "$BT_DIR"; return; fi
  for cand in \
    "$ANDROID_HOME/build-tools/34.0.0" \
    "$ANDROID_SDK_ROOT/build-tools/34.0.0" \
    /usr/lib/android-sdk/build-tools/34.0.0; do
    [[ -x "$cand/aapt2" ]] && { echo "$cand"; return; }
  done
  echo "ERROR: build-tools 未找到，请设置 BT_DIR" >&2; exit 1
}
JAR="$(find_android_jar)"
BT="$(find_btdir)"
echo "android.jar: $JAR"
echo "build-tools: $BT"

# ---------- Shizuku 依赖（Maven Central 下载 AAR 解出 classes.jar）----------
# dev.rikka.shizuku:{api,aidl,provider}:13.1.5
mkdir -p "$LIBS_OUT"
declare -A MODULES=( [api]=dev.rikka.shizuku:api:13.1.5 [aidl]=dev.rikka.shizuku:aidl:13.1.5 [provider]=dev.rikka.shizuku:provider:13.1.5 )
for key in api aidl provider; do
  mod="${MODULES[$key]}"
  IFS=':' read -r grp art ver <<< "$mod"
  # dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar
  url="https://repo1.maven.org/maven2/${grp//.//}/${art}/${ver}/${art}-${ver}.aar"
  aar="$PWD/$LIBS_OUT/$key.aar"; jar="$PWD/$LIBS_OUT/$key.jar"
  if [[ ! -f "$jar" ]]; then
    echo "下载 $mod ..."
    curl -fsSL -o "$aar" "$url"
    ( rm -rf "$LIBS_OUT/$key" && mkdir -p "$LIBS_OUT/$key" && cd "$LIBS_OUT/$key" \
      && unzip -o -q "$aar" classes.jar && mv classes.jar "$jar" )
  fi
done
CLASSPATH_DEPS="$(ls "$LIBS_OUT"/*.jar | tr '\n' ':')"

# ---------- 编译 ----------
rm -rf "$CLS" && mkdir -p "$CLS"
echo "javac ..."
javac -source 1.8 -target 1.8 -bootclasspath "$JAR" -classpath "$JAR:$CLASSPATH_DEPS" \
  -d "$CLS" $(find "$PKG_SRC" -name '*.java')
echo "d8 ..."
"$BT/d8" --release --lib "$JAR" --output build \
  $(find "$CLS" -name '*.class') "$LIBS_OUT"/*.jar

# ---------- 资源/manifest 链接 ----------
echo "aapt2 link ..."
"$BT/aapt2" link -o build/base.apk -I "$JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34
python3 - <<'PY'
import zipfile, shutil
shutil.copy('build/base.apk','build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk','a') as z:
    z.write('build/classes.dex','classes.dex')
PY

# ---------- 对齐 + 签名 ----------
echo "zipalign + apksigner ..."
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f build/release.keystore ]]; then
  keytool -genkeypair -keystore build/release.keystore -alias app -storepass android \
    -keypass android -dname "CN=urlfeeder" -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/apksigner" sign --ks build/release.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT" build/aligned.apk
echo "OK -> $OUT"
ls -la "$OUT"
