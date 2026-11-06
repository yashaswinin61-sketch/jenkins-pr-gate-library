/**
 * Deletes CI VMs whose ci_expires_at has passed: leftovers from killed
 * agents, controller restarts, or builds that crashed before cleanup.
 * Run from a scheduled job (see examples/Jenkinsfile.reaper).
 */
def call(Map cfg = [:]) {
    writeFile file: '.prgate/vm.sh', text: libraryResource('prgate/vm.sh')
    String dryRun = cfg.dryRun ? '--dry-run' : ''
    sh "bash .prgate/vm.sh reap ${dryRun}"
}
