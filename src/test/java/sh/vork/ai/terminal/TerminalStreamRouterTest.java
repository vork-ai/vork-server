package sh.vork.ai.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalStreamRouterTest {

    @Test
    void sanitizeForModel_removesAnsiAndControlCharacters() {
        String raw = "\u001B[31mhelp\u001B[0m\r\nmore\u0007text";
        assertEquals("help\nmoretext", TerminalStreamRouter.sanitizeForModel(raw));
    }

    @Test
    void sanitizeForModel_returnsPlaceholderWhenOnlyControlsRemain() {
        String raw = "\u001B[0m\u0007\r\n";
        assertEquals("[terminal output omitted: control characters only]",
                TerminalStreamRouter.sanitizeForModel(raw));
    }

    @Test
    void selectUiOutput_usesBufferedCommandOutputWhenStreamIsEmpty() {
        assertEquals("file-one\nfile-two\n",
                TerminalStreamRouter.selectUiOutput("ls -l", "file-one\nfile-two\n"));
    }

    @Test
    void normalizeUiOutput_stripsEchoedCommandPrefix() {
        assertEquals("Filesystem  Size Used Avail Use% Mounted on\n",
                TerminalStreamRouter.normalizeUiOutput("df -h",
                        "ls -ldf -h\nFilesystem  Size Used Avail Use% Mounted on\n"));
    }

    @Test
    void normalizeUiOutput_stripsWholeEchoLineWhenCommandIsRepeated() {
        assertEquals("total 456\n-rw-r--r--  1 lee  staff  123 May 17 10:00 pom.xml\n",
                TerminalStreamRouter.normalizeUiOutput("ls -l",
                        "$ ls -lls -l\ntotal 456\n-rw-r--r--  1 lee  staff  123 May 17 10:00 pom.xml\n"));
    }

    @Test
    void normalizeUiOutput_returnsEmptyWhenOnlyEchoedCommandRemains() {
        assertTrue(TerminalStreamRouter.normalizeUiOutput("df -h", "ls -ldf -h").isEmpty());
    }

    @Test
    void hasDisplayableContent_falseForAnsiOnlyChunks() {
        assertTrue(!TerminalStreamRouter.hasDisplayableContent("\u001B[0m\u001B[?2004h"));
    }

    @Test
    void hasDisplayableContent_trueForTextChunks() {
        assertTrue(TerminalStreamRouter.hasDisplayableContent("total 12\n"));
    }
}
