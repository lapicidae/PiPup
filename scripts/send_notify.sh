#!/bin/bash
#
# PiPup Functionality Tester.
# Sends notifications to a PiPup server with dynamic technical info and themes.

set -euo pipefail

#######################################
# GLOBALS
#######################################
readonly DEFAULT_IP='127.0.0.1'
readonly PORT='7979'
readonly DURATION=10
readonly STRESS_ITERATIONS=50

readonly WHEP_STATE_FILE='/dev/shm/pipup_whep.state'
readonly WHEP_TIMEOUT=600  # 10 minutes

readonly MEDIAMTX_IMAGE='bluenviron/mediamtx:latest'
readonly MEDIAMTX_API_PORT='9997'

# Set to "true" to use Go2RTC instead of MediaMTX
readonly USE_GO2RTC='true'
readonly GO2RTC_IMAGE='alexxit/go2rtc:latest'

# RTC Proxy Ports
readonly STREAM_RTSP_PORT='8555'
readonly STREAM_WHEP_PORT='8889'
readonly STREAM_WEBRTC_PORT='8556'

# Fallback Configuration
readonly WHEP_FALLBACK_PORT="${STREAM_WHEP_PORT}"

# Fully-formed mock SDP WebRTC Answer profile for response execution
readonly MOCK_SDP_ANSWER=$'v=0\r\no=- 1719830000 1719830000 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:0\r\na=rtcp-mux\r\na=setup:active\r\na=sendonly\r\na=ice-ufrag:mockufrag\r\na=ice-pwd:mockpwd_at_least_22_chars_long\r\na=fingerprint:sha-256 00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00\r\na=rtpmap:96 H264/90000\r\n'

# Style Variations
readonly DEFAULT_TITLE_SIZE=24
readonly DEFAULT_MSG_SIZE=14
readonly MIN_PADDING=16

readonly TEST_TYPES=("png" "jpg" "svg" "video" "whep" "web" "multipart" "message" "cancel")

# Theme definitions: "background;border;title_text;message_text"
declare -A THEMES
THEMES=(
  ["ocean"]="#001F3F;#0074D9;#7FDBFF;#FFFFFF"
  ["forest"]="#1B3022;#39FF14;#2ECC40;#F0FFF0"
  ["cyberpunk"]="#2B0035;#FF00FF;#00FFFF;#FFFFFF"
  ["warning"]="#410002;#FFDAD6;#FFB4AB;#FFDAD6"
  ["material_dark"]="#1C1B1F;#D8E4FF;#E6E1E5;#E6E1E5"
  ["lavender"]="#231233;#EFDBFF;#EFDBFF;#E6E1E5"
  ["terracotta"]="#2D1614;#FFB4AB;#FFDAD6;#F5D9D5"
  ["deep_teal"]="#002021;#4DB6AC;#B2DFDB;#E0F2F1"
  ["midnight_violet"]="#1D1B2A;#D0BCFF;#EADDFF;#E6E1E5"
  ["glass_azure"]="#CC1A1C1E;#7ABFFF;#D1E4FF;#E2E2E6"
  ["glass_emerald"]="#9900210B;#ACD3A5;#D1E6D3;#E1E3DF"
  ["glass_ruby"]="#CC370001;#FFB3AD;#FFDAD5;#F4DDDB"
  ["glass_sulfur"]="#99211D00;#E1E3BE;#F2F5D2;#E6E3D9"
  ["glass_orchid"]="#CC25192B;#E9B9FB;#F8D8FF;#E9E0E7"
)

# Extract keys for random selection
readonly THEME_KEYS=("${!THEMES[@]}")

# Test Assets
readonly PNG_URL="https://upload.wikimedia.org/wikipedia/commons/6/6a/PNG_Test.png"
readonly JPG_URL="https://upload.wikimedia.org/wikipedia/commons/2/28/JPG_Test.jpg"
readonly SVG_URL="https://upload.wikimedia.org/wikipedia/commons/b/bd/Test.svg"
readonly VIDEO_URL="https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_5MB.mp4"
readonly WEB_URL="https://opensource.org"

# Dynamic Mock WHEP URL (will be updated if server starts)
WHEP_URL="http://127.0.0.1:${WHEP_FALLBACK_PORT}/whep"

# Combined UTF-8 and Lorem Ipsum Stress Test (Ensures encoding stability)
readonly TEXT_UTF8="🚀 UTF-8 Test: Ää Öö Üü ß | € | 漢字 (Kanji) | עִבְรִית (Hebrew) | Special: \"Quoted Text\", 'Single Quotes', {Braces}, [Brackets], /Slashes/ & \Backslashes\. Symbols: ☢☣⚡🔥🌈 | 100% | 180°C."
readonly TEXT_LOREM_IPSUM="Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
readonly LONG_TEXT="${TEXT_UTF8}\n\n${TEXT_LOREM_IPSUM}"

# UI Coloring (ANSI Escape Sequences)
readonly CLR_RESET='\033[0m'
readonly CLR_HEADER='\033[1;35m'  # Bold Magenta
readonly CLR_TEST='\033[1;36m'    # Bold Cyan
readonly CLR_THEME='\033[34m'     # Blue
readonly CLR_PARAM='\033[90m'     # Dark Gray
readonly CLR_SUCCESS='\033[1;32m' # Bold Green
readonly CLR_ERROR='\033[1;31m'   # Bold Red


