import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource
import static org.junit.jupiter.api.Assertions.*

class PrGateTest extends BasePipelineTest {

    /** Shell commands that should fail, matched by substring. */
    List<String> failing = []

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()
        helper.registerSharedLibrary(library().name('prgate')
                .defaultVersion('<notNeeded>')
                .allowOverride(true)
                .implicit(true)
                .targetPath('<notNeeded>')
                .retriever(projectSource())
                .build())

        binding.setVariable('scm', [:])
        binding.setVariable('params', [HOLD_ON_FAILURE: false, HOLD_MINUTES: '30'])
        binding.setVariable('env', [
            JOB_BASE_NAME: 'demo-repo',
            BUILD_NUMBER : '7',
            BUILD_URL    : 'https://ci.example.com/job/demo-repo/7/',
        ])

        helper.registerAllowedMethod('sh', [String]) { String cmd ->
            if (failing.any { cmd.contains(it) }) {
                throw new Exception("script returned exit code 1: ${cmd}")
            }
        }
        helper.registerAllowedMethod('libraryResource', [String]) { '#!/bin/bash' }
        helper.registerAllowedMethod('readJSON', [Map]) {
            [name: 'ci-demo-repo-7', id: 'abc', ip: '10.0.0.5', user: 'ubuntu']
        }
        helper.registerAllowedMethod('timestamps', [Closure]) { Closure c -> c() }
        helper.registerAllowedMethod('disableConcurrentBuilds', [Map]) { null }
        helper.registerAllowedMethod('timeout', [Map, Closure]) { Map m, Closure c -> c() }
        helper.registerAllowedMethod('input', [Map]) { null }
        helper.registerAllowedMethod('catchError', [Map, Closure]) { Map opts, Closure c ->
            try {
                c()
            } catch (e) {
                if (opts.buildResult) {
                    binding.getVariable('currentBuild').result = opts.buildResult
                    binding.getVariable('currentBuild').currentResult = opts.buildResult
                }
            }
        }
    }

    private void runGate(Map overrides = [:]) {
        def cfg = [
            testGroups : [lint: 'make lint', unit: 'make test-unit'],
            integration: [image: 'ubuntu-22.04', flavor: 'm1.medium', command: './ci/integration.sh'],
        ] + overrides
        def script = loadScript('test/resources/Jenkinsfile')
        script.run(cfg)
    }

    private List<String> shellCalls() {
        helper.callStack.findAll { it.methodName == 'sh' }.collect { it.args[0].toString() }
    }

    private boolean called(String method) {
        helper.callStack.any { it.methodName == method }
    }

    @Test
    void 'happy path runs every group, then integration, then deletes the VM'() {
        runGate()

        def sh = shellCalls()
        assertTrue(sh.contains('make lint'))
        assertTrue(sh.contains('make test-unit'))
        assertTrue(sh.any { it.contains('vm.sh create') && it.contains("--name 'ci-demo-repo-7'") })
        assertTrue(sh.contains('./ci/integration.sh'))
        assertTrue(sh.any { it.contains('vm.sh delete') })
        assertFalse(called('input'))
        assertJobStatusSuccess()
    }

    @Test
    void 'a failing unit group skips integration and fails the build'() {
        failing << 'make test-unit'
        runGate()

        assertFalse(shellCalls().any { it.contains('vm.sh create') })
        assertEquals('FAILURE', binding.getVariable('currentBuild').result)
        assertEquals('failed: unit', binding.getVariable('currentBuild').description.toString())
    }

    @Test
    void 'integration failure without HOLD_ON_FAILURE deletes the VM immediately'() {
        failing << './ci/integration.sh'
        runGate()

        assertFalse(called('input'))
        assertTrue(shellCalls().any { it.contains('vm.sh delete') })
        assertEquals('FAILURE', binding.getVariable('currentBuild').result)
    }

    @Test
    void 'integration failure with HOLD_ON_FAILURE holds the VM, then still deletes it'() {
        binding.setVariable('params', [HOLD_ON_FAILURE: true, HOLD_MINUTES: '30'])
        failing << './ci/integration.sh'
        runGate()

        def stack = helper.callStack
        int held = stack.findIndexOf { it.methodName == 'input' }
        int deleted = stack.findIndexOf { it.methodName == 'sh' && it.args[0].toString().contains('vm.sh delete') }
        assertTrue(held >= 0, 'expected the VM to be held')
        assertTrue(deleted > held, 'expected the VM to be deleted after the hold')
        assertTrue(binding.getVariable('currentBuild').description.startsWith('failed: integration'))
    }

    @Test
    void 'hold time extends the VM ttl so the reaper does not kill a held VM'() {
        binding.setVariable('params', [HOLD_ON_FAILURE: true, HOLD_MINUTES: '30'])
        runGate()

        assertTrue(shellCalls().any { it.contains('--ttl-minutes 210') })
    }

    @Test
    void 'HOLD_MINUTES is capped at 240'() {
        binding.setVariable('params', [HOLD_ON_FAILURE: true, HOLD_MINUTES: '9999'])
        runGate()

        assertTrue(shellCalls().any { it.contains('--ttl-minutes 420') })
    }

    @Test
    void 'a passing build with HOLD_ON_FAILURE does not hold'() {
        binding.setVariable('params', [HOLD_ON_FAILURE: true, HOLD_MINUTES: '30'])
        runGate()

        assertFalse(called('input'))
        assertJobStatusSuccess()
    }
}
