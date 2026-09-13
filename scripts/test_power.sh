#!/bin/bash
#
# PiPup Power Control Tester.
# Sends remote wake/sleep/toggle commands to a PiPup server.

set -euo pipefail

#######################################
# GLOBALS
#######################################
readonly DEFAULT_IP='127.0.0.1'
readonly PORT='7979'

# UI Coloring
readonly CLR_RESET='\033[0m'
readonly CLR_HEADER='\033[1;35m'
readonly CLR_SUCCESS='\033[1;32m'
readonly CLR_ERROR='\033[1;31m'
readonly CLR_WARN='\033[1;33m'

#######################################
# HELPERS
#######################################

extract_json_val() {
  local json="${1:-}"
  local key="${2:-}"
  [[ -z "${json}" || -z "${key}" ]] && return
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\?\([^\",}]*\)\"\?.*/\1/p"
}

setup_adb_forwarding() {
  local target_ip="${1}"
  if (printf '' > "/dev/tcp/127.0.0.1/${PORT}") >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v adb >/dev/null 2>&1; then
    return 1
  fi

  local adb_cmd="adb"
  local adb_device=""

  if [[ "${target_ip}" != "127.0.0.1" && "${target_ip}" != "localhost" ]]; then
    local matched_device
    matched_device=$(adb devices | tail -n +2 | grep "^${target_ip}" | awk '{print $1}' | head -n 1)
    if [[ -n "${matched_device}" ]]; then
      adb_device="${matched_device}"
    else
      if adb connect "${target_ip}:5555" >/dev/null 2>&1; then
        adb_device="${target_ip}:5555"
      fi
    fi
  fi

  if [[ -z "${adb_device}" ]]; then
    local device_count
    device_count=$(adb devices | tail -n +2 | grep -cv '^$')
    if [ "${device_count}" -gt 1 ]; then
      adb_device=$(adb devices | tail -n +2 | head -n 1 | awk '{print $1}')
    fi
  fi

  [[ -n "${adb_device}" ]] && adb_cmd="adb -s ${adb_device}"
  printf "[SYSTEM] ADB: Forwarding tcp:%s via %s... " "${PORT}" "${adb_cmd}"
  $adb_cmd forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1 && printf "OK\n" || printf "FAILED\n"
}

usage() {
  cat <<EOF
Usage: ${0##*/} [-d target_ip] <command>
Commands:
  on      Wake the screen
  off     Put the screen to sleep (Requires Admin/Accessibility)
  toggle  Toggle screen state
  status  Check current power state (Detailed summary)

Options:
  -d      Target IP (default: ${DEFAULT_IP})
  -h      Show this help
EOF
  exit 1
}

#######################################
# MAIN
#######################################

target_ip="${DEFAULT_IP}"

while getopts "d:h" opt; do
  case "${opt}" in
    d) target_ip="${OPTARG}" ;;
    h) usage ;;
    *) usage ;;
  esac
done
shift $((OPTIND - 1))

command="${1:-}"
[[ -z "${command}" ]] && usage

# Normalize command
case "${command}" in
  on|wake|up) state="on" ;;
  off|sleep|down) state="off" ;;
  toggle) state="toggle" ;;
  status|state) state="status" ;;
  *) printf "Unknown command: %s\n" "${command}" ; usage ;;
esac

setup_adb_forwarding "${target_ip}" || true

if [[ "${state}" == "status" ]]; then
    endpoint="http://${target_ip}:${PORT}/state"
    printf "%b[POWER] Requesting current state from %s...%b\n" "${CLR_HEADER}" "${target_ip}" "${CLR_RESET}"

    body=$(curl -s "${endpoint}" || printf "")
    if [[ -z "${body}" ]]; then
        printf "%b[ERROR] Could not connect to PiPup server.%b\n" "${CLR_ERROR}" "${CLR_RESET}"
        exit 1
    fi

    # Extract relevant fields (Power block is usually at the end)
    name=$(extract_json_val "${body}" "name")
    model=$(extract_json_val "${body}" "model")
    ver=$(extract_json_val "${body}" "version")

    # Power Block (targeted extraction via sed to handle nested JSON)
    power_block=$(printf '%s' "${body}" | sed -n 's/.*"power":{\([^}]*\)}.*/\1/p')

    p_on=$(extract_json_val "${power_block}" "screenOn")
    p_wake=$(extract_json_val "${power_block}" "canWake")
    p_sleep=$(extract_json_val "${power_block}" "canSleep")
    p_method=$(extract_json_val "${power_block}" "sleepMethod")

    # Permissions
    perm_admin=$(extract_json_val "${body}" "deviceAdmin")
    perm_acc=$(extract_json_val "${body}" "accessibility")

    # Formatting helper
    format_bool() { [[ "${1}" == "true" ]] && printf "%bYES%b" "${CLR_SUCCESS}" "${CLR_RESET}" || printf "%bNO%b" "${CLR_ERROR}" "${CLR_RESET}"; }
    format_on_off() { [[ "${1}" == "true" ]] && printf "%bON%b" "${CLR_SUCCESS}" "${CLR_RESET}" || printf "%bOFF%b" "${CLR_WARN}" "${CLR_RESET}"; }

    printf "\n%bDevice Information:%b\n" "${CLR_HEADER}" "${CLR_RESET}"
    printf "  Name:    %s\n" "${name}"
    printf "  Model:   %s\n" "${model}"
    printf "  Version: %s\n" "${ver}"

    printf "\n%bPower Status:%b\n" "${CLR_HEADER}" "${CLR_RESET}"
    printf "  Screen State:     " ; format_on_off "${p_on}" ; printf "\n"
    printf "  Can Wake:         " ; format_bool "${p_wake}" ; printf "\n"
    printf "  Can Sleep:        " ; format_bool "${p_sleep}" ; printf "\n"
    printf "  Sleep Method:     %b%s%b\n" "${CLR_SUCCESS}" "${p_method:-None}" "${CLR_RESET}"

    printf "\n%bRequired Permissions:%b\n" "${CLR_HEADER}" "${CLR_RESET}"
    printf "  Device Admin:     " ; format_bool "${perm_admin}" ; printf "\n"
    printf "  Accessibility:    " ; format_bool "${perm_acc}" ; printf "\n\n"

else
    endpoint="http://${target_ip}:${PORT}/power?state=${state}"
    printf "%b[POWER] Sending '%s' command to %s...%b\n" "${CLR_HEADER}" "${state^^}" "${target_ip}" "${CLR_RESET}"

    response=$(curl -s -w "|%{http_code}" -X POST "${endpoint}" || printf "Error|000")
    body="${response%|*}"
    code="${response##*|}"

    if [[ "${code}" == "200" ]]; then
        printf "%b[SUCCESS] Command accepted: %s%b\n" "${CLR_SUCCESS}" "${body}" "${CLR_RESET}"
    elif [[ "${code}" == "403" ]]; then
        printf "%b[ERROR] Module disabled on target. Enable 'Power Control' in settings.%b\n" "${CLR_ERROR}" "${CLR_RESET}"
    elif [[ "${code}" == "501" ]]; then
        printf "%b[ERROR] Permission missing on target (Admin/Accessibility needed for SLEEP).%b\n" "${CLR_ERROR}" "${CLR_RESET}"
    else
        printf "%b[FAILED] HTTP %s: %s%b\n" "${CLR_ERROR}" "${code}" "${body}" "${CLR_RESET}"
    fi
fi
