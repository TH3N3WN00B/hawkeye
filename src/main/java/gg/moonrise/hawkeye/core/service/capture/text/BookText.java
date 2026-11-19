package gg.moonrise.hawkeye.core.service.capture.text;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.NotNull;

/**
 * The recorded form of a written book: {@code title: <title>} first when it is signed, then the plain
 * text of each page that is not blank, on one line, separated by {@value #SEPARATOR}, and cut to
 * {@value #MAX_LENGTH} characters.
 */
public final class BookText {

    /** The longest text recorded for one book. */
    public static final int MAX_LENGTH = 4000;

    /** What separates the title and the pages. */
    public static final String SEPARATOR = " | ";

    private BookText() {
    }

    /**
     * What to record for a book edit.
     *
     * @param book    the book after the edit
     * @param signing whether the book is being signed
     * @return the title (when signing) and the pages
     */
    public static @NotNull String recorded(@NotNull BookMeta book, boolean signing) {
        List<String> parts = new ArrayList<>();
        String title = book.getTitle();
        if (signing && title != null && !title.isBlank()) {
            parts.add("title: " + PlainText.oneLine(title));
        }
        for (Component page : book.pages()) {
            String text = PlainText.oneLine(page);
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        return PlainText.capped(String.join(SEPARATOR, parts), MAX_LENGTH);
    }
}
