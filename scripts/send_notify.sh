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
STRESS_ITERATIONS=50

readonly WHEP_STATE_FILE='/dev/shm/pipup_whep.state'
WHEP_TIMEOUT=600  # 10 minutes

readonly MEDIAMTX_IMAGE='bluenviron/mediamtx:latest'
readonly MEDIAMTX_API_PORT='9997'

# Set to "true" to use Go2RTC instead of MediaMTX
readonly USE_GO2RTC='true'
readonly GO2RTC_IMAGE='alexxit/go2rtc:latest'

if [[ "${USE_GO2RTC}" == "true" ]]; then
  ENGINE_NAME="Go2RTC"
  ENGINE_IMAGE="${GO2RTC_IMAGE}"
else
  ENGINE_NAME="MediaMTX"
  ENGINE_IMAGE="${MEDIAMTX_IMAGE}"
fi
readonly ENGINE_NAME ENGINE_IMAGE

# RTC Proxy Ports
readonly STREAM_RTSP_PORT='8555'
readonly STREAM_WHEP_PORT='8889'
readonly STREAM_WEBRTC_PORT='8556'

# Memory Monitoring
readonly MEM_LOG_FILE='/dev/shm/pipup_mem.csv'
readonly MEM_PACKAGE='nl.rogro82.pipup'
readonly MONITOR_TEST_BUFFER=2
readonly MONITOR_SETUP_BUFFER=10
monitor_pid=""
MONITOR_START_TIME=0

# Fallback Configuration
readonly WHEP_FALLBACK_PORT="${STREAM_WHEP_PORT}"

# Fully-formed mock SDP WebRTC Answer profile for response execution
readonly MOCK_SDP_ANSWER=$'v=0\r\no=- 1719830000 1719830000 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:0\r\na=rtcp-mux\r\na=setup:active\r\na=sendonly\r\na=ice-ufrag:mockufrag\r\na=ice-pwd:mockpwd_at_least_22_chars_long\r\na=fingerprint:sha-256 00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00\r\na=rtpmap:96 H264/90000\r\n'

# Style Variations
readonly MIN_PADDING=16

readonly TEST_TYPES=("png" "jpg" "webp" "svg" "video" "whep" "web" "multipart" "message" "cancel")

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

readonly POS_NAMES=("Top Right" "Top Left" "Bottom Right" "Bottom Left" "Center")
readonly ALIGN_NAMES=("Left" "Center" "Right")

# Extract keys for random selection
readonly THEME_KEYS=("${!THEMES[@]}")

# Test Assets
readonly JPG_URL="https://picsum.photos/427/240.jpg"
PNG_URL=$(printf 'https://robohash.org/hash_%s.png?size=427x240' "$RANDOM")
readonly PNG_URL
readonly SVG_URL="https://upload.wikimedia.org/wikipedia/commons/1/16/Eye_svg.svg"
readonly VIDEO_URL="https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_5MB.mp4"
readonly WEB_URL="https://opensource.org"
readonly WEBP_URL="https://picsum.photos/427/240.webp"

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
readonly CLR_MONITOR='\033[1;33m' # Bold Yellow


#######################################
# Helpers
#######################################

