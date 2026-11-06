# jenkins-pr-gate-library

![ci](https://github.com/yashaswinin61-sketch/jenkins-pr-gate-library/actions/workflows/ci.yml/badge.svg)

A Jenkins **shared library** for pull-request gates. Test groups run in parallel, then
integration tests run on a **throwaway cloud VM** that is always cleaned up. An optional
**HOLD_ON_FAILURE** mode keeps the failed VM alive so an engineer can SSH in and debug it.

Every repo uses the same five lines:

```groovy
@Library('prgate') _
prGate(
    testGroups : [lint: 'make lint', unit: 'make test-unit'],
    integration: [image: 'ubuntu-22.04', flavor: 'm1.medium', command: './ci/integration.sh'],
)
```

## Why

Teams that copy and paste Jenkinsfiles end up with N slightly different pipelines. Fixing
anything (a timeout, a cleanup bug) then means N pull requests. Integration VMs leak whenever a
build is killed at the wrong moment, and when an integration test fails on a VM that was
already deleted, the only way to debug is to read logs and guess.

This library makes the gate one versioned, unit-tested artifact:

| problem | what the library does |
|---|---|
| one red group hides the others | groups run in `parallel`, each wrapped in `catchError`, so you see **all** failures in one run |
| "it failed but the stage is green" | every group starts as FAILURE and is only marked SUCCESS after its last command returns, so errors swallowed by `catchError` still count |
| leaked VMs | `try/finally` teardown, plus each VM is tagged with `ci_expires_at`, and a scheduled **reaper** deletes anything past its expiry |
| can't debug a failed integration run | `HOLD_ON_FAILURE=true` keeps the VM for `HOLD_MINUTES` (capped at 4h) and prints the `ssh` command. Click **Release VM** when done. The TTL is extended by the hold time so the reaper won't delete a VM that's being held |
| aborts treated like failures | aborted or timed-out builds never trigger the hold (nobody is waiting to debug them) |
| superseded PR builds waste capacity | `disableConcurrentBuilds(abortPrevious: true)` |

## Layout

```
vars/
  prGate.groovy           # the gate: checkout -> parallel groups -> integration -> report
  withEphemeralVm.groovy  # provision / run / (hold) / always destroy
  holdForDebug.groovy     # timeout + input("Release VM")
  reapExpiredVms.groovy   # scheduled cleanup entry point
src/prgate/
  ResultTracker.groovy    # per-group pass/fail/abort bookkeeping
  VmName.groovy           # cloud-safe, unique, <=63 char VM names
resources/prgate/vm.sh    # OpenStack lifecycle: create (+wait for ssh), delete, reap
test/                     # JenkinsPipelineUnit + JUnit 5 tests, and stubbed-CLI shell tests
examples/                 # Jenkinsfile for an app repo, Jenkinsfile for the reaper job
local/                    # Jenkins-in-Docker sandbox configured with JCasC
```

## Flow

```
PR opened / updated
   │
   ▼
Checkout ─► ┌ lint ─────┐
            ├ unit ─────┼─► all passed? ──no──► Report (FAILURE, "failed: unit")
            └ typecheck ┘        │yes
                                 ▼
                    create VM (tagged ci_expires_at)
                                 │
                    integration tests on VM
                                 │
                failed && HOLD_ON_FAILURE && !aborted ?
                     │yes                       │no
                     ▼                          │
          hold: print ssh, wait for             │
          "Release VM" or timeout               │
                     └──────────┬───────────────┘
                                ▼
                          delete VM  ◄── reaper job deletes anything this misses
```

## Testing

The pipeline logic is tested with [JenkinsPipelineUnit](https://github.com/jenkinsci/JenkinsPipelineUnit),
on the same Groovy 2.4 runtime Jenkins uses, so syntax that Jenkins can't run fails here first:

```bash
./gradlew check    # JUnit pipeline tests + vm.sh tests (openstack CLI stubbed)
```

Covered scenarios include the happy path, a failing group skipping integration, a failure with
and without HOLD_ON_FAILURE (and that the VM is deleted *after* the hold), TTL extension, the
hold cap, and the reaper's handling of both `openstack` CLI property formats (dict and quoted
string).

CI also runs `shellcheck` and validates the local sandbox.

## Try it locally

```bash
cd local && JENKINS_ADMIN_PASSWORD=secret docker compose up --build
# http://localhost:8080 → run "demo-pr-gate"
```

The sandbox is configured with Configuration-as-Code: it installs the plugins, registers this
checkout as the `prgate` global library, and seeds a demo job with Job DSL.

## Using it with real VMs

1. Create an OpenStack `clouds.yaml` credential (`openstack-clouds-yaml`) and an SSH key the agent can use.
2. Make sure `openstack`, `jq` and `ssh` are installed on the agents.
3. Add `examples/Jenkinsfile.reaper` as a scheduled job.
4. Point your repos at `examples/Jenkinsfile`.

Note: during a hold the build keeps its executor busy. Size `HOLD_MINUTES` to match your capacity.

## License

MIT
