#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title LinkPeek
# @raycast.mode silent

# Optional parameters:
# @raycast.icon 🔗

# Documentation:
# @raycast.author JianyuTan

# 这里只保留服务根地址。支持判定接口和预览入口都从同一个根地址派生，
# 自部署用户只需要改这一处。
base_url="https://linkpeek.jianyutan.com"
base_url="${base_url%/}"
support_url="${base_url}/api/preview/support"
preview_base_url="${base_url}/preview?url="

url_encode() {
  local LC_ALL=C
  local value="$1"
  local length="${#value}"
  local i char

  for ((i = 0; i < length; i++)); do
    char="${value:i:1}"
    case "$char" in
      [a-zA-Z0-9.~_-])
        printf '%s' "$char"
        ;;
      *)
        printf '%%%02X' "'$char"
        ;;
    esac
  done
}

# Raycast 从剪贴板读取输入；如果没有可用文本，只打开 Messages，
# 不修改当前剪贴板内容。
input="$(pbpaste)"

if [[ -z "${input//[[:space:]]/}" ]]; then
  open /System/Applications/Messages.app
  exit 0
fi

input="$(printf '%s' "$input" | tr -d '\r' | xargs)"

# 让云端服务用当前 provider registry 判断链接是否支持。
# 网络失败或返回非 JSON 时按“不支持”处理，避免误改剪贴板。
support_response="$(curl -fsS -G --data-urlencode "url=$input" "$support_url" 2>/dev/null || true)"
compact_response="${support_response//$'\n'/}"
compact_response="${compact_response//$'\r'/}"
compact_response="${compact_response//$'\t'/}"
compact_response="${compact_response// /}"

if [[ "$compact_response" != *'"supported":true'* ]]; then
  open /System/Applications/Messages.app
  exit 0
fi

# 支持判定接口只返回判断结果；确认支持后，再在本地拼接最终预览链接。
encoded_url="$(url_encode "$input")"
final_url="${preview_base_url}${encoded_url}"

printf '%s' "$final_url" | pbcopy
open /System/Applications/Messages.app