#######################################
# Helpers
#######################################

#######################################
# Resolves the corresponding JSON media payload string for a given test type.
# Arguments:
#   type: String, metadata category identifier (e.g., "png", "video").
# Outputs:
#   Echoes the JSON formatted string or "null".
#######################################
get_media_payload() {
  local type="${1}"
  local url="${2:-}"
  local fit="${3:-cover}"
  case "${type}" in
    png)   printf '%s' "{\"image\": {\"uri\": \"${url:-$PNG_URL}\", \"width\": 480}}" ;;
    jpg)   printf '%s' "{\"image\": {\"uri\": \"${url:-$JPG_URL}\", \"width\": 480}}" ;;
    svg)   printf '%s' "{\"image\": {\"uri\": \"${url:-$SVG_URL}\", \"width\": 480}}" ;;
    video) printf '%s' "{\"video\": {\"uri\": \"${url:-$VIDEO_URL}\", \"width\": 480}}" ;;
    whep)  printf '%s' "{\"whep\": {\"uri\": \"${url:-$WHEP_URL}\", \"width\": 640, \"videoFit\": \"${fit}\"}}" ;;
    web)   printf '%s' "{\"web\": {\"uri\": \"${url:-$WEB_URL}\", \"width\": 640, \"height\": 480}}" ;;
    *)     printf 'null' ;;
  esac
}

