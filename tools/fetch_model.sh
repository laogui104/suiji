#!/usr/bin/env bash
# 下载随记内置的 Vosk 中文离线识别模型（vosk-model-small-cn-0.22）
# 产物：app/src/main/assets/model-cn.zip（约 42 MB，不入 git，见 .gitignore）
# 用法：bash tools/fetch_model.sh
set -euo pipefail

URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets"
DEST="$DEST_DIR/model-cn.zip"

mkdir -p "$DEST_DIR"

if [ -f "$DEST" ]; then
  echo "已存在：$DEST ($(du -h "$DEST" | cut -f1))"
  echo "如需重新下载，先删除该文件。"
  exit 0
fi

echo "下载 $URL ..."
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 -o "$DEST.part" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$DEST.part" "$URL"
else
  echo "未找到 curl 或 wget" >&2
  exit 1
fi

mv "$DEST.part" "$DEST"
echo "完成：$DEST ($(du -h "$DEST" | cut -f1))"
echo "校验目录结构（应含 vosk-model-small-cn-0.22/am、/graph、/conf）："
unzip -l "$DEST" | head -5
