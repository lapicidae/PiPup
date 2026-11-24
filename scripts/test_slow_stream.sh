#!/bin/bash
#
# PiPup Slow Stream & Poster Preload Tester.
# Simulates a throttled video stream with delayed delivery to test poster preloading,
# posterMode scaling, bufferMs load control, mediaFullBleed, and liveBadge reveal.
# Strictly adheres to the Google Shell Style Guide and AGENTS.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/common.sh"

readonly SERVER_PORT="8888"
readonly SAMPLE_VIDEO_URL="https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_5MB.mp4"
readonly SAMPLE_POSTER_URL="https://thumb.wikimedia.org/wikipedia/de/thumb/e/ea/Big_Buck_Bunny.svg/330px-Big_Buck_Bunny.svg.png"
readonly CACHE_DIR="/tmp/pipup_test"

SERVER_PID=""

usage() {
  cat <<EOF
Usage: ${0##*/} [-d ip] [-D delay_s] [-s speed_kb] [-b buf_ms] [-p pos] [-m mode] [-B badge_pos] [-C color] [-L] [-F] [-M] [-P] [-c]
Options:
  -d    Target IP (default: ${DEFAULT_IP})
  -D    Initial video delay in seconds (default: 5)
  -s    Streaming speed in KB/s (default: 350)
  -b    ExoPlayer bufferMs (default: 2000)
  -p    Position index 0..8 (default: 0)
  -m    Poster mode: fill (default), fit, stretch, center
  -L    Disable LIVE badge (enabled by default; reveals on first frame)
  -B    LIVE badge position: bottom_right (default), top_right, top_left, bottom_left
  -C    LIVE badge hex color (default: "#B3261E")
  -F    Enable mediaFullBleed
  -M    Parallel 5-popup showcase (maxPopups=5: fill, fit, stretch+fullBleed, center, placeholder)
  -P    Omit poster to test media loading placeholder
  -c    Cancel active popups before starting
  -h    Show this help
EOF
  exit 1
}

prepare_assets() {
  mkdir -p "${CACHE_DIR}"
  if [[ ! -s "${CACHE_DIR}/sample.mp4" ]]; then
    printf "%b[*] Downloading sample test video...%b\n" "${CLR_HEADER}" "${CLR_RESET}"
    curl -sSL -o "${CACHE_DIR}/sample.mp4" "${SAMPLE_VIDEO_URL}"
  fi
  if [[ ! -s "${CACHE_DIR}/poster.png" ]]; then
    printf "%b[*] Fetching sample poster artwork...%b\n" "${CLR_HEADER}" "${CLR_RESET}"
    curl -fsSL -A "Mozilla/5.0" -o "${CACHE_DIR}/poster.png" "${SAMPLE_POSTER_URL}" || python3 -c "
import struct, zlib
def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
raw = b''.join([b'\x00' + bytes([218, 56, 56]) * 330 for _ in range(330)])
open('${CACHE_DIR}/poster.png', 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 330, 330, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))"
  fi
}

