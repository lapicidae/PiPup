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

# Style Variations
readonly DEFAULT_TITLE_SIZE=24
readonly DEFAULT_MSG_SIZE=14
readonly MIN_PADDING=16

readonly TEST_TYPES=("png" "jpg" "svg" "video" "web" "multipart" "message" "cancel")

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
  case "${type}" in
    png)   echo "{\"image\": {\"uri\": \"${PNG_URL}\", \"width\": 480}}" ;;
    jpg)   echo "{\"image\": {\"uri\": \"${JPG_URL}\", \"width\": 480}}" ;;
    svg)   echo "{\"image\": {\"uri\": \"${SVG_URL}\", \"width\": 480}}" ;;
    video) echo "{\"video\": {\"uri\": \"${VIDEO_URL}\", \"width\": 480}}" ;;
    web)   echo "{\"web\": {\"uri\": \"${WEB_URL}\", \"width\": 640, \"height\": 480}}" ;;
    *)     printf "null" ;;
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
  printf "${CLR_HEADER}%-12s | %-15s | %-60s | %-15s | %-6s${CLR_RESET}\n" \
    "TEST TYPE" "THEME" "STYLE PARAMETERS" "ENDPOINT" "HTTP"
  printf "${CLR_PARAM}%s${CLR_RESET}\n" \
    "-------------------------------------------------------------------------------------------------------------------------------"
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
  if (echo > "/dev/tcp/127.0.0.1/${PORT}") >/dev/null 2>&1; then
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
  response=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${endpoint}" || echo "000")
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
  local endpoint="http://${target_ip}:${PORT}/notify"
  local temp_file="/tmp/pipup_test.png"

  local full_msg
  full_msg=$(printf "Mode: Form-Data\nPos: %s\nMediaPos: %s\nPadding: %s%b" "${position}" "${media_pos}" "${padding}" "${suffix}")

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
    -F "animationDuration=${anim_duration}" || echo "000")

  rm -f "${temp_file}"

  # Colorize the HTTP status code
  local status_color="${CLR_SUCCESS}"
  [[ "${response}" != "200" ]] && status_color="${CLR_ERROR}"

  local style_info  
  style_info=$(printf "Pos:%s MedPos:%s Rad:-- Bdr:-- Pad:%sdp Anim:%s (%sms)" \
    "${position}" "${media_pos}" "${padding}" "${anim_type}" "${anim_duration}")

  printf "${CLR_TEST}%-12s${CLR_RESET} | ${CLR_THEME}%-15s${CLR_RESET} | ${CLR_PARAM}%-60s${CLR_RESET} | %-15s | ${status_color}%-6s${CLR_RESET}\n" \
    "MULTIPART" "N/A (Form-Data)" "${style_info}" "${target_ip}" "${response}"
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
Usage: ${0##*/} [-h host] [-t type] [-a] [-l] [-c] [-s]
Options:
  -h    Target IP (default: ${DEFAULT_IP})
  -t    Test type: ${TEST_TYPES[*]}
  -a    Run all standard tests in sequence
  -l    Add long text to messages
  -c    Immediately trigger a service-wide cancel request
  -s    Execute a high-frequency parallel stress test
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
  local target_ip="${DEFAULT_IP}"
  local test_type=""
  local run_all="false"
  local use_long_text="false"
  local immediate_cancel="false"
  local run_stress="false"

  while getopts "h:t:alcs" opt; do
    case "${opt}" in
      h) target_ip="${OPTARG}" ;;
      t) test_type="${OPTARG}" ;;
      a) run_all="true" ;;
      l) use_long_text="true" ;;
      c) immediate_cancel="true" ;;
      s) run_stress="true" ;;
      *) usage ;;
    esac
  done

  if [[ "${run_all}" == "true" && "${run_stress}" == "true" ]]; then
    printf "Error: Options -a (run all) and -s (stress test) are mutually exclusive.\n" >&2
    usage
  fi

  if [[ "${immediate_cancel}" == "true" ]]; then
    send_cancel_request "${target_ip}"
    return 0
  fi

  local suffix=""
  [[ "${use_long_text}" == "true" ]] && suffix="\n\n${LONG_TEXT}"

  [[ "${target_ip}" =~ ^(localhost|127\.0\.0\.1)$ ]] && setup_adb_forwarding

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

    local theme_name="${THEME_KEYS[$((RANDOM % ${#THEME_KEYS[@]}))]}"
    local theme_str="${THEMES[$theme_name]}"

    local bg border title_c msg_c
    IFS=';' read -r bg border title_c msg_c <<< "${theme_str}"

    local rand_radius=$((RANDOM % 50))
    local rand_border=$((RANDOM % 10 + 1))
    local rand_media_pos=$((RANDOM % 4))
    local rand_padding=$((MIN_PADDING + (RANDOM % 25)))
	local rand_anim_type=$((RANDOM % 11))
    local rand_anim_duration=$((300 + RANDOM % 1201))

    local info_msg
    info_msg=$(printf "Theme: %s\nType: %s\nRadius: %spx | Border: %spx\nMediaPos: %s | Padding: %sdp\nAnim: %s (%sms)%b" \
      "${theme_name}" "${type}" "${rand_radius}" "${rand_border}" "${rand_media_pos}" "${rand_padding}" \
      "${rand_anim_type}" "${rand_anim_duration}" "${suffix}")

    # Fire the notification and catch the returned HTTP code
    local response
    response=$(send_json_notification "${target_ip}" "${title}" "${info_msg}" "${media}" \
      "${pos}" "${bg}" "${rand_border}" "${border}" "${title_c}" "${msg_c}" \
      "${rand_radius}" "${rand_media_pos}" "${rand_padding}" "${rand_anim_type}" "${rand_anim_duration}")

    # Colorize the HTTP status code
    local status_color="${CLR_SUCCESS}"
    [[ "${response}" != "200" ]] && status_color="${CLR_ERROR}"

    # Print the beautiful, single-line table row
    local style_info
    style_info=$(printf "Pos:%s MedPos:%s Rad:%spx Bdr:%spx Pad:%sdp Anim:%s (%sms)" \
      "${pos}" "${rand_media_pos}" "${rand_radius}" "${rand_border}" "${rand_padding}" \
      "${rand_anim_type}" "${rand_anim_duration}")

    printf "${CLR_TEST}%-12s${CLR_RESET} | ${CLR_THEME}%-15s${CLR_RESET} | ${CLR_PARAM}%-60s${CLR_RESET} | %-15s | ${status_color}%-6s${CLR_RESET}\n" \
      "${type^^}" "${theme_name}" "${style_info}" "${target_ip}" "${response}"
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
  #   Writes stress initialization metrics to stdout.
  #######################################
  run_stress_test() {
    local target_ip="${1}"
    local suffix="${2}"

    printf "[STRESS] Initiating parallel bombardment of %d requests...\n" "${STRESS_ITERATIONS}"
    printf "[STRESS] Spawning background jobs... "

    printf "DONE\n"
    printf "[STRESS] Awaiting incoming responses from server...\n\n"
    print_table_header

    local i
    for ((i = 0; i < STRESS_ITERATIONS; i++)); do
      local rand_idx=$((RANDOM % ${#TEST_TYPES[@]}))
      local type="${TEST_TYPES[rand_idx]}"

      if [[ "${type}" == "cancel" ]]; then
        continue 
      fi

      if [[ "${type}" == "multipart" ]]; then
        local mp_padding=$((MIN_PADDING + (RANDOM % 25)))
        local mp_anim_type=$((RANDOM % 11))
        local mp_anim_duration=$((300 + RANDOM % 1201))
        send_multipart_test "${target_ip}" "$((RANDOM % 5))" "${suffix}" "$((RANDOM % 4))" "${mp_padding}" "${mp_anim_type}" "${mp_anim_duration}" &
        continue
      fi

      local media
      media=$(get_media_payload "${type}")
      dispatch_test "${type}" "Stress #${i}" "$((RANDOM % 5))" "${media}" &
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
        local mp_padding=$((MIN_PADDING + (RANDOM % 25)))
        local mp_anim_type=$((RANDOM % 11))
        local mp_anim_duration=$((300 + RANDOM % 1201))
        send_multipart_test "${target_ip}" "${pos_list[idx]}" "${suffix}" "$((RANDOM % 4))" "${mp_padding}" "${mp_anim_type}" "${mp_anim_duration}"
      else
        local media
        media=$(get_media_payload "${type}")
        dispatch_test "${type}" "${type^^} Test" "${pos_list[idx]}" "${media}"
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
  
  if [[ "${test_type}" == "multipart" ]]; then
    print_table_header
    local mp_padding=$((MIN_PADDING + (RANDOM % 25)))
    local mp_anim_type=$((RANDOM % 11))
    local mp_anim_duration=$((300 + RANDOM % 1201))
    send_multipart_test "${target_ip}" 0 "${suffix}" "$((RANDOM % 4))" "${mp_padding}" "${mp_anim_type}" "${mp_anim_duration}"
  elif [[ "${test_type}" == "cancel" ]]; then
    local media
    media=$(get_media_payload "png")
    dispatch_test "cancel" "Abort Test" 0 "${media}"
    sleep 2
    send_cancel_request "${target_ip}"
  else
    if [[ ! " ${TEST_TYPES[*]} " == *" ${test_type} "* ]]; then
      printf "Test '%s' not recognized.\n" "${test_type}" >&2
      usage
    fi
    print_table_header
    local media
    media=$(get_media_payload "${test_type}")
    dispatch_test "${test_type}" "${test_type^^} Test" 0 "${media}"
  fi
}

main "$@"
