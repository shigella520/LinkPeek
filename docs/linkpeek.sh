#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title LinkPeek
# @raycast.mode silent

# Optional parameters:
# @raycast.icon 🔗

# Documentation:
# @raycast.author JianyuTan

base_url="https://linkpeek.jianyutan.com/preview?url="
input="$(pbpaste)"

if [[ -z "${input//[[:space:]]/}" ]]; then
  open /System/Applications/Messages.app
  exit 0
fi

input="$(printf '%s' "$input" | tr -d '\r' | xargs)"

final_url="$(
INPUT="$input" BASE_URL="$base_url" python3 - <<'PY'
import os
import re
from urllib.parse import quote, urlsplit

raw = os.environ["INPUT"].strip()
base_url = os.environ["BASE_URL"]

BV_PATTERN = re.compile(r"(BV[0-9A-Za-z]{10})")
V2EX_TOPIC_PATH_PATTERN = re.compile(r"^/(?:amp/)?t/(\d+)(?:/.*)?$")
NGA_TID_QUERY_PATTERN = re.compile(r"(^|&)tid=(\d+)($|&)")
LINUXDO_TOPIC_PATH_PATTERN = re.compile(r"^/t/(?:[^/]+/)?(\d+)(?:/\d+)?/?$")


def is_supported(url: str) -> bool:
    try:
        parts = urlsplit(url)
    except Exception:
        return False

    scheme = parts.scheme.lower()
    host = (parts.hostname or "").lower()
    path = parts.path or "/"
    query = parts.query or ""

    if scheme not in ("http", "https") or not host:
        return False

    if host == "b23.tv":
        return True

    if host.endswith("bilibili.com") and (BV_PATTERN.search(path) or BV_PATTERN.search(query)):
        return True

    if host in ("v2ex.com", "www.v2ex.com") and V2EX_TOPIC_PATH_PATTERN.match(path):
        return True

    if host in ("bbs.nga.cn", "nga.178.com", "ngabbs.com") and path == "/read.php" and NGA_TID_QUERY_PATTERN.search(query):
        return True

    if host in ("linux.do", "www.linux.do") and LINUXDO_TOPIC_PATH_PATTERN.match(path):
        return True

    return False


if is_supported(raw):
    print(f"{base_url}{quote(raw, safe='')}", end="")
PY
)"

if [[ -z "$final_url" ]]; then
  open /System/Applications/Messages.app
  exit 0
fi

printf '%s' "$final_url" | pbcopy
open /System/Applications/Messages.app