start_throttle_server() {
  local delay_sec="${1}" speed_kb="${2}" server_script="${CACHE_DIR}/throttle_server.py"
  cat <<'EOF' > "${server_script}"
import http.server, os, socketserver, sys, threading, time
from urllib.parse import urlparse, parse_qs

PORT, DELAY_SEC, BYTES_PER_SEC, CACHE_DIR = int(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3]) * 1024, sys.argv[4]
FILES = {"/poster.png": ("image/png", "poster.png"), "/slow_video.mp4": ("video/mp4", "sample.mp4")}
CHUNK = 32 * 1024
seen, lock = set(), threading.Lock()

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_HEAD(self): self._serve(head_only=True)
    def do_GET(self): self._serve(head_only=False)

    def _serve(self, head_only):
        u = urlparse(self.path)
        if u.path not in FILES: return self.send_error(404)
        ctype, fname = FILES[u.path]
        fpath = os.path.join(CACHE_DIR, fname)
        if not os.path.exists(fpath): return self.send_error(404)
        fsize = os.path.getsize(fpath)
        start, end = 0, fsize - 1
        rng = self.headers.get("Range")
        if u.path == "/slow_video.mp4" and rng and rng.startswith("bytes="):
            p = rng[6:].split("-")
            if p[0]: start = int(p[0])
            if len(p) > 1 and p[1]: end = int(p[1])
        if not head_only and u.path == "/slow_video.mp4" and DELAY_SEC > 0:
            tok = parse_qs(u.query).get("t", ["0"])[0]
            with lock:
                first = tok not in seen
                seen.add(tok)
            if first: time.sleep(DELAY_SEC)
        length = end - start + 1
        self.send_response(206 if (rng and u.path == "/slow_video.mp4") else 200)
        if rng and u.path == "/slow_video.mp4":
            self.send_header("Content-Range", f"bytes {start}-{end}/{fsize}")
        for k, v in [("Content-Type", ctype), ("Content-Length", str(length)), ("Accept-Ranges", "bytes"),
                     ("Access-Control-Allow-Origin", "*"), ("Connection", "close")]:
            self.send_header(k, v)
        self.end_headers()
        if head_only: return
        with open(fpath, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                buf = f.read(min(CHUNK, left))
                if not buf: break
                try:
                    self.wfile.write(buf); self.wfile.flush()
                    left -= len(buf)
                    if u.path == "/slow_video.mp4": time.sleep(CHUNK / BYTES_PER_SEC)
                except Exception: break

class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True
    allow_reuse_address = True

with Server(("0.0.0.0", PORT), Handler) as s: s.serve_forever()
EOF
  python3 -u "${server_script}" "${SERVER_PORT}" "${delay_sec}" "${speed_kb}" "${CACHE_DIR}" </dev/null >/dev/null 2>&1 &
  sleep 1
  printf "%s" "$!"
}

#######################################
# Builds and sends a single video stream notification payload.
# Arguments:
#   $1: endpoint_base, $2: media_host, $3: id, $4: pos, $5: title, $6: msg,
#   $7: poster_mode ("none" omits poster), $8: full_bleed, $9: live_badge,
#   $10: badge_pos, $11: badge_color, $12: width, $13: buffer_ms, $14: token
#######################################
send_stream_popup() {
  local endpoint_base="${1}" media_host="${2}" id="${3}" pos="${4}" title="${5}" msg="${6}"
  local mode="${7}" bleed="${8}" badge="${9}" bpos="${10}" bcolor="${11}" width="${12}" buf="${13}" tok="${14}"
  local poster_fields=""
  if [[ "${mode}" != "none" ]]; then
    poster_fields=$(printf '"poster": "http://%s:%s/poster.png?t=%s", "posterMode": "%s",' "${media_host}" "${SERVER_PORT}" "${tok}" "${mode}")
  fi
  local payload
  payload=$(cat <<EOF
{
  "id": "${id}",
  "duration": 18,
  "position": ${pos},
  "title": ${title},
  "message": ${msg},
  "mediaFullBleed": ${bleed},
  "borderRadius": 20,
  "liveBadge": ${badge},
  "liveBadgePosition": "${bpos}",
  "liveBadgeColor": "${bcolor}",
  "media": {
    "video": {
      "uri": "http://${media_host}:${SERVER_PORT}/slow_video.mp4?t=${tok}",
      ${poster_fields}
      "width": ${width},
      "bufferMs": ${buf}
    }
  }
}
EOF
)
  send_api_request "${endpoint_base}/notify" "${payload}" "Stream '${id}' (pos=${pos}, mode=${mode}, bleed=${bleed}, badge=${bpos})"
}

# shellcheck disable=SC2329
cleanup_slow_stream() {
  if [[ -n "${SERVER_PID:-}" ]]; then
    kill -9 "${SERVER_PID}" 2>/dev/null || true
    SERVER_PID=""
  fi
}