#######################################
# Internal helper to extract values from simple JSON objects using sed.
# Arguments:
#   json: String containing basic JSON object.
#   key: String, the field name to extract.
# Outputs:
#   Writes the extracted value to STDOUT.
#######################################
extract_json_val() {
  local json="${1:-}"
  local key="${2:-}"
  [[ -z "${json}" || -z "${key}" ]] && return

  # Use sed to find "key":"value" or "key":value
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\?\([^\",}]*\)\"\?.*/\1/p"
}

#######################################
# Internal helper to extract memory values robustly from dumpsys output.
# Arguments:
#   data: String containing dumpsys meminfo output.
#   label: String, the label to look for (e.g. "TOTAL PSS:").
# Outputs:
#   Writes the numeric value to STDOUT.
#######################################
get_mem_val() {
  local data="${1:-}"
  local label="${2:-}"
  [[ -z "${data}" || -z "${label}" ]] && printf "0\n" && return

  local line
  line=$(printf '%s\n' "${data}" | grep "${label}" | head -n 1 || true)
  [[ -z "${line}" ]] && printf "0\n" && return

  local val=${line#*"${label}"}
  val="${val#"${val%%[![:space:]]*}"}"
  val="${val%%[[:space:]]*}"
  val="${val//[!0-9]/}"

  printf '%s\n' "${val:-0}"
}

#######################################
# Background function to monitor device memory usage.
# Globals:
#   MEM_PACKAGE
#   MEM_LOG_FILE
# Arguments:
#   duration: Integer, monitoring time in seconds.
#   adb_cmd: String, the full adb command with serial.
# Outputs:
#   Writes CSV entries to MEM_LOG_FILE.
#######################################
monitor_memory() {
  local duration="${1}"
  local adb_cmd="${2}"
  local interval=2

  local end=$((SECONDS + duration))
  while [ $SECONDS -lt $end ]; do
    local timestamp
    timestamp=$(date +"%H:%M:%S")
    local meminfo
    meminfo=$(${adb_cmd} shell dumpsys meminfo "${MEM_PACKAGE}" 2>/dev/null || printf "")

    if [[ -n "${meminfo}" ]]; then
      local total_pss java_heap native_heap
      total_pss=$(get_mem_val "${meminfo}" "TOTAL PSS:")
      java_heap=$(get_mem_val "${meminfo}" "Java Heap:")
      native_heap=$(get_mem_val "${meminfo}" "Native Heap:")

      if [[ "${total_pss}" != "0" ]]; then
        printf "%s,%s,%s,%s\n" "${timestamp}" "${total_pss}" "${java_heap}" "${native_heap}" >> "${MEM_LOG_FILE}"
      fi
    fi
    sleep "${interval}"
  done
  printf "\n%b[SYSTEM] Monitoring duration reached (%ss).%b\n" "${CLR_MONITOR}" "${duration}" "${CLR_RESET}"
}

#######################################
# Analyzes the collected memory log and prints a summary.
# Globals:
#   MEM_LOG_FILE
#   CLR_MONITOR
#   CLR_RESET
#   CLR_SUCCESS
# Arguments:
#   None
# Outputs:
#   Memory statistics summary to STDOUT
#######################################
print_memory_summary() {
  [[ ! -f "${MEM_LOG_FILE}" ]] && return

  local max_pss=0 max_java=0 max_native=0
  local first_pss=0 last_pss=0
  local count=0

  format_mb() {
    local raw="${1:-0}"
    local val=${raw//[!0-9]/}
    val=${val:-0}
    local integer=$(( val / 1024 ))
    local decimal=$(( (val % 1024) * 100 / 1024 ))
    printf "%6d.%02d MB" "${integer}" "${decimal}"
  }

  printf "\n${CLR_MONITOR}[ANALYSIS] Memory Statistics (from %s):${CLR_RESET}\n" "${MEM_LOG_FILE}"

  while IFS=',' read -r _ pss_val java_val native_val || [[ -n "${pss_val}" ]]; do
    [[ "${pss_val}" == "TotalPSS" || -z "${pss_val}" ]] && continue
    local pss=${pss_val//[!0-9]/}
    local java=${java_val//[!0-9]/}
    local native=${native_val//[!0-9]/}
    [[ -z "${pss}" ]] && continue

    count=$(( count + 1 ))
    if [ "${pss}" -gt "${max_pss}" ]; then max_pss="${pss}"; fi
    if [ "${java}" -gt "${max_java}" ]; then max_java="${java}"; fi
    if [ "${native}" -gt "${max_native}" ]; then max_native="${native}"; fi

    last_pss="${pss}"
    if [ "${count}" -eq 1 ]; then first_pss="${pss}"; fi
  done < "${MEM_LOG_FILE}"

  if [[ $count -gt 0 ]]; then
    printf "  - Peak Total RAM:   " ; format_mb "$max_pss" ; printf "\n"
    printf "  - Java Heap (Peak): " ; format_mb "$max_java" ; printf "\n"
    printf "  - Native Heap (Pk): " ; format_mb "$max_native" ; printf "\n"
    printf "  - Recovery:         " ; format_mb "$last_pss" ; printf " (Baseline: " ; format_mb "$first_pss" ; printf ")\n"

    if [[ $(( last_pss )) -le $(( first_pss * 125 / 100 )) ]]; then
      printf "  - Health Status:    %bPASSED (Stable)%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
    else
      printf "  - Health Status:    %bWARNING (High Retention)%b\n" "${CLR_MONITOR}" "${CLR_RESET}"
    fi
  else
    printf "  - No data collected for analysis.\n"
  fi
}

#######################################
# Finishes monitoring, waits for recovery if needed, and prints summary.
# Globals:
#   MONITOR_START_TIME
#   CLR_MONITOR
#   CLR_RESET
# Arguments:
#   is_mon: Boolean string ("true"/"false") indicating if monitor is active.
#   mon_pid: Integer, the PID of the background monitor process.
#   duration: Integer, the original calculated total duration.
# Outputs:
#   Status messages and final analysis summary to STDOUT.
#######################################
finish_monitoring() {
  local is_mon="${1}"
  local mon_pid="${2}"
  local duration="${3}"

  [[ "${is_mon}" != "true" ]] && return

  local now
  now=$(date +%s)
  local target_end=$(( MONITOR_START_TIME + duration ))
  local remaining=$(( target_end - now ))

  if [ "${remaining}" -gt 0 ]; then
    local h=$((remaining / 3600))
    local m=$(( (remaining % 3600) / 60 ))
    local s=$((remaining % 60))
    local time_str=""
    [[ $h -gt 0 ]] && time_str+="${h}h "
    [[ $m -gt 0 || $h -gt 0 ]] && time_str+="${m}m "
    time_str+="${s}s"

    printf "\n${CLR_MONITOR}[SYSTEM] Waiting for popups to finish and memory to stabilize (%s)...${CLR_RESET}\n" "${time_str}"
    sleep "${remaining}"
  fi

  printf "[SYSTEM] Processing final monitoring data...\n"
  kill "${mon_pid}" 2>/dev/null || true
  printf "%b[SYSTEM] Monitoring finished.%b\n" "${CLR_MONITOR}" "${CLR_RESET}"
  print_memory_summary
}

#######################################
# Signal and exit handler to ensure background processes are cleaned up.
# Globals:
#   monitor_pid
#   CLR_ERROR
#   CLR_RESET
#   CLR_SUCCESS
# Arguments:
#   opt_mode: Optional string, "INT" for interrupt mode.
# Outputs:
#   Cleanup status messages to STDOUT.
# Returns:
#   1 if interrupted, 0 otherwise.
#######################################
cleanup_all() {
  trap - SIGINT SIGTERM EXIT
  printf "\n%b[SYSTEM] Interrupt/Exit signal received. Cleaning up background jobs...%b\n" "${CLR_ERROR}" "${CLR_RESET}"

  local pids
  pids=$(jobs -p)
  if [[ -n "${pids}" ]]; then
    # shellcheck disable=SC2086
    kill ${pids} 2>/dev/null || true
  fi

  [[ -n "${monitor_pid:-}" ]] && kill "${monitor_pid}" 2>/dev/null || true
  stop_whep_service true

  printf "%b[SYSTEM] Cleanup complete. Goodbye.%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
  [[ "${1:-}" == "INT" ]] && exit 1 || exit 0
}

#######################################
# Formats the current WHEP_TIMEOUT for display.
# Globals:
#   WHEP_TIMEOUT
# Arguments:
#   None
# Outputs:
#   Writes a formatted string (e.g. "10 min") to STDOUT.
#######################################
get_timeout_display() {
  if [[ "${WHEP_TIMEOUT}" -ge 86400 ]]; then
    printf "infinite/24h"
  else
    printf "%d min" "$((WHEP_TIMEOUT / 60))"
  fi
}

#######################################
# Resolves the corresponding JSON media payload string for a given test type.
# Arguments:
#   type: String category identifier (e.g., "png", "video").
#   url: Optional string custom URL.
#   fit: Optional string video fit mode (default: "cover").
# Outputs:
#   Writes the JSON formatted string or "null" to STDOUT.
#######################################
get_media_payload() {
  local type="${1}"
  local url="${2:-}"
  local fit="${3:-cover}"
  local cache_field=""
  [[ "${USE_CACHE}" == "false" ]] && cache_field=", \"cache\": false"

  case "${type}" in
    png)   printf '%s' "{\"image\": {\"uri\": \"${url:-$PNG_URL}\", \"width\": 480${cache_field}}}" ;;
    jpg)   printf '%s' "{\"image\": {\"uri\": \"${url:-$JPG_URL}\", \"width\": 480${cache_field}}}" ;;
    webp)  printf '%s' "{\"image\": {\"uri\": \"${url:-$WEBP_URL}\", \"width\": 480${cache_field}}}" ;;
    svg)   printf '%s' "{\"image\": {\"uri\": \"${url:-$SVG_URL}\", \"width\": 480${cache_field}}}" ;;
    video) printf '%s' "{\"video\": {\"uri\": \"${url:-$VIDEO_URL}\", \"width\": 480}}" ;;
    whep)  printf '%s' "{\"whep\": {\"uri\": \"${url:-$WHEP_URL}\", \"width\": 640, \"videoFit\": \"${fit}\"}}" ;;
    web)   printf '%s' "{\"web\": {\"uri\": \"${url:-$WEB_URL}\", \"width\": 640, \"height\": 480${cache_field}}}" ;;
    *)     printf 'null' ;;
  esac
}

