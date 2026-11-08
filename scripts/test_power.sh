#!/bin/bash
#
# PiPup Power Control Tester.
# Sends remote wake/sleep/toggle commands to a PiPup server.

set -euo pipefail

# --- Source Shared Common Library ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/common.sh"

extract_json_val() {
  local json="${1:-}"
  local key="${2:-}"
  [[ -z "${json}" || -z "${key}" ]] && return
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\?\([^\",}]*\)\"\?.*/\1/p"
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
# Main execution logic.
# Globals:
#   DEFAULT_IP
#   PORT
#   CLR_HEADER
#   CLR_SUCCESS
# Arguments:
#   @: Command-line arguments passed to the script.
# Outputs:
#   Writes progress and execution results to stdout.
# Returns:
#   0 on success, non-zero on error.
#######################################
main() {
  local target_ip="${DEFAULT_IP}"
  local command=""
  local state=""
  local endpoint body response code
  local name model ver power_block p_on p_wake p_sleep p_method perm_admin perm_acc

  while getopts "d:h" opt; do
    case "${opt}" in
      d) target_ip="${OPTARG}" ;;
      h|?) usage ;;
    esac
  done
  shift $((OPTIND - 1))

  command="${1:-}"
  [[ -z "${command}" ]] && usage

  case "${command}" in
    on|wake|up) state="on" ;;
    off|sleep|down) state="off" ;;
    toggle) state="toggle" ;;
    status|state) state="status" ;;
    *) printf "Unknown command: %s\n" "${command}" ; usage ;;
  esac

  setup_adb_forwarding "${target_ip}"

  if [[ "${state}" == "status" ]]; then
    endpoint="http://${target_ip}:${PORT}/state"
    printf "%b[POWER] Requesting current state from %s...%b\n" "${CLR_HEADER}" "${target_ip}" "${CLR_RESET}"

    body=$(curl -s --connect-timeout 3 --max-time 5 "${endpoint}" || printf "")
    if [[ -z "${body}" ]]; then
        printf "%b[ERROR] Could not connect to PiPup server.%b\n" "${CLR_ERROR}" "${CLR_RESET}"
        exit 1
    fi

    name=$(extract_json_val "${body}" "name")
    model=$(extract_json_val "${body}" "model")
    ver=$(extract_json_val "${body}" "version")

    power_block=$(printf '%s' "${body}" | sed -n 's/.*"power":{\([^}]*\)}.*/\1/p')

    p_on=$(extract_json_val "${power_block}" "screenOn")
    p_wake=$(extract_json_val "${power_block}" "canWake")
    p_sleep=$(extract_json_val "${power_block}" "canSleep")
    p_method=$(extract_json_val "${power_block}" "sleepMethod")

    perm_admin=$(extract_json_val "${body}" "deviceAdmin")
    perm_acc=$(extract_json_val "${body}" "accessibility")

    format_bool() { [[ "${1}" == "true" ]] && printf "%bYES%b" "${CLR_SUCCESS}" "${CLR_RESET}" || printf "%bNO%b" "${CLR_ERROR}" "${CLR_RESET}"; }
    format_on_off() { [[ "${1}" == "true" ]] && printf "%bON%b" "${CLR_SUCCESS}" "${CLR_RESET}" || printf "%bOFF%b" "${CLR_WARNING}" "${CLR_RESET}"; }

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

    response=$(curl -s --connect-timeout 3 --max-time 5 -w "|%{http_code}" -X POST "${endpoint}" || printf "Error|000")
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
}

main "$@"