main() {
  local target_ip="${DEFAULT_IP}" initial_delay="5" speed_kb="350" buffer_ms="2000" position="0"
  local poster_mode="fill" live_badge="true" badge_pos="bottom_right" badge_color="#B3261E"
  local full_bleed="false" multi_mode="false" cancel_first="false" omit_poster="false"
  local adb_cmd="adb"
  [[ -n "${ANDROID_SERIAL:-}" ]] && adb_cmd="adb -s ${ANDROID_SERIAL}"

  while getopts "d:D:s:b:p:m:LB:C:FMPch" opt; do
    case "${opt}" in
      d) target_ip="${OPTARG}" ;;
      D) initial_delay="${OPTARG}" ;;
      s) speed_kb="${OPTARG}" ;;
      b) buffer_ms="${OPTARG}" ;;
      p) position="${OPTARG}" ;;
      m) poster_mode="${OPTARG}" ;;
      L) live_badge="false" ;;
      B) badge_pos="${OPTARG}" ;;
      C) badge_color="${OPTARG}" ;;
      F) full_bleed="true" ;;
      M) multi_mode="true" ;;
      P) omit_poster="true" ;;
      c) cancel_first="true" ;;
      h|?) usage ;;
    esac
  done
  shift $((OPTIND - 1))

  local endpoint_base="http://${target_ip}:${PORT}"
  local media_host="127.0.0.1"
  [[ "${target_ip}" != "127.0.0.1" && "${target_ip}" != "localhost" ]] && media_host="${target_ip}"

  setup_adb_forwarding "${target_ip}"
  [[ "${cancel_first}" == "true" ]] && cancel_all_popups "${endpoint_base}"
  prepare_assets
  $adb_cmd reverse "tcp:${SERVER_PORT}" "tcp:${SERVER_PORT}" >/dev/null 2>&1 || true

  local effective_speed="${speed_kb}"
  [[ "${multi_mode}" == "true" && "${speed_kb}" -lt 1200 ]] && effective_speed="1200"
  printf "%b[*] Starting throttled server on port %s (delay: %ss, speed: %s KB/s)...%b\n" \
    "${CLR_HEADER}" "${SERVER_PORT}" "${initial_delay}" "${effective_speed}" "${CLR_RESET}"
  SERVER_PID=$(start_throttle_server "${initial_delay}" "${effective_speed}")
  register_cleanup_hook cleanup_slow_stream

  local ts
  ts=$(date +%s)

  if [[ "${multi_mode}" == "true" ]]; then
    update_app_settings "${endpoint_base}" '{"maxPopups": 5, "mediaLoadingPlaceholder": true}'
    cancel_all_popups "${endpoint_base}"

    # Format: id|pos|title_json|msg_json|poster_mode|full_bleed|badge_pos|badge_color
    local cases=(
      'slow_fill|1|"1. fill"|"CENTER_CROP + LIVE top_left"|fill|false|top_left|#B3261E'
      'slow_fit|0|"2. fit"|"FIT_CENTER + LIVE top_right"|fit|false|top_right|#1565C0'
      'slow_stretch|3|"3. stretch + Bleed"|"FIT_XY + Full-Bleed + LIVE"|stretch|true|bottom_left|#2E7D32'
      'slow_center|2|"4. center"|"CENTER + LIVE bottom_right"|center|false|bottom_right|#6A1B9A'
      'slow_placeholder|4|null|null|none|true|bottom_right|#EF6C00'
    )
    local idx=1
    for entry in "${cases[@]}"; do
      IFS='|' read -r cid cpos ctitle cmsg cmode cbleed cbpos cbcol <<< "${entry}"
      send_stream_popup "${endpoint_base}" "${media_host}" "${cid}" "${cpos}" "${ctitle}" "${cmsg}" \
        "${cmode}" "${cbleed}" "true" "${cbpos}" "${cbcol}" 320 "${buffer_ms}" "${ts}_${idx}"
      idx=$((idx + 1))
    done

    sleep $(( initial_delay > 5 ? initial_delay + 16 : 21 ))
  else
    local mode="${poster_mode}"
    if [[ "${omit_poster}" == "true" ]]; then
      mode="none"
      update_app_settings "${endpoint_base}" '{"mediaLoadingPlaceholder": true}'
    fi
    send_stream_popup "${endpoint_base}" "${media_host}" "slow_single" "${position}" \
      "\"Slow Stream (${mode})\"" "\"Delay ${initial_delay}s | LIVE badge on 1st frame\"" \
      "${mode}" "${full_bleed}" "${live_badge}" "${badge_pos}" "${badge_color}" 480 "${buffer_ms}" "${ts}"
    sleep $(( initial_delay > 5 ? initial_delay + 15 : 20 ))
  fi

  printf "%b[DONE] Test finished.%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
}

main "$@"
