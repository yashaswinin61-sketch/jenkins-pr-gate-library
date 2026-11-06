import prgate.VmName

/**
 * Creates a VM, runs body(vm), and always deletes the VM afterwards.
 *
 * If the build failed and cfg.holdOnFailure is set, the VM is kept for
 * cfg.holdMinutes (or until someone clicks "Release VM") so an engineer can
 * SSH in and inspect the failing state. Aborted or timed-out builds are never held.
 *
 * Failure detection uses currentBuild.currentResult as well as exceptions,
 * because steps wrapped in catchError mark the build failed without throwing.
 *
 * cfg: image, flavor, network, keyName, user (default 'ubuntu'), ttlMinutes,
 *      holdOnFailure, holdMinutes
 */
def call(Map cfg, Closure body) {
    String name = VmName.forBuild('ci', env.JOB_BASE_NAME, env.BUILD_NUMBER)
    int ttl = (cfg.ttlMinutes ?: 180) as int
    if (cfg.holdOnFailure) {
        ttl += (cfg.holdMinutes ?: 60) as int
    }

    def vm = provision(name, cfg, ttl)
    boolean threw = false
    boolean aborted = false
    try {
        return body(vm)
    } catch (e) {
        threw = true
        // Aborts and timeouts arrive as FlowInterruptedException: nobody is waiting to debug.
        aborted = e.getClass().simpleName == 'FlowInterruptedException'
        throw e
    } finally {
        boolean failed = threw || currentBuild.currentResult == 'FAILURE'
        if (failed && !aborted && cfg.holdOnFailure) {
            holdForDebug(vm, (cfg.holdMinutes ?: 60) as int)
        }
        destroy(vm)
    }
}

private Map provision(String name, Map cfg, int ttlMinutes) {
    writeFile file: '.prgate/vm.sh', text: libraryResource('prgate/vm.sh')
    String args = [
        "--name '${name}'",
        "--image '${cfg.image}'",
        "--flavor '${cfg.flavor}'",
        cfg.network ? "--network '${cfg.network}'" : '',
        cfg.keyName ? "--key-name '${cfg.keyName}'" : '',
        "--user '${cfg.user ?: 'ubuntu'}'",
        "--ttl-minutes ${ttlMinutes}",
        "--build-url '${env.BUILD_URL ?: ''}'",
    ].findAll().join(' ')
    sh "bash .prgate/vm.sh create ${args} > .prgate/vm.json"
    def vm = readJSON(file: '.prgate/vm.json')
    echo "Provisioned ${vm.name} (${vm.ip})"
    return vm
}

private void destroy(Map vm) {
    // Never fail the build because cleanup failed; the reaper job catches leftovers.
    catchError(buildResult: null, stageResult: null) {
        sh "bash .prgate/vm.sh delete --name '${vm.name}'"
    }
}