#######################################
# Selects a random theme from the associative array.
# Arguments:
#   None
# Outputs:
#   Echoes a semicolon-separated string: "bg;border;title_color;message_color"
#######################################
get_random_theme_colors() {
  local random_index=$((RANDOM % ${#THEME_KEYS[@]}))
  local theme_name="${THEME_KEYS[random_index]}"
  printf "%s" "${THEMES[$theme_name]}"
}

#######################################
# Prints a standardized, colorized table header for test results.
# Arguments:
#   None
# Outputs:
#   Writes the table header and separator line to stdout.
#######################################
print_table_header() {
  printf "${CLR_HEADER}%-12s | %-15s | %-76s | %-15s | %-6s${CLR_RESET}\n" \
    "TEST TYPE" "THEME" "STYLE PARAMETERS" "ENDPOINT" "HTTP"
  printf "${CLR_PARAM}%s${CLR_RESET}\n" \
    "--------------------------------------------------------------------------------------------------------------------------------------"
}

#######################################
# Parses the active WHEP background process state file.
# Globals:
#   WHEP_STATE_FILE
# Arguments:
#   None
# Outputs:
#   Writes space-separated PID and Port/Mode to stdout if valid.
#######################################
parse_whep_state() {
  if [[ -f "${WHEP_STATE_FILE}" ]]; then
    local state_data
    state_data=$(cat "${WHEP_STATE_FILE}" 2>/dev/null || printf '')
    if [[ "${state_data}" == *":"* ]]; then
      printf '%s %s' "${state_data%:*}" "${state_data#*:}"
    fi
  fi
}

#######################################
# Generates randomized layout configuration styling parameters.
# Globals:
#   MIN_PADDING
# Arguments:
#   type: String, metadata category identifier (e.g., "png", "whep").
# Outputs:
#   Prints space-separated values: radius border padding anim_type anim_dur fit
#######################################
get_random_style() {
  local type="${1}"
  local rand_radius=$(( RANDOM % 50 ))
  local rand_border=$(( RANDOM % 10 + 1 ))
  local rand_padding=$(( MIN_PADDING + (RANDOM % 25) ))
  local rand_anim_type=$(( RANDOM % 11 ))
  local rand_anim_duration=$(( 300 + RANDOM % 1201 ))

  local fit="cover"
  if [[ "${type}" == "whep" ]]; then
    local fits=("cover" "contain" "fill")
    fit="${fits[$(( RANDOM % 3 ))]}"
  fi

  printf '%s %s %s %s %s %s' "${rand_radius}" "${rand_border}" "${rand_padding}" "${rand_anim_type}" "${rand_anim_duration}" "${fit}"
}

#######################################
# Prints a unified structural execution row to standard output.
# Globals:
#   CLR_TEST
#   CLR_RESET
#   CLR_THEME
#   CLR_PARAM
#   CLR_SUCCESS
#   CLR_ERROR
# Arguments:
#   type: String, capitalized identifier.
#   theme: String, theme identification profile name.
#   style: String, layout metric information line.
#   target: String, IP routing endpoint address.
#   code: Integer, HTTP response status value.
# Outputs:
#   Writes colorized terminal row representation to stdout.
#######################################
print_result_row() {
  local type="${1}"
  local theme="${2}"
  local style="${3}"
  local target="${4}"
  local code="${5}"

  local status_color="${CLR_SUCCESS}"
  [[ "${code}" != "200" ]] && status_color="${CLR_ERROR}"

  printf "${CLR_TEST}%-12s${CLR_RESET} | ${CLR_THEME}%-15s${CLR_RESET} | ${CLR_PARAM}%-76s${CLR_RESET} | %-15s | ${status_color}%-6s${CLR_RESET}\n" \
    "${type}" "${theme}" "${style}" "${target}" "${code}"
}

#######################################
# Starts a persistent background WHEP service serving the video in a loop.
# Spawns MediaMTX or Go2RTC via Docker, or falls back to a netcat mock server.
# Globals:
#   GO2RTC_IMAGE
#   MEDIAMTX_IMAGE
#   STREAM_RTSP_PORT
#   STREAM_WHEP_PORT
#   USE_GO2RTC
#   VIDEO_URL
#   WHEP_FALLBACK_PORT
#   WHEP_STATE_FILE
#   WHEP_TIMEOUT
# Arguments:
#   None
# Outputs:
#   Writes status information to stdout.
#######################################
start_whep_service() {
  local host_ip
  host_ip=$(ip route get 1 2>/dev/null | awk '{print $7;exit}' || hostname -I | awk '{print $1}')

  local state_info
  state_info=$(parse_whep_state)
  if [[ -n "${state_info}" ]]; then
    local state_pid state_port
    read -r state_pid state_port <<< "${state_info}"
    if kill -0 "${state_pid}" 2>/dev/null; then
      if [[ "${USE_GO2RTC}" == "true" && "${state_port}" == "${STREAM_WHEP_PORT}" ]]; then
        WHEP_URL="http://${host_ip}:${STREAM_WHEP_PORT}/api/webrtc?src=mystream"
        return 0
      elif [[ "${USE_GO2RTC}" == "false" && "${state_port}" == "${STREAM_WHEP_PORT}" ]]; then
        WHEP_URL="http://${host_ip}:${STREAM_WHEP_PORT}/mystream/whep"
        return 0
      elif [[ "${state_port}" != "${STREAM_WHEP_PORT}" ]]; then
        WHEP_URL="http://${host_ip}:${state_port}/whep"
        return 0
      fi
    fi
    rm -f "${WHEP_STATE_FILE}"
  fi

  # Check if both docker and ffmpeg are installed for the real stream pipeline
  if command -v docker >/dev/null 2>&1 && command -v ffmpeg >/dev/null 2>&1; then
    printf "[SYSTEM] Spawning WebRTC/WHEP container and FFmpeg streaming pipeline...\n"

    # Define dynamic properties
    local container_image="${MEDIAMTX_IMAGE}"
    # Default mappings for MediaMTX
    local docker_opts=(
      "-p" "${STREAM_WHEP_PORT}:8889"
      "-p" "${STREAM_RTSP_PORT}:8554"
      "-p" "${MEDIAMTX_API_PORT}:9997"
      "-p" "1935:1935"
    )
    local container_args=()
    local stream_url="rtmp://${host_ip}:1935/mystream"
    local stream_format="flv"
    WHEP_URL="http://${host_ip}:${STREAM_WHEP_PORT}/mystream/whep"
    if [[ "${USE_GO2RTC}" == "true" ]]; then
      WHEP_URL="http://${host_ip}:${STREAM_WHEP_PORT}/api/webrtc?src=mystream"
    fi

    # Override properties if Go2RTC is selected
    if [[ "${USE_GO2RTC}" == "true" ]]; then
      container_image="${GO2RTC_IMAGE}"

      # For go2rtc:
      # - Map Host WHEP port to internal API port 1984
      # - Map Host RTSP port to internal 8554
      # - Map Host WebRTC port (8556) to internal 8555 (default)
      docker_opts=(
        "-p" "${STREAM_WHEP_PORT}:1984"
        "-p" "${STREAM_RTSP_PORT}:8554"
        "-p" "${STREAM_WEBRTC_PORT}:8555/tcp"
        "-p" "${STREAM_WEBRTC_PORT}:8555/udp"
      )
    fi

    (
      set +e

      # Store state indicating docker mode with the active HOST port
      printf "%s:%s" "$BASHPID" "${STREAM_WHEP_PORT}" > "${WHEP_STATE_FILE}"

      local container_name="webrtc_pipup_${BASHPID}"
      local ffmpeg_pid=""
      local config_file="/dev/shm/go2rtc_${BASHPID}.yaml"

      # shellcheck disable=SC2329
      cleanup_pipeline() {
        trap - SIGTERM SIGINT EXIT
        rm -f "${WHEP_STATE_FILE}"
        rm -f "${config_file}"

        if [[ -n "${ffmpeg_pid}" ]]; then
          kill "${ffmpeg_pid}" >/dev/null 2>&1 || true
        fi

        docker rm -f "${container_name}" >/dev/null 2>&1 &
        exit 0
      }
      trap cleanup_pipeline SIGTERM SIGINT EXIT

      # --- Robust Zombie Cleanup ---
      # Cleanly pipe container IDs to xargs to avoid running docker rm on empty arguments
      docker ps -qa --filter "name=webrtc_pipup_" | xargs -r docker rm -f >/dev/null 2>&1 || true
      pkill -f "nc -lp ${STREAM_WHEP_PORT}" >/dev/null 2>&1 || true
      sleep 0.5

      # Create Go2RTC config file to avoid CLI flag parsing issues with complex strings
      if [[ "${USE_GO2RTC}" == "true" ]]; then
        cat <<EOF > "${config_file}"
api:
  origin: "*"
webrtc:
  candidates:
    - "${host_ip}:${STREAM_WEBRTC_PORT}"
ffmpeg:
  reinput: "-re -i {input}"
streams:
  mystream: "ffmpeg:${VIDEO_URL}#video=h264#audio=aac#input=reinput"
log:
  level: debug
EOF
        docker_opts+=("-v" "${config_file}:/config/go2rtc.yaml")
        container_args=("-config" "/config/go2rtc.yaml")
      fi

      # Start container
      local run_cmd=("docker" "run" "--rm" "-d" "--name" "${container_name}" "${docker_opts[@]}" "${container_image}")
      if [[ "${USE_GO2RTC}" == "true" ]]; then
        run_cmd+=("/usr/local/bin/go2rtc" "${container_args[@]}")
      fi

      "${run_cmd[@]}" >/dev/null

      if ! docker ps --filter "name=^/${container_name}$" --format '{{.Names}}' | grep -qx "${container_name}"; then
        printf '[SYSTEM] Failed to start container (%s).\n' "${container_image}" >&2
        docker logs "${container_name}" 2>&1 || true
        return 1
      fi

      sleep 2

      # Stream dynamically ONLY if MediaMTX is used. Go2RTC handles it on-demand!
      if [[ "${USE_GO2RTC}" == "false" ]]; then
        ffmpeg -loglevel error -re -stream_loop -1 -i "${VIDEO_URL}" \
          -c:v libx264 -preset ultrafast -tune zerolatency -bf 0 -c:a aac \
          -f "${stream_format}" "${stream_url}" >/dev/null 2>&1 &
        ffmpeg_pid=$!
      fi

      sleep "${WHEP_TIMEOUT}" &
      wait $!
    ) &
    disown

    # --- Robust WHEP Live Detection ---
    printf "[SYSTEM] Waiting for stream endpoint to become live..."
    local retry=0
    local live=false
    while [ $retry -lt 30 ]; do
      sleep 1
      if [[ "${USE_GO2RTC}" == "true" ]]; then
        # Check if the stream name exists in the API output. Use -L for go2rtc redirects.
        local api_check
        api_check=$(curl -sL "http://${host_ip}:${STREAM_WHEP_PORT}/api/streams" || printf '000')
        if [[ "${api_check}" == *"mystream"* ]]; then
          live=true
          break
        fi
      else
        # For MediaMTX, we check the container logs for the "online" message.
        # Derive the container name using the subshell PID from the state file.
        local state_data
        state_data=$(cat "${WHEP_STATE_FILE}" 2>/dev/null || printf '')
        local c_pid="${state_data%:*}"
        if [[ -n "${c_pid}" ]]; then
          if docker logs "webrtc_pipup_${c_pid}" 2>&1 | grep -q "stream is available and online"; then
            live=true
            break
          fi
        fi
      fi

      printf "."
      ((retry += 1))
    done

    if [[ "${live}" == "true" ]]; then
      printf " READY\n"
    else
      printf " TIMEOUT\n"
      printf "[ERROR] Stream failed to become live within 30 seconds.\n" >&2
      return 1
    fi

    printf "[SYSTEM] Stream pipeline ready. Target WHEP URL: %s\n" "${WHEP_URL}"
    return 0
  fi

  # --- Fallback: Original Netcat Mock Server ---
  printf "[SYSTEM] Docker/FFmpeg missing. Falling back to basic netcat server...\n"

  local assigned_port="${WHEP_FALLBACK_PORT}"

  if (printf "" > "/dev/tcp/127.0.0.1/${assigned_port}") >/dev/null 2>&1; then
    local found=false
    local p
    for p in {8890..8990}; do
      if ! (printf "" > "/dev/tcp/127.0.0.1/${p}") >/dev/null 2>&1; then
        assigned_port=$p
        found=true
        break
      fi
    done
    if [[ "$found" == "false" ]]; then
       printf "[SYSTEM] Error: Could not find any free port for mock WHEP server.\n" >&2
       return 1
    fi
  fi

  printf "[SYSTEM] Spawning persistent WHEP mock server on port %s (timeout: %dm)...\n" \
    "${assigned_port}" $((WHEP_TIMEOUT / 60))

  (
    set +e
    local start_time
    start_time=$(date +%s)

    printf "%s:%s" "$BASHPID" "${assigned_port}" > "${WHEP_STATE_FILE}"

    trap 'rm -f "${WHEP_STATE_FILE}"; exit 0' SIGTERM SIGINT

    while true; do
      local now
      now=$(date +%s)
      if (( now - start_time > WHEP_TIMEOUT )); then break; fi

      {
        printf "HTTP/1.1 200 OK\r\n"
        printf "Content-Type: application/sdp\r\n"
        printf "Access-Control-Allow-Origin: *\r\n"
        printf "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n"
        printf "Access-Control-Allow-Headers: Content-Type, *\r\n"
        printf 'Content-Length: %s\r\n' "${#MOCK_SDP_ANSWER}"
        printf "Connection: close\r\n\r\n"
        printf "%s" "${MOCK_SDP_ANSWER}"
      } | timeout 2 nc -lp "${assigned_port}" -s 0.0.0.0 > /dev/null 2>&1 || true
    done
    rm -f "${WHEP_STATE_FILE}"
  ) &
  disown

  local wait_count=0
  while [[ ! -f "${WHEP_STATE_FILE}" && $wait_count -lt 10 ]]; do
    sleep 0.1
    ((wait_count++))
  done

  WHEP_URL="http://${host_ip}:${assigned_port}/whep"
}

#######################################
# Sets up ADB port forwarding based on target location or specific device.
# Resolves connected target network IP addresses or falls back to locally
# deployed emulators to prevent device collisions.
# Globals:
#   PORT: Integer, read-only system service port identifier.
#   target_ip: String, checked to determine matching mechanics.
# Arguments:
#   None
# Outputs:
#   Writes logging actions and port mapping diagnostics to stdout.
# Returns:
#   0 if setup succeeded or routing is already open.
#   1 if adb tool binary is missing or routing generation fails.
#######################################
setup_adb_forwarding() {
  if (printf '' > "/dev/tcp/127.0.0.1/${PORT}") >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v adb >/dev/null 2>&1; then
    printf "[SYSTEM] Port %s closed, adb missing.\n" "${PORT}"
    return 1
  fi

  local adb_cmd="adb"
  local adb_device=""

  # Attempt to resolve the specific ADB serial using the target IP
  if [[ "${target_ip}" != "127.0.0.1" && "${target_ip}" != "localhost" ]]; then
    local matched_device
    matched_device=$(adb devices | tail -n +2 | grep "^${target_ip}" | awk '{print $1}' | head -n 1)

    if [[ -n "${matched_device}" ]]; then
      adb_device="${matched_device}"
      printf "[SYSTEM] Auto-matched IP '%s' to ADB device: %s\n" "${target_ip}" "${adb_device}"
    else
      # Attempt automatic on-the-fly connection if device is offline
      printf "[SYSTEM] IP '%s' not found in active ADB list. Connection attempt... " "${target_ip}"
      if adb connect "${target_ip}:5555" >/dev/null 2>&1; then
        adb_device="${target_ip}:5555"
        printf "CONNECTED\n"
      else
        printf "FAILED\n"
      fi
    fi
  fi

  # Fallback: Handle multiple connected devices for localhost deployments
  if [[ -z "${adb_device}" ]]; then
    local device_count
    device_count=$(adb devices | tail -n +2 | grep -cv '^$')

    if [ "${device_count}" -gt 1 ]; then
      # If testing on localhost, try to prioritize local emulators first
      local local_emulator
      local_emulator=$(adb devices | tail -n +2 | grep -E '^(emulator-|127\.0\.0\.1|localhost)' | awk '{print $1}' | head -n 1)

      if [[ -n "${local_emulator}" ]]; then
        adb_device="${local_emulator}"
        printf "[SYSTEM] Multiple targets online. Prioritizing local target: %s\n" "${adb_device}"
      else
        # Strict Fallback: If no obvious local target matches, use the first available active slot
        adb_device=$(adb devices | tail -n +2 | head -n 1 | awk '{print $1}')
        printf "[SYSTEM] Multiple targets online. Defaulting to first device: %s\n" "${adb_device}"
      fi
    fi
  fi

  # Append serial routing if target identification succeeded
  if [[ -n "${adb_device}" ]]; then
    adb_cmd="adb -s ${adb_device}"
  fi

  printf "[SYSTEM] ADB: Forwarding tcp:%s via %s... " "${PORT}" "${adb_cmd}"
  $adb_cmd forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1 && printf "OK\n" || printf "FAILED\n"
}

#######################################
# Sends a request to clear the active notification queue on the server.
# Arguments:
#   target_ip: String, IP address of the PiPup server.
# Outputs:
#   Writes HTTP status results to stdout.
#######################################
send_cancel_request() {
  local target_ip="${1}"
  local endpoint="http://${target_ip}:${PORT}/cancel"

  printf "[SYSTEM] Sending CANCEL request to %s\n" "${target_ip}"
  local response
  response=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${endpoint}" || printf '000')
  printf "[RESULT] Cancel HTTP %s\n" "${response}"
}

#######################################
# Sends a JSON notification payload to the PiPup server.
# Arguments:
#   target_ip: String, IP address of the target server.
#   title: String, notification title text.
#   message: String, notification body text.
#   media_json: String, JSON formatted string containing the media object, or "null".
#   position: Integer, screen location index (0-4).
#   bg_color: String, background hex color code.
#   border_width: Integer, border thickness in pixels.
#   border_color: String, border hex color code.
#   title_color: String, title text hex color code.
#   msg_color: String, body text hex color code.
#   border_radius: Integer, layout corner rounding radius in pixels.
#   media_pos: Integer, relative media alignment index (0-3).
#   padding: Integer, inner layout padding in pixels (>= 16).
#   anim_type: Integer, animation type index (0-10).
#   anim_duration: Integer, animation duration in ms.
#   overwrite: Boolean, true or false.
# Outputs:
#   Writes test parameters and server response codes to stdout.
#######################################
send_json_notification() {
  local target_ip="${1}"
  local title="${2}"
  local message="${3}"
  local media_json="${4}"
  local position="${5}"
  local bg_color="${6}"
  local border_width="${7}"
  local border_color="${8}"
  local title_color="${9}"
  local msg_color="${10}"
  local border_radius="${11}"
  local media_pos="${12}"
  local padding="${13}"
  local anim_type="${14}"
  local anim_duration="${15}"
  local overwrite="${16:-false}"

  local endpoint="http://${target_ip}:${PORT}/notify"

  local escaped_title="${title//\\/\\\\}"
  escaped_title="${escaped_title//\"/\\\"}"

  local escaped_message="${message//\\/\\\\}"
  escaped_message="${escaped_message//\"/\\\"}"
  escaped_message="${escaped_message//$'\n'/\\n}"

  # printf "[TEST: %-10s] Pos: %s | MediaPos: %s | Padding: %sdp | Endpoint: %s\n" "${title}" "${position}" "${media_pos}" "${padding}" "${target_ip}"

  local json_payload
  json_payload=$(cat <<EOF
{
  "duration": ${DURATION},
  "position": ${position},
  "title": "${escaped_title}",
  "titleColor": "${title_color}",
  "titleSize": ${DEFAULT_TITLE_SIZE},
  "message": "${escaped_message}",
  "messageColor": "${msg_color}",
  "messageSize": ${DEFAULT_MSG_SIZE},
  "backgroundColor": "${bg_color}",
  "borderRadius": ${border_radius},
  "borderWidth": ${border_width},
  "borderColor": "${border_color}",
  "contentPadding": ${padding},
  "mediaPosition": ${media_pos},
  "animationType": ${anim_type},
  "animationDuration": ${anim_duration},
  "overwrite": ${overwrite},
  "media": ${media_json}
}
EOF
)

  local response
  response=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${endpoint}" \
    -H "Content-Type: application/json" \
    -d "${json_payload}" || printf "000")

  printf "%s" "${response}"
}

#######################################
# Sends an image notification using multipart/form-data.
# Arguments:
#   target_ip: String, IP address of the target server.
#   position: Integer, screen location index (0-4).
#   suffix: String, optional additional debug text to append to the layout body.
#   media_pos: Integer, relative media alignment index (0-3), defaults to 0.
#   padding: Integer, inner layout padding in pixels, defaults to 16.
#   anim_type: Integer, animation type index (0-10).
#   anim_duration: Integer, animation duration in ms.
#   overwrite: Boolean, true or false.
# Outputs:
#   Writes processing status and server HTTP codes to stdout.
# Returns:
#   0 if successful, 1 if the test asset download fails.
#######################################
send_multipart_test() {
  local target_ip="${1}"
  local position="${2}"
  local suffix="${3:-}"
  local media_pos="${4:-0}"
  local padding="${5:-16}"
  local anim_type="${6:-0}"
  local anim_duration="${7:-500}"
  local overwrite="${8:-false}"
  local endpoint="http://${target_ip}:${PORT}/notify"
  local temp_file="/dev/shm/pipup_test.png"

  local full_msg
  full_msg=$(printf "Mode: Form-Data\nPos: %s\nMediaPos: %s\nPadding: %s\nOverwrite: %s%b" "${position}" "${media_pos}" "${padding}" "${overwrite}" "${suffix}")

  curl -s --fail "${PNG_URL}" -o "${temp_file}" || return 1

  local response
  response=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${endpoint}" \
    -F "title=Multipart Test" \
    -F "message=${full_msg}" \
    -F "image=@${temp_file}" \
    -F "duration=${DURATION}" \
    -F "position=${position}" \
    -F "mediaPosition=${media_pos}" \
    -F "contentPadding=${padding}" \
    -F "animationType=${anim_type}" \
    -F "animationDuration=${anim_duration}" \
    -F "overwrite=${overwrite}" || printf '000')

  rm -f "${temp_file}"

  local style_info
  style_info=$(printf "Pos:%s MedPos:%s Rad:--px Bdr:--px Pad:%sdp Anim:%s (%sms) Overwrite:%s" \
    "${position}" "${media_pos}" "${padding}" "${anim_type}" "${anim_duration}" "${overwrite}")

  print_result_row "MULTIPART" "N/A (Form-Data)" "${style_info}" "${target_ip}" "${response}"
}

#######################################
# Prints script usage guidelines and exits.
# Arguments:
#   None
# Outputs:
#   Writes CLI options to stderr.
#######################################
usage() {
  cat <<EOF
Usage: ${0##*/} [-h host] [-t type] [-u url] [-a] [-l] [-o] [-c] [-s] [-k] [--help]
Options:
  -h    Target IP (default: ${DEFAULT_IP})
  -t    Test type: ${TEST_TYPES[*]}
  -u    Custom media URL (overrides default assets)
  -a    Run all standard tests in sequence
  -l    Add long text to messages
  -o    Overwrite the current notification
  -c    Immediately trigger a service-wide cancel request
  -s    Execute a high-frequency parallel stress test
  -k    Stop the active WHEP pipeline and server
  --help, -?  Show this help message and exit
EOF
  exit 1
}

#######################################
# Main script execution flow handler.
# Arguments:
#   Array of arguments passed to script invocation.
# Outputs:
#   Passes standard logs and structural test suite sequences to stdout.
#######################################
main() {
  if [[ "${1:-}" == "--help" || "${1:-}" == "-help" || "${1:-}" == "-?" ]]; then
    usage
  fi

  local target_ip="${DEFAULT_IP}"
  local test_type=""
  local custom_url=""
  local run_all="false"
  local use_long_text="false"
  local immediate_cancel="false"
  local run_stress="false"
  local kill_whep_pipeline="false"
  local overwrite="false"

  while getopts "h:t:u:alocks" opt; do
    case "${opt}" in
      h) target_ip="${OPTARG}" ;;
      t) test_type="${OPTARG}" ;;
      u) custom_url="${OPTARG}" ;;
      a) run_all="true" ;;
      l) use_long_text="true" ;;
      o) overwrite="true" ;;
      c) immediate_cancel="true" ;;
      k) kill_whep_pipeline="true" ;;
      s) run_stress="true" ;;
      *) usage ;;
    esac
  done

  # Handle Pipeline Termination (-k) Cleanly
  if [[ "${kill_whep_pipeline}" == "true" ]]; then
    local state_info
    state_info=$(parse_whep_state)
    if [[ -n "${state_info}" ]]; then
      local state_pid state_mode
      read -r state_pid state_mode <<< "${state_info}"
      printf "[SYSTEM] Stopping active WHEP pipeline (PID: %s, Port/Mode: %s)... " "${state_pid}" "${state_mode}"

      kill "${state_pid}" 2>/dev/null || true

      if command -v docker >/dev/null 2>&1; then
        docker ps -qa --filter "name=webrtc_pipup_" | xargs -r docker rm -f >/dev/null 2>&1 || true
      fi

      pkill -f "nc -lp ${state_mode}" >/dev/null 2>&1 || true
      rm -f "${WHEP_STATE_FILE}"
      printf "OK\n"
    else
      printf "[SYSTEM] No active WHEP pipeline or state file found.\n"
    fi
    return 0
  fi

  if [[ "${run_all}" == "true" && "${run_stress}" == "true" ]]; then
    printf "Error: Options -a (run all) and -s (stress test) are mutually exclusive.\n" >&2
    usage
  fi

  if [[ "${immediate_cancel}" == "true" ]]; then
    send_cancel_request "${target_ip}"
    return 0
  fi

  local suffix=""
  if [[ "${use_long_text}" == "true" ]]; then
    suffix="\n\n${LONG_TEXT}"
  fi

  if [[ "${target_ip}" =~ ^(localhost|127\.0\.0\.1)$ ]]; then
    setup_adb_forwarding || true
  fi

  # Start the background WHEP service for WebRTC/WHEP testing only if needed
  if [[ "${test_type}" == "whep" && -z "${custom_url}" ]] || [[ "${run_all}" == "true" ]] || [[ "${run_stress}" == "true" ]]; then
    start_whep_service
  fi

  #######################################
  # Wraps randomized styling configurations and maps variables to json dispatchers.
  # Arguments:
  #   type: String, metadata category identifier.
  #   title: String, notification header layout string.
  #   pos: Integer, screen quadrant target index.
  #   media: String, JSON escaped configuration block or null string.
  #######################################
  dispatch_test() {
    local type="${1}"
    local title="${2}"
    local pos="${3}"
    local media="${4}"
    local fit="${5:-}"

    local theme_name="${THEME_KEYS[$(( RANDOM % ${#THEME_KEYS[@]} ))]}"
    local theme_str="${THEMES[$theme_name]}"

    local bg border title_c msg_c
    IFS=';' read -r bg border title_c msg_c <<< "${theme_str}"

    local radius border_w padding anim_type anim_dur target_fit
    local style_data
    style_data=$(get_random_style "${type}")
    read -r radius border_w padding anim_type anim_dur target_fit <<< "${style_data}"
    [[ -z "${fit}" ]] && fit="${target_fit}"

    local rand_media_pos=$(( RANDOM % 4 ))

    local info_msg fit_label=""
    [[ -n "${fit}" ]] && fit_label=" | Fit: ${fit}"
    info_msg=$(printf "Theme: %s\nType: %s%s\nRadius: %spx | Border: %spx\nMediaPos: %s | Padding: %sdp\nAnim: %s (%sms)\nOverwrite: %s%b" \
      "${theme_name}" "${type}" "${fit_label}" "${radius}" "${border_w}" "${rand_media_pos}" "${padding}" \
      "${anim_type}" "${anim_dur}" "${overwrite}" "${suffix}")

    local response
    response=$(send_json_notification "${target_ip}" "${title}" "${info_msg}" "${media}" \
      "${pos}" "${bg}" "${border_w}" "${border}" "${title_c}" "${msg_c}" \
      "${radius}" "${rand_media_pos}" "${padding}" "${anim_type}" "${anim_dur}" "${overwrite}")

    local table_type="${type^^}"
    [[ -n "${fit}" ]] && table_type="${table_type}(${fit:0:1})"

    local style_info
    style_info=$(printf "Pos:%s MedPos:%s Rad:%spx Bdr:%spx Pad:%sdp Anim:%s (%sms) Overwrite:%s" \
      "${pos}" "${rand_media_pos}" "${radius}" "${border_w}" "${padding}" \
      "${anim_type}" "${anim_dur}" "${overwrite}")

    print_result_row "${table_type}" "${theme_name}" "${style_info}" "${target_ip}" "${response}"
  }

  #######################################
  # Executes a rapid concurrent background stress test against the endpoint.
  # Globals:
  #   STRESS_ITERATIONS: Integer, total parallel payloads to dispatch.
  #   TEST_TYPES: Array, available test configurations.
  # Arguments:
  #   target_ip: String, IP address of the target server.
  #   suffix: String, payload text modifiers.
  # Outputs:
  #   Writes stress initialization metrics and test results to stdout.
  #######################################
  run_stress_test() {
    local target_ip="${1}"
    local suffix="${2}"

    printf "[STRESS] Initiating parallel bombardment of %d requests...\n" "${STRESS_ITERATIONS}"
    printf "[STRESS] Spawning background jobs... "

    printf "DONE\n"
    printf "[STRESS] Awaiting incoming responses from server...\n\n"
    print_table_header

    local num_types=${#TEST_TYPES[@]}
    local i
    for ((i = 0; i < STRESS_ITERATIONS; i++)); do
      local seed
      seed=$(date +%N | sed 's/^0*//')
      local rand_idx=$(((seed + i) % num_types))
      local type="${TEST_TYPES[rand_idx]}"

      [[ "${type}" == "cancel" ]] && continue

      local radius border_w padding anim_type anim_dur fit
      local style_data
      style_data=$(get_random_style "${type}")
      read -r radius border_w padding anim_type anim_dur fit <<< "${style_data}"

      if [[ "${type}" == "multipart" ]]; then
        send_multipart_test "${target_ip}" "$(( RANDOM % 5 ))" "${suffix}" \
          "$(( RANDOM % 4 ))" "${padding}" "${anim_type}" \
          "${anim_dur}" "${overwrite}" &
        continue
      fi

      local media
      media=$(get_media_payload "${type}" "${custom_url}" "${fit}")
      dispatch_test "${type}" "Stress #${i}" "$(( RANDOM % 5 ))" "${media}" "${fit}" &
    done

    wait
    printf "\n[STRESS] Execution wave completed successfully.\n"
  }

  # --- Execution Flows ---

  if [[ "${run_all}" == "true" ]]; then
    local pos_list
    mapfile -t pos_list < <(printf "%s\n" 0 1 2 3 4 1 2 3 | shuf)

    print_table_header

    local type
    local idx=0
    for type in "${TEST_TYPES[@]}"; do
      [[ "${type}" == "cancel" ]] && continue

      if [[ "${type}" == "multipart" ]]; then
        local radius border_w padding anim_type anim_dur fit
        read -r radius border_w padding anim_type anim_dur fit <<< "$(get_random_style "${type}")"

        send_multipart_test "${target_ip}" "${pos_list[idx]}" "${suffix}" "$((RANDOM % 4))" "${padding}" "${anim_type}" "${anim_dur}" "${overwrite}"
      else
        local radius border_w padding anim_type anim_dur fit
        read -r radius border_w padding anim_type anim_dur fit <<< "$(get_random_style "${type}")"

        local media
        media=$(get_media_payload "${type}" "${custom_url}" "${fit}")
        dispatch_test "${type}" "${type^^} Test" "${pos_list[idx]}" "${media}" "${fit}"
      fi

      idx=$((idx + 1))
      sleep "$((DURATION - 1))"
    done
    return 0
  fi

  if [[ "${run_stress}" == "true" ]]; then
    run_stress_test "${target_ip}" "${suffix}"
    return 0
  fi

  # Single Test Case Execution
  [[ -z "${test_type}" ]] && test_type="message"

  local radius border_w padding anim_type anim_dur fit
  read -r radius border_w padding anim_type anim_dur fit <<< "$(get_random_style "${test_type}")"

  if [[ "${test_type}" == "multipart" ]]; then
    print_table_header
    send_multipart_test "${target_ip}" 0 "${suffix}" "$((RANDOM % 4))" "${padding}" "${anim_type}" "${anim_dur}" "${overwrite}"
  elif [[ "${test_type}" == "cancel" ]]; then
    local media
    media=$(get_media_payload "png")
    print_table_header
    dispatch_test "cancel" "Abort Test" 0 "${media}"
    sleep 2
    send_cancel_request "${target_ip}"
  else
    if [[ ! " ${TEST_TYPES[*]} " == *" ${test_type} "* ]]; then
      printf "Test '%s' not recognized.\n" "${test_type}" >&2
      usage
    fi

    local media
    media=$(get_media_payload "${test_type}" "${custom_url}" "${fit}")

    if [[ "${test_type}" == "whep" && -z "${custom_url}" ]]; then
      local state_info
      state_info=$(parse_whep_state)
      if [[ -n "${state_info}" ]]; then
        local state_pid state_port
        read -r state_pid state_port <<< "${state_info}"
        if docker ps -q --filter "name=webrtc_pipup_" >/dev/null 2>&1; then
          printf "[SYSTEM] WebRTC Pipeline container is active (Engine Port: %s)\n\n" "${state_port}"
        else
          printf "[SYSTEM] Fallback WHEP server is active in background (PID: %s, Port: %s)\n\n" "${state_pid}" "${state_port}"
        fi
      fi
    fi
    print_table_header
    dispatch_test "${test_type}" "${test_type^^} Test" 0 "${media}" "${fit}"
  fi
}

main "$@"
