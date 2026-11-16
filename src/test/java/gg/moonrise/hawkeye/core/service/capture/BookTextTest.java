package gg.moonrise.hawkeye.core.service.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class BookTextTest {

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void pagesAreJoinedOnOneLineAndBlankPagesSkipped() {
        assertEquals("Dear diary | it rained", BookText.recorded(WrittenBooks.book(null, "Dear\ndiary", " ",
                "it rained"), false));
    }

    @Test
    public void aSignedBookStartsWithItsTitle() {
        assertEquals("title: Secrets | the base is at 100 -200", BookText.recorded(WrittenBooks.book("Secrets",
                "the base is at 100 -200"), true));
    }

    @Test
    public void theTitleIsOnlyRecordedWhenSigning() {
        assertEquals("draft", BookText.recorded(WrittenBooks.book("Secrets", "draft"), false));
    }

    @Test
    public void longBooksAreCut() {
        String page = "x".repeat(1000);
        String text = BookText.recorded(WrittenBooks.book(null, page, page, page, page, page), false);

        assertEquals(BookText.MAX_LENGTH, text.length());
        assertTrue(text.startsWith(page + BookText.SEPARATOR));
    }

    @Test
    public void anEmptyBookIsEmptyText() {
        assertEquals("", BookText.recorded(WrittenBooks.book(null), false));
    }
}
