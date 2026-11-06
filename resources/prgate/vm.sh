#!/usr/bin/env bash
# Ephemeral CI VM lifecycle on OpenStack.
#
#   vm.sh create --name N --image I --flavor F [--network NET] [--key-name K]
#                [--user U] [--ttl-minutes M] [--build-url URL]   -> JSON on stdout
#   vm.sh delete --name N
#   vm.sh reap   [--dry-run]       delete CI VMs past their ci_expires_at
#
# Every VM is tagged with ci_owner / ci_expires_at / ci_build_url metadata so
# leftovers can be found and reaped without a separate state store.
# Requires: openstack CLI (auth via OS_* env or clouds.yaml), jq, ssh.
set -euo pipefail

OWNER_TAG="${CI_OWNER_TAG:-jenkins-prgate}"
SSH_WAIT_SECONDS="${SSH_WAIT_SECONDS:-300}"

log() { echo "[vm.sh] $*" >&2; }
die() { log "ERROR: $*"; exit 1; }

cmd_create() {
    local name="" image="" flavor="" network="" key="" user="ubuntu" ttl=180 build_url=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --name) name="$2"; shift 2 ;;
            --image) image="$2"; shift 2 ;;
            --flavor) flavor="$2"; shift 2 ;;
            --network) network="$2"; shift 2 ;;
            --key-name) key="$2"; shift 2 ;;
            --user) user="$2"; shift 2 ;;
            --ttl-minutes) ttl="$2"; shift 2 ;;
            --build-url) build_url="$2"; shift 2 ;;
            *) die "unknown option $1" ;;
        esac
    done
    [[ -n "$name" && -n "$image" && -n "$flavor" ]] || die "--name, --image and --flavor are required"

    local expires=$(( $(date +%s) + ttl * 60 ))
    local args=(server create "$name" --image "$image" --flavor "$flavor" --wait -f json
                --property "ci_owner=${OWNER_TAG}"
                --property "ci_expires_at=${expires}"
                --property "ci_build_url=${build_url}")
    [[ -n "$network" ]] && args+=(--network "$network")
    [[ -n "$key" ]] && args+=(--key-name "$key")

    log "creating $name ($image / $flavor, expires in ${ttl}m)"
    local server
    if ! server=$(openstack "${args[@]}"); then
        # A half-created server would otherwise leak until the reaper runs.
        openstack server delete --wait "$name" >/dev/null 2>&1 || true
        die "server create failed"
    fi

    local id ip
    id=$(jq -r '.id' <<<"$server")
    ip=$(first_ip "$(jq -c '.addresses' <<<"$server")")
    [[ -n "$ip" ]] || die "no IP address assigned to $name"

    wait_for_ssh "$user" "$ip" || { openstack server delete --wait "$id" || true; die "ssh never came up on $ip"; }
    jq -n --arg name "$name" --arg id "$id" --arg ip "$ip" --arg user "$user" \
        --argjson expires "$expires" '{name:$name,id:$id,ip:$ip,user:$user,expires_at:$expires}'
}

# .addresses is a dict ({"net": ["10.0.0.5"]}) or a string ("net=10.0.0.5, ...")
# depending on the CLI version, so handle both.
first_ip() {
    jq -r 'if type=="object" then [.[]] | flatten | map(if type=="object" then .addr else . end) | .[0]
           else (split("=")[1] // "" | split(",")[0] | gsub(" ";"")) end // empty' <<<"$1"
}

wait_for_ssh() {
    local user="$1" ip="$2" deadline=$(( $(date +%s) + SSH_WAIT_SECONDS ))
    while (( $(date +%s) < deadline )); do
        if ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=no \
               -o UserKnownHostsFile=/dev/null "$user@$ip" true 2>/dev/null; then
            log "ssh ready on $ip"
            return 0
        fi
        sleep 5
    done
    return 1
}

cmd_delete() {
    local name=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --name) name="$2"; shift 2 ;;
            *) die "unknown option $1" ;;
        esac
    done
    [[ -n "$name" ]] || die "--name is required"
    log "deleting $name"
    openstack server delete --wait "$name" || log "delete failed for $name (reaper will retry)"
}

cmd_reap() {
    local dry_run=0 now
    [[ "${1:-}" == "--dry-run" ]] && dry_run=1
    now=$(date +%s)
    openstack server list --long -f json -c ID -c Name -c Properties |
        jq -r --arg owner "$OWNER_TAG" --argjson now "$now" '
            .[]
            | .props = (if (.Properties|type)=="object" then .Properties
                        else (.Properties // "" | split(", ")
                              | map(split("=") | {(.[0]): (.[1:] | join("=") | gsub("^\u0027|\u0027$"; ""))})
                              | add // {})
                        end)
            | select(.props.ci_owner == $owner)
            | select((.props.ci_expires_at // "0" | tonumber) < $now)
            | "\(.ID) \(.Name)"' |
        while read -r id name; do
            if (( dry_run )); then
                log "would reap $name ($id)"
            else
                log "reaping $name ($id)"
                openstack server delete "$id" || log "failed to delete $id"
            fi
        done
}

main() {
    local cmd="${1:-}"; shift || true
    case "$cmd" in
        create) cmd_create "$@" ;;
        delete) cmd_delete "$@" ;;
        reap) cmd_reap "$@" ;;
        *) die "usage: vm.sh {create|delete|reap} [options]" ;;
    esac
}

# Allow sourcing for tests without running main.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