#######################################
# Selects a random theme from the associative array.
# Globals:
#   THEME_KEYS
#   THEMES
# Arguments:
#   None
# Outputs:
#   Writes semicolon-separated colors to STDOUT.
#######################################
get_random_theme_colors() {
  local random_index=$((RANDOM % ${#THEME_KEYS[@]}))
  local theme_name="${THEME_KEYS[random_index]}"
  printf "%s" "${THEMES[$theme_name]}"
}

#######################################
# Prints a standardized, colorized table header for test results.
# Globals:
#   CLR_HEADER
#   CLR_RESET
#   CLR_PARAM
# Arguments:
#   None
# Outputs:
#   Writes the table header to STDOUT.
#######################################
print_table_header() {
  printf "${CLR_HEADER}%-12s | %-15s | %-76s | %-15s | %-6s | %-20s${CLR_RESET}\n" \
    "TEST TYPE" "THEME" "STYLE PARAMETERS" "ENDPOINT" "HTTP" "JSON STATUS"
  printf "${CLR_PARAM}%s${CLR_RESET}\n" \
    "--------------------------------------------------------------------------------------------------------------------------------------------------------"
}

#######################################
# Parses the active WHEP background process state file.
# Globals:
#   WHEP_STATE_FILE
# Arguments:
#   None
# Outputs:
#   Writes space-separated PID and Port/Mode to STDOUT.
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
#   RANDOM
#   MIN_PADDING
# Arguments:
#   type: String category identifier.
# Outputs:
#   Writes space-separated values (radius border padding etc.) to STDOUT.
#######################################
get_random_style() {
  local type="${1}"
  local rand_radius=$(( RANDOM % 50 ))
  local rand_border=$(( RANDOM % 10 + 1 ))
  local rand_padding=$(( MIN_PADDING + (RANDOM % 25) ))
  local rand_anim_type=$(( RANDOM % 11 ))
  local rand_anim_duration=$(( 300 + RANDOM % 1201 ))
  local rand_title_size=$(( 18 + RANDOM % 20 ))
  local rand_msg_size=$(( 12 + RANDOM % 10 ))

  local fit="cover"
  if [[ "${type}" == "whep" ]]; then
    local fits=("cover" "contain" "fill")
    fit="${fits[$(( RANDOM % 3 ))]}"
  fi

  printf '%s %s %s %s %s %s %s %s' "${rand_radius}" "${rand_border}" "${rand_padding}" \
    "${rand_anim_type}" "${rand_anim_duration}" "${fit}" "${rand_title_size}" "${rand_msg_size}"
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
#   theme: String theme name.
#   style: String info line.
#   target: String IP address.
#   result: String "CODE|BODY" response.
# Outputs:
#   Writes a colorized terminal row to STDOUT.
#######################################
print_result_row() {
  local type="${1}"
  local theme="${2}"
  local style="${3}"
  local target="${4}"
  local result="${5}"

  local code="${result%%|*}"
  local body="${result#*|}"
  [[ "${code}" == "${body}" ]] && body="" # No body returned

  local status
  status=$(extract_json_val "${body}" "status")
  local message
  message=$(extract_json_val "${body}" "message")

  local status_color="${CLR_SUCCESS}"
  if [[ "${code}" != "200" || "${status}" == "Error" ]]; then
    status_color="${CLR_ERROR}"
  fi

  local status_display="N/A"
  if [[ -n "${status}" ]]; then
    status_display="${status}: ${message}"
  fi

  printf "${CLR_TEST}%-12s${CLR_RESET} | ${CLR_THEME}%-15s${CLR_RESET} | ${CLR_PARAM}%-76s${CLR_RESET} | %-15s | ${status_color}%-6s${CLR_RESET} | ${status_color}%-20s${CLR_RESET}\n" \
    "${type}" "${theme}" "${style}" "${target}" "${code}" "${status_display}"
}

#######################################
# Stops the active background WHEP service and cleans up Docker/Netcat.
# Arguments:
#   silent: Optional boolean string (default: false) to suppress status info.
# Outputs:
#   Writes status information to STDOUT.
#######################################
stop_whep_service() {
  local silent="${1:-false}"
  local state_info
  state_info=$(parse_whep_state)

  if [[ -n "${state_info}" ]]; then
    local state_pid state_mode
    read -r state_pid state_mode <<< "${state_info}"

    [[ "${silent}" == "false" ]] && printf "[SYSTEM] Stopping active WHEP pipeline (PID: %s, Port/Mode: %s)... " "${state_pid}" "${state_mode}"

    kill "${state_pid}" 2>/dev/null || true

    if command -v docker >/dev/null 2>&1; then
      docker ps -qa --filter "name=webrtc_pipup_" | xargs -r docker rm -f >/dev/null 2>&1 || true
    fi

    pkill -f "nc -lp ${state_mode}" >/dev/null 2>&1 || true
    rm -f "${WHEP_STATE_FILE}"

    [[ "${silent}" == "false" ]] && printf "OK\n"
  else
    [[ "${silent}" == "false" ]] && printf "[SYSTEM] No active WHEP pipeline or state file found.\n"
  fi
}

#######################################
# Starts a persistent background WHEP service serving a video loop.
# Globals:
#   ENGINE_IMAGE
#   ENGINE_NAME
#   STREAM_RTSP_PORT
#   STREAM_WHEP_PORT
#   USE_GO2RTC
#   VIDEO_URL
#   WHEP_FALLBACK_PORT
#   WHEP_STATE_FILE
#   WHEP_TIMEOUT
# Arguments:
#   force_restart: Optional boolean string (default: false).
# Outputs:
#   Writes status information to STDOUT.
# Returns:
#   0 on success, 1 on error.
#######################################
start_whep_service() {
  local force_restart="${1:-false}"
  local host_ip
  host_ip=$(ip route get 1 2>/dev/null | awk '{print $7;exit}' || hostname -I | awk '{print $1}')

  local state_info
  state_info=$(parse_whep_state)
  if [[ -n "${state_info}" ]]; then
    if [[ "${force_restart}" == "true" ]]; then
      stop_whep_service false
      printf "[SYSTEM] Restarting %s server with new configuration...\n" "${ENGINE_NAME}"
    else
      local state_pid state_port
      read -r state_pid state_port <<< "${state_info}"
      if kill -0 "${state_pid}" 2>/dev/null; then
        if [[ "${USE_GO2RTC}" == "true" && "${state_port}" == "${STREAM_WHEP_PORT}" ]]; then
          WHEP_URL="http://${host_ip}:${STREAM_WHEP_PORT}/api/webrtc?src=mystream"
          printf "[SYSTEM] Reusing existing %s WebRTC server (Port: %s)\n" "${ENGINE_NAME}" "${state_port}"
          return 0
        elif [[ "${USE_GO2RTC}" == "false" && "${state_port}" == "${STREAM_WHEP_PORT}" ]]; then
          WHEP_URL="http://${host_ip}:${STREAM_WHEP_PORT}/mystream/whep"
          printf "[SYSTEM] Reusing existing %s WebRTC server (Port: %s)\n" "${ENGINE_NAME}" "${state_port}"
          return 0
        elif [[ "${state_port}" != "${STREAM_WHEP_PORT}" ]]; then
          WHEP_URL="http://${host_ip}:${state_port}/whep"
          printf "[SYSTEM] Reusing existing fallback WebRTC server (Port: %s)\n" "${state_port}"
          return 0
        fi
      fi
      rm -f "${WHEP_STATE_FILE}"
    fi
  fi

  # Check if both docker and ffmpeg are installed for the real stream pipeline
  if command -v docker >/dev/null 2>&1 && command -v ffmpeg >/dev/null 2>&1; then
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

    local extra_msg=""
    [[ "${USE_GO2RTC}" == "false" ]] && extra_msg=" and FFmpeg streaming pipeline"

    printf "[SYSTEM] Spawning WebRTC/WHEP container (using %s, timeout: %s)%s...\n" \
      "${ENGINE_NAME}" "$(get_timeout_display)" "${extra_msg}"

    (
      set +e

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
      local run_cmd=("docker" "run" "--rm" "-d" "--name" "${container_name}" "${docker_opts[@]}" "${ENGINE_IMAGE}")
      if [[ "${USE_GO2RTC}" == "true" ]]; then
        run_cmd+=("/usr/local/bin/go2rtc" "${container_args[@]}")
      fi

      "${run_cmd[@]}" >/dev/null

      if ! docker ps --filter "name=^/${container_name}$" --format '{{.Names}}' | grep -qx "${container_name}"; then
        printf '[SYSTEM] Failed to start container (%s).\n' "${ENGINE_IMAGE}" >&2
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
    local whep_pid=$!
    printf "%s:%s" "$whep_pid" "${STREAM_WHEP_PORT}" > "${WHEP_STATE_FILE}"
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
      retry=$((retry + 1))
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

  printf "[SYSTEM] Spawning persistent WHEP mock server on port %s (timeout: %s)...\n" \
    "${assigned_port}" "$(get_timeout_display)"

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
    wait_count=$((wait_count + 1))
  done

  WHEP_URL="http://${host_ip}:${assigned_port}/whep"
}

#######################################
# Sets up ADB port forwarding based on target location or specific device.
# Globals:
#   PORT
#   target_ip
# Arguments:
#   None
# Outputs:
#   Writes logging actions and port mapping diagnostics to STDOUT.
# Returns:
#   0 on success, 1 on error.
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
# Globals:
#   PORT
# Arguments:
#   target_ip: String IP address of the PiPup server.
# Outputs:
#   Writes HTTP status results to STDOUT.
#######################################
send_cancel_request() {
  local target_ip="${1}"
  local endpoint="http://${target_ip}:${PORT}/cancel"

  printf "[SYSTEM] Sending CANCEL request to %s\n" "${target_ip}"
  local response
  response=$(curl -s -w "\n%{http_code}" -X POST "${endpoint}" || printf "\n000")

  local code
  code=$(printf '%s\n' "${response}" | tail -n 1)
  local body
  body=$(printf '%s\n' "${response}" | head -n -1)

  printf "[RESULT] Cancel HTTP %s | Status: %s\n" "${code}" "$(extract_json_val "${body}" "status")"
}

#######################################
# Sends a JSON notification payload to the PiPup server via POST.
# Globals:
#   PORT
#   DURATION
#   DEFAULT_TITLE_SIZE
#   DEFAULT_MSG_SIZE
# Arguments:
#   target_ip: String IP address of the target server.
#   title: String title text.
#   message: String message text.
#   media_json: String JSON media configuration.
#   position: Integer screen position (0-4).
#   bg_color: String background hex color.
#   border_width: Integer border width in pixels.
#   border_color: String border hex color.
#   title_color: String title hex color.
#   msg_color: String message hex color.
#   border_radius: Integer radius in pixels.
#   media_pos: Integer media alignment (0-3).
#   padding: Integer padding in pixels.
#   anim_type: Integer animation type index.
#   anim_duration: Integer duration in milliseconds.
#   overwrite: Optional boolean string (default: false).
# Outputs:
#   Writes "CODE|BODY" response string to STDOUT.
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
  local title_align="${17:-0}"
  local msg_align="${18:-0}"
  local title_size="${19:-24}"
  local msg_size="${20:-14}"

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
  "titleSize": ${title_size},
  "titleAlignment": ${title_align},
  "message": "${escaped_message}",
  "messageColor": "${msg_color}",
  "messageSize": ${msg_size},
  "messageAlignment": ${msg_align},
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
  response=$(curl -s --max-time 60 -w "\n%{http_code}" -X POST "${endpoint}" \
    -H "Content-Type: application/json" \
    -d "${json_payload}" || printf "\n000")

  local code
  code=$(printf '%s\n' "${response}" | tail -n 1)
  local body
  body=$(printf '%s\n' "${response}" | head -n -1)

  printf "%s|%s" "${code}" "${body}"
}

#######################################
# Sends an image notification using multipart/form-data via POST.
# Globals:
#   PORT
#   DURATION
#   MULTIPART_IMAGES
# Arguments:
#   target_ip: String IP address of the target server.
#   position: Integer screen position index (0-4).
#   suffix: Optional string debug message.
#   media_pos: Optional integer alignment index (0-3).
#   padding: Optional integer padding in pixels.
#   anim_type: Optional integer animation index (0-10).
#   anim_duration: Optional integer duration in ms.
#   overwrite: Optional boolean string (default: false).
# Outputs:
#   Writes result row to STDOUT.
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

  local rand_idx=$(( RANDOM % ${#MULTIPART_IMAGES[@]} ))
  local img_file="${MULTIPART_IMAGES[$rand_idx]}"
  local img_ext="${img_file##*.}"

  local full_msg
  full_msg=$(printf "Mode: Form-Data (Source: %s)\nPos: %s\nMediaPos: %s\nPadding: %s\nOverwrite: %s%b" \
    "${img_ext^^}" "${position}" "${media_pos}" "${padding}" "${overwrite}" "${suffix}")

  local response
  response=$(curl -s --max-time 60 -w "\n%{http_code}" -X POST "${endpoint}" \
    -F "title=Multipart Test" \
    -F "message=${full_msg}" \
    -F "image=@${img_file}" \
    -F "duration=${DURATION}" \
    -F "position=${position}" \
    -F "mediaPosition=${media_pos}" \
    -F "contentPadding=${padding}" \
    -F "animationType=${anim_type}" \
    -F "animationDuration=${anim_duration}" \
    -F "overwrite=${overwrite}" || printf '\n000')

  local code
  code=$(printf '%s\n' "${response}" | tail -n 1)
  local body
  body=$(printf '%s\n' "${response}" | head -n -1)

  # rm -f "${temp_file}" # No longer needed with SHARED_MULTIPART_IMAGE

  local style_info
  style_info=$(printf "Pos:%s MedPos:%s Rad:--px Bdr:--px Pad:%sdp Anim:%s (%sms) Overwrite:%s" \
    "${position}" "${media_pos}" "${padding}" "${anim_type}" "${anim_duration}" "${overwrite}")

  print_result_row "MULTIPART" "N/A (Form-Data)" "${style_info}" "${target_ip}" "${code}|${body}"
}

#######################################
# Prints script usage guidelines and exits.
# Arguments:
#   None
# Outputs:
#   CLI options summary to STDERR
#######################################
usage() {
  cat <<EOF
Usage: ${0##*/} [-d device] [-w [min]] [-t type] [-u url] [-r [count]] [-a] [-l] [-o] [-C] [-c] [-s] [-k] [-h]
Options:
  -d    Target IP (default: ${DEFAULT_IP})
  -w    Start WebRTC server only. Optional: minutes (0 = infinite/24h)
  -t    Test type: ${TEST_TYPES[*]}
  -u    Custom media URL (overrides default assets)
  -r    Repeat count for single/all tests, OR iterations for stress test (default: 5 for tests, 50 for stress)
  -a    Run all standard tests in sequence
  -l    Add long text to messages
  -o    Overwrite the current notification
  -C    Disable media caching (for URL-based media)
  -c    Immediately trigger a service-wide cancel request
  -s    Execute a high-frequency parallel stress test
  -g    Gallery mode: Systematic walkthrough of all animations and positions
  -m    Monitor RAM usage in background. Optional: seconds (default: auto)
  -k    Stop the active WHEP pipeline and server
  -h, --help, -?  Show this help message and exit
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
#######################################
# Main entry point for the script. Handles CLI arguments and test execution.
# Globals:
#   DEFAULT_IP
#   TEST_TYPES
#   STRESS_ITERATIONS
#   MONITOR_START_TIME
#   monitor_pid
# Arguments:
#   $@: Array of command line arguments.
# Outputs:
#   Writes logs and test results to STDOUT/STDERR.
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
  local run_gallery="false"
  local kill_whep_pipeline="false"
  local force_start_webrtc="false"
  local server_only="false"
  local overwrite="false"
  local monitor_mem="false"
  local USE_CACHE="true"
  local monitor_duration="auto"
  local repeat_count=1
  local repeat_explicit="false"

  while getopts "d:t:u:alockswmghrC?" opt; do
    case "${opt}" in
      d) target_ip="${OPTARG}" ;;
      C) USE_CACHE="false" ;;
      t) test_type="${OPTARG}" ;;
      u) custom_url="${OPTARG}" ;;
      a) run_all="true" ;;
      l) use_long_text="true" ;;
      o) overwrite="true" ;;
      c) immediate_cancel="true" ;;
      k) kill_whep_pipeline="true" ;;
      s) run_stress="true" ;;
      g) run_gallery="true" ;;
      r)
        repeat_count=5
        repeat_explicit="true"
        local next_val="${!OPTIND:-}"
        if [[ -n "${next_val}" && "${next_val}" =~ ^[0-9]+$ ]]; then
          repeat_count="${next_val}"
          OPTIND=$((OPTIND + 1))
        fi
        ;;
      m)
        monitor_mem="true"
        # If the next argument doesn't start with a hyphen, use it as duration
        local next_val="${!OPTIND:-}"
        if [[ -n "${next_val}" && "${next_val}" =~ ^[0-9]+$ ]]; then
          monitor_duration="${next_val}"
          OPTIND=$((OPTIND + 1))
        elif [[ "${next_val}" == "auto" ]]; then
          monitor_duration="auto"
          OPTIND=$((OPTIND + 1))
        fi
        ;;
      w)
        force_start_webrtc="true"
        server_only="true"
        # Peek at next argument for optional timeout
        local next_arg="${!OPTIND:-}"
        if [[ -n "${next_arg}" && "${next_arg}" =~ ^[0-9]+$ ]]; then
          if [[ "${next_arg}" -eq 0 ]]; then
            WHEP_TIMEOUT=86400 # 24 hours
          else
            WHEP_TIMEOUT=$((next_arg * 60))
          fi
          OPTIND=$((OPTIND + 1))
        fi
        ;;
      h|\?) usage ;;
      *) usage ;;
    esac
  done

  # Handle Pipeline Termination (-k) Cleanly
  if [[ "${kill_whep_pipeline}" == "true" ]]; then
    stop_whep_service
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

  if [[ "${run_stress}" == "true" && "${repeat_explicit}" == "true" ]]; then
    STRESS_ITERATIONS="${repeat_count}"
  fi

  local suffix=""
  if [[ "${use_long_text}" == "true" ]]; then
    suffix="\n\n${LONG_TEXT}"
  fi

  # Setup ADB target early to allow monitor to use it
  local adb_full_cmd="adb"
  if [[ "${target_ip}" =~ ^(localhost|127\.0\.0\.1)$ ]]; then
    setup_adb_forwarding || true
    # Extract the device name if setup_adb_forwarding printed it (not ideal, but let's re-run detection)
    local local_emulator
    local_emulator=$(adb devices | tail -n +2 | grep -E '^(emulator-|127\.0\.0\.1|localhost)' | awk '{print $1}' | head -n 1 || true)
    [[ -n "${local_emulator}" ]] && adb_full_cmd="adb -s ${local_emulator}"
  else
    # Check if we can match the target IP to a device
    local matched_device
    matched_device=$(adb devices | tail -n +2 | grep "^${target_ip}" | awk '{print $1}' | head -n 1 || true)
    [[ -n "${matched_device}" ]] && adb_full_cmd="adb -s ${matched_device}"
  fi

  # Prepare shared multipart test images to avoid rate limiting and race conditions
  readonly SHARED_PNG="/dev/shm/pipup_shared.png"
  readonly SHARED_JPG="/dev/shm/pipup_shared.jpg"
  MULTIPART_IMAGES=()

  if [[ "${run_stress}" == "true" || "${run_all}" == "true" || "${test_type}" == "multipart" ]]; then
    # Download PNG
    if [[ ! -f "${SHARED_PNG}" || ! -s "${SHARED_PNG}" ]]; then
      printf "[SYSTEM] Downloading shared PNG for multipart tests... "
      if curl -sL --fail "${PNG_URL}" -o "${SHARED_PNG}"; then
        printf "OK\n"
      else
        printf "FAILED (generating local fallback)\n"
        printf 'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAADklEQVR42mNkYGD4DwABBQEA8RE6XgAAAABJRU5ErkJggg==' | base64 -d > "${SHARED_PNG}"
      fi
    fi
    MULTIPART_IMAGES+=("${SHARED_PNG}")

    # Download JPG
    if [[ ! -f "${SHARED_JPG}" || ! -s "${SHARED_JPG}" ]]; then
      printf "[SYSTEM] Downloading shared JPG for multipart tests... "
      if curl -sL --fail "${JPG_URL}" -o "${SHARED_JPG}"; then
        printf "OK\n"
      else
        printf "FAILED (reusing PNG as fallback)\n"
        cp "${SHARED_PNG}" "${SHARED_JPG}"
      fi
    fi
    MULTIPART_IMAGES+=("${SHARED_JPG}")
  fi

  # Ensure WebServer is listening before starting tests (robust readiness check)
  if [[ "${target_ip}" =~ ^(localhost|127\.0\.0\.1)$ ]]; then
    if ! (printf '' > "/dev/tcp/127.0.0.1/${PORT}") >/dev/null 2>&1; then
       printf "[SYSTEM] Port %s closed. Attempting auto-forward... " "${PORT}"
       if command -v adb >/dev/null 2>&1 && adb devices | grep -q "device$"; then
          adb forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1 && printf "OK\n" || printf "FAILED\n"
       else
          printf "FAILED (No device found)\n"
       fi
    fi

    printf "[SYSTEM] Waiting for PiPup WebServer on port %s... " "${PORT}"
    local ready=false
    for ((i=1; i<=15; i++)); do
      if (printf '' > "/dev/tcp/127.0.0.1/${PORT}") >/dev/null 2>&1; then
        ready=true
        break
      fi
      sleep 1
      printf "."
    done
    if [[ "${ready}" == "true" ]]; then
      printf " READY\n"
    else
      printf " TIMEOUT (continuing anyway)\n"
    fi
  fi

  trap 'cleanup_all INT' SIGINT SIGTERM

  local recovery_time=12 # Default for single test
  if [[ "${monitor_mem}" == "true" ]]; then
    if [[ "${monitor_duration}" == "auto" ]]; then
      local count_for_duration=1
      if [[ "${run_stress}" == "true" ]]; then
        # If overwrite is active, the total display time is almost independent of count
        [[ "${overwrite}" == "true" ]] && count_for_duration=1 || count_for_duration="${STRESS_ITERATIONS}"
        recovery_time=30
      elif [[ "${run_all}" == "true" ]]; then
        local test_count=0
        for t in "${TEST_TYPES[@]}"; do [[ "$t" != "cancel" ]] && test_count=$((test_count + 1)); done
        count_for_duration=$(( test_count * repeat_count ))
        recovery_time=30
      else
        count_for_duration="${repeat_count}"
      fi
      # Simple formula: (Display Time with Buffer) + Setup + Recovery
      monitor_duration=$(( (count_for_duration * (DURATION + MONITOR_TEST_BUFFER)) + MONITOR_SETUP_BUFFER + recovery_time ))
    fi

    # Ensure monitor duration is at least setup + recovery + some buffer
    [[ "${monitor_duration}" -lt $(( MONITOR_SETUP_BUFFER + recovery_time + 10 )) ]] && monitor_duration=$(( MONITOR_SETUP_BUFFER + recovery_time + 10 ))

    printf "Timestamp,TotalPSS,JavaHeap,NativeHeap\n" > "${MEM_LOG_FILE}"
    printf "${CLR_MONITOR}[SYSTEM] Starting background RAM monitoring (Duration: %ss, Target: %s)...${CLR_RESET}\n" \
      "${monitor_duration}" "${adb_full_cmd}"

    # Take initial synchronous baseline to ensure the log is not empty for fast tests
    baseline_info=$(${adb_full_cmd} shell dumpsys meminfo "${MEM_PACKAGE}" 2>/dev/null || printf "")
    if [[ -n "${baseline_info}" ]]; then
       local pss java native
       pss=$(get_mem_val "${baseline_info}" "TOTAL PSS:")
       java=$(get_mem_val "${baseline_info}" "Java Heap:")
       native=$(get_mem_val "${baseline_info}" "Native Heap:")
       [[ "${pss}" != "0" ]] && printf "%s,%s,%s,%s\n" "$(date +"%H:%M:%S")" "${pss}" "${java}" "${native}" >> "${MEM_LOG_FILE}"
    fi

    MONITOR_START_TIME=$(date +%s)
    monitor_memory "${monitor_duration}" "${adb_full_cmd}" &
    monitor_pid=$!
  fi

  # Start the background WHEP service for WebRTC/WHEP testing only if needed
  if [[ "${test_type}" == "whep" && -z "${custom_url}" ]] || [[ "${run_all}" == "true" ]] || [[ "${run_stress}" == "true" ]] || [[ "${force_start_webrtc}" == "true" ]]; then
    start_whep_service "${force_start_webrtc}"
    # If server-only mode was requested and no explicit test was specified, exit now
    if [[ "${server_only}" == "true" && -z "${test_type}" && "${run_all}" == "false" && "${run_stress}" == "false" ]]; then
      return 0
    fi
  fi

  #######################################
  # Wraps randomized styling configurations and maps variables to json dispatchers.
  # Globals:
  #   THEME_KEYS
  #   THEMES
  #   target_ip
  #   overwrite
  #   suffix
  # Arguments:
  #   type: String type identifier.
  #   title: String notification title.
  #   pos: Integer screen position.
  #   media: String JSON media payload.
  #   fit: Optional string video fit mode.
  # Outputs:
  #   Writes test results via print_result_row to STDOUT.
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

    local radius border_w padding anim_type anim_dur target_fit title_s msg_s
    local style_data
    style_data=$(get_random_style "${type}")
    read -r radius border_w padding anim_type anim_dur target_fit title_s msg_s <<< "${style_data}"
    [[ -z "${fit}" ]] && fit="${target_fit}"

    local rand_media_pos=$(( RANDOM % 4 ))
    local rand_t_a=$(( RANDOM % 3 ))
    local rand_m_a=$(( RANDOM % 3 ))

    local info_msg fit_label=""
    [[ -n "${fit}" ]] && fit_label=" | Fit: ${fit}"
    info_msg=$(printf "Theme: %s\nType: %s%s\nRadius: %spx | Border: %spx\nMediaPos: %s | Padding: %sdp\nAnim: %s (%sms)\nAlign: T=%s M=%s | Size: T=%s M=%s\nOverwrite: %s%b" \
      "${theme_name}" "${type}" "${fit_label}" "${radius}" "${border_w}" "${rand_media_pos}" "${padding}" \
      "${anim_type}" "${anim_dur}" "${rand_t_a}" "${rand_m_a}" "${title_s}" "${msg_s}" "${overwrite}" "${suffix}")

    local response
    response=$(send_json_notification "${target_ip}" "${title}" "${info_msg}" "${media}" \
      "${pos}" "${bg}" "${border_w}" "${border}" "${title_c}" "${msg_c}" \
      "${radius}" "${rand_media_pos}" "${padding}" "${anim_type}" "${anim_dur}" "${overwrite}" \
      "${rand_t_a}" "${rand_m_a}" "${title_s}" "${msg_s}")

    local table_type="${type^^}"
    [[ -n "${fit}" ]] && table_type="${table_type}(${fit:0:1})"

    local style_info
    style_info=$(printf "Pos:%s MedPos:%s Rad:%spx Bdr:%spx Pad:%sdp Anim:%s (%sms) Overwrite:%s" \
      "${pos}" "${rand_media_pos}" "${radius}" "${border_w}" "${padding}" \
      "${anim_type}" "${anim_dur}" "${overwrite}")

    print_result_row "${table_type}" "${theme_name}" "${style_info}" "${target_ip}" "${response}"
  }

  #######################################
  # Internal dispatcher that decides which test method to use.
  # Globals:
  #   target_ip
  #   custom_url
  #   overwrite
  #   suffix
  # Arguments:
  #   type: String type identifier.
  #   title: String notification title.
  #   pos: Integer screen position index.
  #   target_ip: String target server IP.
  #   custom_url: Optional string URL override.
  #   overwrite: Optional boolean string.
  #   suffix: Optional string debug suffix.
  #   is_async: Optional boolean string ("true" for background).
  # Outputs:
  #   Writes test results to STDOUT.
  #######################################
  trigger_test() {
    local type="${1}"
    local title="${2}"
    local pos="${3}"
    local target_ip="${4}"
    local custom_url="${5:-}"
    local overwrite="${6:-false}"
    local suffix="${7:-}"
    local is_async="${8:-false}"

    if [[ "${type}" == "multipart" ]]; then
      if [[ "${is_async}" == "true" ]]; then
        send_multipart_test "${target_ip}" "${pos}" "${suffix}" "$((RANDOM % 4))" "" "" "" "${overwrite}" &
      else
        send_multipart_test "${target_ip}" "${pos}" "${suffix}" "$((RANDOM % 4))" "" "" "" "${overwrite}"
      fi
    else
      local radius border_w padding anim_type anim_dur fit title_s msg_s
      local style_data
      style_data=$(get_random_style "${type}")
      read -r radius border_w padding anim_type anim_dur fit title_s msg_s <<< "${style_data}"

      local media
      media=$(get_media_payload "${type}" "${custom_url}" "${fit}")
      if [[ "${is_async}" == "true" ]]; then
        dispatch_test "${type}" "${title}" "${pos}" "${media}" "${fit}" &
      else
        dispatch_test "${type}" "${title}" "${pos}" "${media}" "${fit}"
      fi
    fi
  }

  #######################################
  # Executes a rapid concurrent background stress test against the endpoint.
  # Globals:
  #   STRESS_ITERATIONS
  #   TEST_TYPES
  #   monitor_mem
  #   monitor_pid
  # Arguments:
  #   target_ip: String IP address of the target server.
  #   suffix: String text modifier suffix.
  # Outputs:
  #   Writes stress initialization metrics and test results to STDOUT.
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
    local test_pids=()
    # Run the bombardment in a way that we can easily kill it
    for ((i = 0; i < STRESS_ITERATIONS; i++)); do
      local seed
      seed=$(date +%N | sed 's/^0*//')
      local rand_idx=$(((seed + i) % num_types))
      local type="${TEST_TYPES[rand_idx]}"

      # Avoid 'cancel' during stress to ensure full count and prevent queue clearing
      while [[ "${type}" == "cancel" ]]; do
         seed=$((seed + 1))
         rand_idx=$(((seed + i) % num_types))
         type="${TEST_TYPES[rand_idx]}"
      done

      trigger_test "${type}" "Stress #${i}" "$(( RANDOM % 5 ))" "${target_ip}" "${custom_url}" "${overwrite}" "${suffix}" "true"
      test_pids+=($!)
    done

    # Wait ONLY for the test requests to finish, not the monitor
    if [ ${#test_pids[@]} -gt 0 ]; then
        wait "${test_pids[@]}"
    fi

    printf "\n[SYSTEM] All %d requests processed.\n" "${STRESS_ITERATIONS}"
    finish_monitoring "${monitor_mem}" "${monitor_pid:-}" "${monitor_duration}"
    printf "\n[STRESS] Execution wave completed successfully.\n"
  }

  # --- Execution Flows ---

  if [[ "${run_all}" == "true" ]]; then
    print_table_header

    local test_count=0
    for t in "${TEST_TYPES[@]}"; do [[ "$t" != "cancel" ]] && test_count=$((test_count + 1)); done

    for ((r = 1; r <= repeat_count; r++)); do
      [[ "${repeat_count}" -gt 1 ]] && printf "${CLR_MONITOR}[RUN %d/%d]${CLR_RESET}\n" "${r}" "${repeat_count}"

      local pos_list
      mapfile -t pos_list < <(printf "%s\n" 0 1 2 3 4 0 1 2 3 4 | shuf)

      local type
      local current_test_idx=0
      for type in "${TEST_TYPES[@]}"; do
        [[ "${type}" == "cancel" ]] && continue
        current_test_idx=$((current_test_idx + 1))

        trigger_test "${type}" "${type^^} Test" "${pos_list[current_test_idx-1]}" "${target_ip}" "${custom_url}" "${overwrite}" "${suffix}" "false"

        # Don't sleep after the very last test of the very last run
        if [[ $r -lt $repeat_count || $current_test_idx -lt $test_count ]]; then
           sleep "$((DURATION - 1))"
        fi
      done
    done

    finish_monitoring "${monitor_mem}" "${monitor_pid:-}" "${recovery_time}"
    return 0
  fi

  if [[ "${run_stress}" == "true" ]]; then
    run_stress_test "${target_ip}" "${suffix}" "${recovery_time}"
    return 0
  fi

  if [[ "${run_gallery}" == "true" ]]; then
    printf "[SYSTEM] Starting Animation Gallery walkthrough (Randomized Positions)...\n"
    print_table_header
    local a e
    for e in "false" "true"; do
      for a in {0..10}; do
        local rand_p=$(( RANDOM % 5 ))
        local rand_t_a=$(( RANDOM % 3 ))
        local rand_m_a=$(( RANDOM % 3 ))
        local rand_t_s=$(( 18 + RANDOM % 15 ))
        local rand_m_s=$(( 12 + RANDOM % 10 ))

        local title="Gallery Anim ${a}"
        local info="Pos: ${POS_NAMES[$rand_p]} | Exit: ${e}\nAlign: T=${ALIGN_NAMES[$rand_t_a]}, M=${ALIGN_NAMES[$rand_m_a]}\nSize: T=${rand_t_s}, M=${rand_m_s}"

        local style_info
        style_info=$(printf "Pos:%s Algn:%s/%s Size:%s/%s Anim:%s (%sms) Exit:%s" \
            "${rand_p}" "${rand_t_a}" "${rand_m_a}" "${rand_t_s}" "${rand_m_s}" "${a}" "500" "${e}")

        # Mix in some colors and borders from themes
        local theme_str
        theme_str=$(get_random_theme_colors)
        local bg border title_c msg_c
        IFS=';' read -r bg border title_c msg_c <<< "${theme_str}"

        local response
        response=$(send_json_notification "${target_ip}" "${title}" "${info}" "null" \
          "${rand_p}" "${bg}" "2" "${border}" "${title_c}" "${msg_c}" \
          "12" "0" "20" "${a}" "500" "true" \
          "${rand_t_a}" "${rand_m_a}" "${rand_t_s}" "${rand_m_s}")

        print_result_row "GALLERY" "Mixed" "${style_info}" "${target_ip}" "${response}"
        sleep 2.2
      done
    done
    return 0
  fi

  # Single Test Case Execution
  [[ -z "${test_type}" ]] && test_type="message"

  if [[ "${test_type}" == "multipart" ]]; then
    print_table_header
    for ((r = 1; r <= repeat_count; r++)); do
      [[ "${repeat_count}" -gt 1 ]] && printf "${CLR_MONITOR}[RUN %d/%d]${CLR_RESET}\n" "${r}" "${repeat_count}"

      trigger_test "multipart" "Multipart Test" "$(( RANDOM % 5 ))" "${target_ip}" "" "${overwrite}" "${suffix}" "false"
      [[ $r -lt $repeat_count ]] && sleep "$((DURATION - 1))"
    done
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

    if [[ "${test_type}" == "whep" && -z "${custom_url}" ]]; then
      local state_info
      state_info=$(parse_whep_state)
      if [[ -n "${state_info}" ]]; then
        local state_pid state_port
        read -r state_pid state_port <<< "${state_info}"
        if docker ps -q --filter "name=webrtc_pipup_" >/dev/null 2>&1; then
          printf "[SYSTEM] %s Pipeline container is active (Port: %s)\n\n" "${ENGINE_NAME}" "${state_port}"
        else
          printf "[SYSTEM] Fallback WHEP server is active in background (PID: %s, Port: %s)\n\n" "${state_pid}" "${state_port}"
        fi
      fi
    fi
    print_table_header
    for ((r = 1; r <= repeat_count; r++)); do
      [[ "${repeat_count}" -gt 1 ]] && printf "${CLR_MONITOR}[RUN %d/%d]${CLR_RESET}\n" "${r}" "${repeat_count}"

      trigger_test "${test_type}" "${test_type^^} Test" "$(( RANDOM % 5 ))" "${target_ip}" "${custom_url}" "${overwrite}" "${suffix}" "false"
      [[ $r -lt $repeat_count ]] && sleep "$((DURATION - 1))"
    done
  fi

  finish_monitoring "${monitor_mem}" "${monitor_pid:-}" "${recovery_time}"
}

main "$@"
