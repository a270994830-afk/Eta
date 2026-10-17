#!/usr/bin/env bash
# 构建影子屏 sidecar 载体 dex。
#
# 用法：build_carrier.sh [android_sdk_dir]
#
# 产物：app/src/main/assets/carrier/carrier.dex
#
# 说明：carrier 运行在独立的 app_process 进程里，因此只需要编译到一个 dex，
# 不参与 Eta 主 APK 的类加载。使用 android.jar 作为 bootclasspath，
# 以 -source/-target 8 兼容 ART 的 dex 校验。
set -euo pipefail

eta_root=$(cd "$(dirname "$0")/.." && pwd)
sdk_dir=${1:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}}

if [[ -z "$sdk_dir" ]]; then
    for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /usr/local/android-sdk; do
        if [[ -d "$candidate" ]]; then sdk_dir=$candidate; break; fi
    done
fi
if [[ -z "$sdk_dir" || ! -d "$sdk_dir" ]]; then
    printf '%s\n' '未找到 Android SDK，请作为第一个参数传入或设置 ANDROID_SDK_ROOT。' >&2
    exit 64
fi

android_jar=$(ls -1 "$sdk_dir"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)
if [[ -z "$android_jar" ]]; then
    printf '%s\n' "在 $sdk_dir/platforms 下未找到 android.jar。" >&2
    exit 65
fi

# d8 位于 build-tools 下；Windows 上为 d8.bat，需经 cmd.exe 调用。
# 注意：这里显式拼接路径后再展开通配符，避免 Windows 路径中的空格导致 glob 失效。
shopt -s nullglob
d8_candidates=("$sdk_dir"/build-tools/*/d8)
d8_bat_candidates=("$sdk_dir"/build-tools/*/d8.bat)
shopt -u nullglob
d8_raw=$(printf '%s\n' "${d8_candidates[@]}" | sort -V | tail -1)
d8_win=$(printf '%s\n' "${d8_bat_candidates[@]}" | sort -V | tail -1)
if [[ -z "$d8_raw" && -z "$d8_win" ]]; then
    printf '%s\n' "在 $sdk_dir/build-tools 下未找到 d8。请先安装 Android SDK build-tools。" >&2
    exit 66
fi

src_dir="$eta_root/carrier/src"
out_dir="$eta_root/carrier/out"
dex_dir="$eta_root/carrier/dex"
asset_dir="$eta_root/app/src/main/assets/carrier"

mkdir -p "$out_dir" "$dex_dir" "$asset_dir"
find "$out_dir" -mindepth 1 -delete 2>/dev/null || true
find "$dex_dir" -mindepth 1 -delete 2>/dev/null || true

# 收集源文件清单。
sources=$(find "$src_dir" -name '*.java')
if [[ -z "$sources" ]]; then
    printf '%s\n' '未找到 carrier 源文件。' >&2
    exit 67
fi

# javac 与 d8 处理 Windows 原生路径更稳妥。
to_native() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

native_out=$(to_native "$out_dir")
native_android=$(to_native "$android_jar")

javac_bin=${JAVAC:-javac}
native_sources=()
while IFS= read -r src; do
    [[ -n "$src" ]] && native_sources+=("$(to_native "$src")")
done <<< "$sources"

"$javac_bin" \
    -source 8 -target 8 \
    -encoding UTF-8 \
    -nowarn \
    -bootclasspath "$native_android" \
    -d "$native_out" \
    "${native_sources[@]}" > "$out_dir/javac.log" 2>&1
javac_status=$?
if [[ $javac_status -ne 0 ]]; then
    printf 'javac 失败（退出码 %s），日志：\n' "$javac_status" >&2
    cat "$out_dir/javac.log" >&2
    exit 68
fi

class_count=$(find "$out_dir" -name '*.class' | wc -l)
if [[ "$class_count" -eq 0 ]]; then
    printf '%s\n' 'javac 未产生任何 class 文件。' >&2
    cat "$out_dir/javac.log" >&2
    exit 68
fi

native_dex=$(to_native "$dex_dir")
if [[ -n "$d8_raw" ]]; then
    "$d8_raw" --min-api 30 --output "$native_dex" $(find "$out_dir" -name '*.class' | while read -r f; do to_native "$f"; done)
elif [[ -n "$d8_win" ]]; then
    MSYS2_ARG_CONV_EXCL="*" cmd.exe /c "$(to_native "$d8_win")" --min-api 30 --output "$native_dex" \
        $(find "$out_dir" -name '*.class' | while read -r f; do to_native "$f"; done)
fi

dex_file="$dex_dir/classes.dex"
if [[ ! -f "$dex_file" ]]; then
    printf '%s\n' 'd8 未产出 classes.dex。' >&2
    exit 69
fi

cp "$dex_file" "$asset_dir/carrier.dex"
printf 'carrier.dex 已生成：%s (%s 字节)\n' "$asset_dir/carrier.dex" "$(wc -c < "$asset_dir/carrier.dex")"
