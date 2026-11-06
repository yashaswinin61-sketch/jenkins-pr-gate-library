/**
 * Keep a failed environment alive for interactive debugging.
 * Returns when the timeout expires or someone clicks "Release VM".
 */
def call(Map vm, int minutes) {
    echo """\
        |==================== HOLD_ON_FAILURE ====================
        | VM ${vm.name} is being kept for ${minutes} minutes.
        |   ssh ${vm.user}@${vm.ip}
        | Click "Release VM" (or abort the build) when you are done.
        |=========================================================""".stripMargin()
    currentBuild.description = "HELD: ${vm.user}@${vm.ip} (${minutes}m)"
    try {
        timeout(time: minutes, unit: 'MINUTES') {
            input message: "VM ${vm.name} is held for debugging.", ok: 'Release VM'
        }
        echo 'VM released by user.'
    } catch (e) {
        echo "Hold ended: ${e.getClass().simpleName}"
    }
}
