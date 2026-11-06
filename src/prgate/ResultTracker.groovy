package prgate

/**
 * Tracks the outcome of each parallel test group.
 *
 * Every group starts as FAILURE and only becomes SUCCESS once its last
 * command returns. That way a group whose error was swallowed by catchError
 * (which marks the stage red but does not throw) is still counted as failed.
 * Aborted groups are tracked separately so an abort never triggers
 * failure-only behaviour such as holding a VM for debugging.
 */
class ResultTracker implements Serializable {
    static final String SUCCESS = 'SUCCESS'
    static final String FAILURE = 'FAILURE'
    static final String ABORTED = 'ABORTED'

    private final Map<String, String> results = [:]

    void start(String group) { results[group] = FAILURE }

    void pass(String group) { results[group] = SUCCESS }

    void abort(String group) { results[group] = ABORTED }

    String status(String group) { results[group] }

    List<String> failed() { results.findAll { it.value == FAILURE }.keySet() as List }

    boolean anyAborted() { results.containsValue(ABORTED) }

    boolean allPassed() { !results.isEmpty() && results.every { it.value == SUCCESS } }

    String summary() {
        results.collect { k, v -> "${v == SUCCESS ? 'PASS' : v == ABORTED ? 'ABRT' : 'FAIL'}  ${k}" }
               .join('\n')
    }
}
