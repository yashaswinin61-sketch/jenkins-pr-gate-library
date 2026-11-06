import prgate.ResultTracker

/**
 * Pull-request gate: parallel test groups, then integration tests on a
 * throwaway VM. Usage (see examples/Jenkinsfile):
 *
 *   prGate(
 *     agentLabel : 'linux',
 *     testGroups : [lint: 'make lint', unit: 'make test-unit'],
 *     integration: [image: 'ubuntu-22.04', flavor: 'm1.medium',
 *                   network: 'ci-net', command: './ci/integration.sh'],
 *   )
 */
def call(Map cfg) {
    properties([
        parameters([
            booleanParam(name: 'HOLD_ON_FAILURE', defaultValue: false,
                         description: 'Keep the integration VM alive after a failure so you can SSH in.'),
            string(name: 'HOLD_MINUTES', defaultValue: '60',
                   description: 'How long to hold the VM (max 240).'),
        ]),
        disableConcurrentBuilds(abortPrevious: true),
        buildDiscarder(logRotator(numToKeepStr: '100')),
    ])

    def tracker = new ResultTracker()

    node(cfg.agentLabel ?: 'linux') {
        timestamps {
            stage('Checkout') {
                checkout scm
            }

            stage('Tests') {
                def branches = [failFast: cfg.failFast ?: false]
                (cfg.testGroups ?: [:]).each { name, command ->
                    branches[name] = { runGroup(tracker, name) { sh command } }
                }
                parallel branches
            }

            if (cfg.integration && tracker.failed().isEmpty() && !tracker.anyAborted()) {
                stage('Integration') {
                    def vmCfg = cfg.integration + [
                        holdOnFailure: params.HOLD_ON_FAILURE as boolean,
                        holdMinutes  : Math.min((params.HOLD_MINUTES ?: '60') as int, 240),
                    ]
                    withEphemeralVm(vmCfg) { vm ->
                        runGroup(tracker, 'integration') {
                            withEnv(["VM_IP=${vm.ip}", "VM_USER=${vm.user}"]) {
                                sh cfg.integration.command
                            }
                        }
                    }
                }
            } else if (cfg.integration) {
                echo 'Skipping integration: earlier test groups did not pass.'
            }

            stage('Report') {
                echo "Gate summary:\n${tracker.summary()}"
                if (!tracker.failed().isEmpty()) {
                    currentBuild.result = 'FAILURE'
                    currentBuild.description = "failed: ${tracker.failed().join(', ')}"
                }
            }
        }
    }
}

/** Run one group: catchError keeps sibling branches going; aborts still propagate. */
private void runGroup(ResultTracker tracker, String name, Closure body) {
    tracker.start(name)
    try {
        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE', catchInterruptions: false) {
            body()
            tracker.pass(name)
        }
    } catch (e) {
        // With catchInterruptions:false only interruptions (abort / timeout) escape catchError.
        tracker.abort(name)
        throw e
    }
}
