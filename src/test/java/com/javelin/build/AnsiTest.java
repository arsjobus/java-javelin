package com.javelin.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnsiTest {
    @Test void plainTextIsUntouched() { assertEquals("hello world", Ansi.strip("hello world")); }

    @Test void stripsColourCodes() { assertEquals("error", Ansi.strip("\u001B[31;1merror\u001B[0m")); }

    @Test void stripsOscTerminatedByBell() { assertEquals("title", Ansi.strip("\u001B]0;window\u0007title")); }

    @Test void stripsOscTerminatedByStringTerminator() {
        assertEquals("link", Ansi.strip("\u001B]8;;https://example.com\u001B\\link\u001B]8;;\u001B\\"));
    }

    @Test void unterminatedOscDoesNotSwallowLaterText() {
        assertEquals("\u001B]0;title and more", Ansi.strip("\u001B]0;title and more"));
        assertEquals("a b", Ansi.strip("a \u001B]0;t\u0007b"));
    }

    @Test void stripsTwoCharacterEscapes() { assertEquals("ab", Ansi.strip("a\u001B=b")); }

    @Test void emptyStringStaysEmpty() { assertEquals("", Ansi.strip("")); }

    @Test void stripsCharsetSelection() { assertEquals("x", Ansi.strip("\u001B(Bx")); }
}
