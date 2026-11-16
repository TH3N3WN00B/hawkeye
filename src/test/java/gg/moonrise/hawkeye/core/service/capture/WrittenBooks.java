package gg.moonrise.hawkeye.core.service.capture;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mockbukkit.mockbukkit.inventory.meta.BookMetaMock;

/**
 * Book metas with component pages. MockBukkit 4.120's {@code BookMetaMock#pages()} is not implemented,
 * and {@code PlayerEditBookEvent#getNewBookMeta()} clones the meta, so the fake keeps its pages through
 * {@code clone()}.
 */
public final class WrittenBooks {

    private WrittenBooks() {
    }

    /**
     * A book.
     *
     * @param title its title, or {@code null} for an unsigned book
     * @param pages the page texts
     * @return the meta
     */
    public static @NotNull BookMeta book(@Nullable String title, @NotNull String @NotNull ... pages) {
        List<Component> components = new ArrayList<>();
        for (String page : pages) {
            components.add(Component.text(page));
        }
        Book book = new Book(components);
        if (title != null) {
            book.setTitle(title);
        }
        return book;
    }

    private static final class Book extends BookMetaMock {

        private final List<Component> pages;

        private Book(@NotNull List<Component> pages) {
            this.pages = List.copyOf(pages);
        }

        private Book(@NotNull Book other) {
            super(other);
            this.pages = other.pages;
        }

        @Override
        public @NotNull List<Component> pages() {
            return pages;
        }

        @Override
        public @NotNull Book clone() {
            return new Book(this);
        }
    }
}
