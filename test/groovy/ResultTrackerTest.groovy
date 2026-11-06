import org.junit.jupiter.api.Test
import prgate.ResultTracker

import static org.junit.jupiter.api.Assertions.*

class ResultTrackerTest {

    @Test
    void 'a started group counts as failed until it passes'() {
        def t = new ResultTracker()
        t.start('unit')
        assertEquals(['unit'], t.failed())
        t.pass('unit')
        assertTrue(t.failed().isEmpty())
        assertTrue(t.allPassed())
    }

    @Test
    void 'aborted groups are not failures'() {
        def t = new ResultTracker()
        t.start('unit'); t.abort('unit')
        t.start('lint'); t.pass('lint')
        assertTrue(t.failed().isEmpty())
        assertTrue(t.anyAborted())
        assertFalse(t.allPassed())
    }

    @Test
    void 'summary lists every group'() {
        def t = new ResultTracker()
        t.start('a'); t.pass('a')
        t.start('b')
        assertEquals('PASS  a\nFAIL  b', t.summary())
    }
}
