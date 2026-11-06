import org.junit.jupiter.api.Test
import prgate.VmName

import static org.junit.jupiter.api.Assertions.*

class VmNameTest {

    @Test
    void 'sanitises job names'() {
        assertEquals('ci-my-repo-pr-42-17', VmName.forBuild('ci', 'My_Repo » PR-42', 17))
    }

    @Test
    void 'long names are truncated but keep the build number'() {
        String name = VmName.forBuild('ci', 'x' * 200, 12345)
        assertTrue(name.length() <= VmName.MAX_LENGTH)
        assertTrue(name.endsWith('-12345'))
    }

    @Test
    void 'null parts are skipped'() {
        assertEquals('ci-7', VmName.forBuild('ci', null, 7))
    }
}
