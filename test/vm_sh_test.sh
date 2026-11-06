#!/usr/bin/env bash
# Tests for resources/prgate/vm.sh with the openstack CLI stubbed out.
set -euo pipefail
# shellcheck disable=SC2329  # the openstack() stubs are called indirectly by vm.sh
cd "$(dirname "$0")/.."
# shellcheck source=resources/prgate/vm.sh
source resources/prgate/vm.sh

fails=0
check() {
    local desc="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then echo "ok   $desc"; else
        echo "FAIL $desc"; echo "     expected: $expected"; echo "     actual:   $actual"; fails=$((fails+1)); fi
}

check "ip from dict addresses"   "10.0.0.5" "$(first_ip '{"ci-net":["10.0.0.5"]}')"
check "ip from string addresses" "10.0.0.7" "$(first_ip '"ci-net=10.0.0.7, fd00::1"')"
check "ip from addr objects"     "10.1.1.1" "$(first_ip '{"n":[{"addr":"10.1.1.1"}]}')"

now=$(date +%s)
openstack() {
    case "$1 $2" in
        "server list")
            cat <<JSON
[
 {"ID":"1","Name":"ci-old","Properties":{"ci_owner":"jenkins-prgate","ci_expires_at":"$((now-60))"}},
 {"ID":"2","Name":"ci-live","Properties":{"ci_owner":"jenkins-prgate","ci_expires_at":"$((now+600))"}},
 {"ID":"3","Name":"someone-else","Properties":{"ci_expires_at":"1"}},
 {"ID":"4","Name":"ci-old-str","Properties":"ci_build_url='https://ci/job/x=1', ci_expires_at='$((now-5))', ci_owner='jenkins-prgate'"}
]
JSON
            ;;
        "server delete") echo "$3" >> "$TMP_DELETED" ;;
    esac
}
TMP_DELETED=$(mktemp)
cmd_reap 2>/dev/null
check "reap deletes only expired CI VMs" "1,4" "$(paste -sd, "$TMP_DELETED")"

# String-form properties are quoted by some CLI versions; make sure that's handled too.
: > "$TMP_DELETED"
openstack() {
    case "$1 $2" in
        "server list") echo "[{\"ID\":\"4\",\"Name\":\"x\",\"Properties\":\"ci_expires_at='$((now-5))', ci_owner='jenkins-prgate'\"}]" ;;
        "server delete") echo "$3" >> "$TMP_DELETED" ;;
    esac
}
cmd_reap 2>/dev/null
check "reap handles quoted string properties" "4" "$(paste -sd, "$TMP_DELETED")"

: > "$TMP_DELETED"
openstack() { [[ "$1 $2" == "server list" ]] && echo '[{"ID":"1","Name":"a","Properties":{"ci_owner":"jenkins-prgate","ci_expires_at":"1"}}]'; [[ "$1 $2" == "server delete" ]] && echo "$3" >> "$TMP_DELETED"; return 0; }
cmd_reap --dry-run 2>/dev/null
check "dry-run deletes nothing" "" "$(cat "$TMP_DELETED")"

rm -f "$TMP_DELETED"
if (( fails > 0 )); then echo "$fails failed"; exit 1; fi
echo "all vm.sh tests passed"
