package gg.moonrise.hawkeye.util.serialize;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.nbt.NbtGzip;
import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a stored item shows beyond its material and count, read from its NBT without a server: the
 * custom name, the enchantments and, for a written book or a book and quill, the book itself.
 * {@link ItemBytes} stores the raw NBT of {@code ItemStack#serializeAsBytes}, whose root holds the data
 * components under {@code components} in the shapes of Minecraft 1.21.5 and later:
 * <ul>
 *   <li>{@code minecraft:custom_name}: a text component;</li>
 *   <li>{@code minecraft:enchantments} and {@code minecraft:stored_enchantments}: enchantment id to level
 *       ({@code {"minecraft:sharpness": 5}}), or the older {@code {levels: {...}, show_in_tooltip: 1b}};</li>
 *   <li>{@code minecraft:written_book_content}: {@code pages} (each a text component, bare or as
 *       {@code {raw: ..., filtered: ...}}), {@code title} (a string, bare or as {@code {raw: ...}}),
 *       {@code author}, {@code generation}, {@code resolved};</li>
 *   <li>{@code minecraft:writable_book_content}: {@code pages}, each a string, bare or as
 *       {@code {raw: ...}}.</li>
 * </ul>
 * A text component in NBT is a string (plain text), a list (the first element with the rest as its
 * siblings) or a compound with the keys of the JSON form ({@code text}, {@code extra}, {@code color},
 * {@code bold} as a byte, ...). Items written before 1.21.5 hold the JSON form as a string, which is read
 * as JSON when it parses as one. Click events are dropped everywhere: these components are shown to the
 * staff member who looks the item up, and a command hidden in a book page must never run as them.
 * Anything malformed is left out, never thrown.
 *
 * @param customName   the custom name, or {@code null}
 * @param enchantments the enchantments, then the stored enchantments of an enchanted book, in stored order
 * @param bookContent  the book of a written book or a book and quill, or {@code null}
 */
public record ItemDetails(@Nullable Component customName, @NotNull List<Enchantment> enchantments,
        @Nullable BookContent bookContent) {

    private static final String COMPONENTS = "components";
    private static final String CUSTOM_NAME = "minecraft:custom_name";
    private static final String ENCHANTMENTS = "minecraft:enchantments";
    private static final String STORED_ENCHANTMENTS = "minecraft:stored_enchantments";
    private static final String WRITTEN_BOOK = "minecraft:written_book_content";
    private static final String WRITABLE_BOOK = "minecraft:writable_book_content";
    /** The wrapper of a filterable value: the text as written (the filtered text is for chat filtering). */
    private static final String RAW = "raw";
    /** A heterogeneous NBT list wraps each element that is not a compound in a compound under this key. */
    private static final String WRAPPED = "";
    private static final Set<String> CLICK_KEYS = Set.of("click_event", "clickEvent");
    private static final TypeAdapter<JsonElement> JSON = new Gson().getAdapter(JsonElement.class);

    /**
     * Copies the enchantments.
     *
     * @param customName   the custom name, or {@code null}
     * @param enchantments the enchantments
     * @param bookContent  the book, or {@code null}
     */
    public ItemDetails {
        enchantments = List.copyOf(enchantments);
    }

    /**
     * One enchantment.
     *
     * @param id    the namespaced enchantment ({@code minecraft:sharpness})
     * @param level its level
     */
    public record Enchantment(@NotNull String id, int level) {
    }

    /**
     * The text of a written book or a book and quill.
     *
     * @param title  the title, or empty (a book and quill has none)
     * @param author the author, or empty
     * @param pages  the pages in order; a page that cannot be read is empty
     * @param signed {@code true} for a written book, {@code false} for a book and quill
     */
    public record BookContent(@NotNull String title, @NotNull String author, @NotNull List<Component> pages,
            boolean signed) {

        /**
         * Copies the pages.
         *
         * @param title  the title
         * @param author the author
         * @param pages  the pages
         * @param signed whether the book is signed
         */
        public BookContent {
            pages = List.copyOf(pages);
        }
    }

    /**
     * Reads what an item shows.
     *
     * @param bytes raw or gzip item NBT
     * @return the details (possibly {@linkplain #isEmpty() empty}), or empty when the bytes are not item NBT
     */
    public static @NotNull Optional<ItemDetails> of(byte @NotNull [] bytes) {
        NbtTag.NbtCompound item;
        try {
            item = NbtRoot.read(NbtGzip.raw(bytes)).compound();
        } catch (CodecException e) {
            return Optional.empty();
        }
        NbtTag.NbtCompound components = item.compound(COMPONENTS).orElse(new NbtTag.NbtCompound(Map.of()));
        Component name = components.get(CUSTOM_NAME).flatMap(ItemDetails::text).orElse(null);
        List<Enchantment> enchantments = new ArrayList<>();
        components.compound(ENCHANTMENTS).ifPresent(levels -> enchantments(levels, enchantments));
        components.compound(STORED_ENCHANTMENTS).ifPresent(levels -> enchantments(levels, enchantments));
        BookContent book = components.compound(WRITTEN_BOOK).map(ItemDetails::writtenBook)
                .or(() -> components.compound(WRITABLE_BOOK).map(ItemDetails::writableBook))
                .orElse(null);
        return Optional.of(new ItemDetails(name, enchantments, book));
    }

    /**
     * The custom name.
     *
     * @return the name, or empty when the item has none
     */
    public @NotNull Optional<Component> name() {
        return Optional.ofNullable(customName);
    }

    /**
     * The book.
     *
     * @return the book, or empty when the item is not a written book or a book and quill
     */
    public @NotNull Optional<BookContent> book() {
        return Optional.ofNullable(bookContent);
    }

    /**
     * Whether there is nothing to show beyond the material.
     *
     * @return {@code true} without a name, enchantments and a book
     */
    public boolean isEmpty() {
        return customName == null && enchantments.isEmpty() && bookContent == null;
    }

    private static void enchantments(@NotNull NbtTag.NbtCompound component, @NotNull List<Enchantment> into) {
        NbtTag.NbtCompound levels = component.compound("levels").orElse(component);
        for (Map.Entry<String, NbtTag> entry : levels.values().entrySet()) {
            integer(entry.getValue()).ifPresent(level -> into.add(new Enchantment(entry.getKey(), level)));
        }
    }

    private static @NotNull BookContent writtenBook(@NotNull NbtTag.NbtCompound content) {
        List<Component> pages = new ArrayList<>();
        for (NbtTag page : list(content.get("pages").orElse(null))) {
            pages.add(text(filterable(page)).orElse(Component.empty()));
        }
        String title = string(filterable(content.get("title").orElse(null))).orElse("");
        String author = string(content.get("author").orElse(null)).orElse("");
        return new BookContent(title, author, pages, true);
    }

    private static @NotNull BookContent writableBook(@NotNull NbtTag.NbtCompound content) {
        List<Component> pages = new ArrayList<>();
        for (NbtTag page : list(content.get("pages").orElse(null))) {
            NbtTag raw = filterable(page);
            pages.add(string(raw).<Component>map(Component::text)
                    .or(() -> text(raw))
                    .orElse(Component.empty()));
        }
        return new BookContent("", "", pages, false);
    }

    /** The text as written of a filterable value: the value itself, or its {@code raw} entry. */
    private static @Nullable NbtTag filterable(@Nullable NbtTag value) {
        NbtTag unwrapped = unwrap(value);
        if (unwrapped instanceof NbtTag.NbtCompound compound && compound.get(RAW).isPresent()) {
            return unwrap(compound.get(RAW).get());
        }
        return unwrapped;
    }

    /** The element of a heterogeneous list's wrapper compound ({@code {"": value}}), or the value. */
    private static @Nullable NbtTag unwrap(@Nullable NbtTag value) {
        if (value instanceof NbtTag.NbtCompound compound && compound.values().size() == 1
                && compound.get(WRAPPED).isPresent()) {
            return compound.get(WRAPPED).get();
        }
        return value;
    }

    private static @NotNull List<NbtTag> list(@Nullable NbtTag value) {
        return value instanceof NbtTag.NbtList list ? list.values() : List.of();
    }

    private static @NotNull Optional<String> string(@Nullable NbtTag value) {
        return value instanceof NbtTag.NbtString string ? Optional.of(string.value()) : Optional.empty();
    }

    private static @NotNull Optional<Integer> integer(@Nullable NbtTag value) {
        return switch (value) {
            case NbtTag.NbtInt number -> Optional.of(number.value());
            case NbtTag.NbtShort number -> Optional.of((int) number.value());
            case NbtTag.NbtByte number -> Optional.of((int) number.value());
            case null, default -> Optional.empty();
        };
    }

    /**
     * A text component stored in NBT: a string (plain text, or JSON in items from before 1.21.5), a list or
     * a compound. A component Adventure cannot read falls back to its plain text.
     */
    private static @NotNull Optional<Component> text(@Nullable NbtTag value) {
        NbtTag tag = unwrap(value);
        return switch (tag) {
            case NbtTag.NbtString string -> Optional.of(fromString(string.value()));
            case NbtTag.NbtCompound compound -> Optional.of(fromTree(json(compound), compound));
            case NbtTag.NbtList list -> Optional.of(fromTree(json(list), list));
            case null, default -> Optional.empty();
        };
    }

    private static @NotNull Component fromString(@NotNull String value) {
        String trimmed = value.strip();
        boolean json = trimmed.startsWith("{") || trimmed.startsWith("[")
                || trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"");
        if (json) {
            try {
                return GsonComponentSerializer.gson().deserializeFromTree(withoutClicks(strictJson(value)));
            } catch (IOException | RuntimeException e) {
                // Plain text that only looks like JSON ("[Chapter 1]").
            }
        }
        return Component.text(value);
    }

    /**
     * Parses a whole string as strict JSON: a lenient parse would read {@code [Chapter]} as an array of an
     * unquoted string and lose the brackets of a plain page. {@code setLenient(false)} rather than Gson 2.11's
     * {@code Strictness}, which Paper 1.21.1 does not ship.
     */
    private static @NotNull JsonElement strictJson(@NotNull String value) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(value));
        reader.setLenient(false);
        JsonElement tree = JSON.read(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IOException("text after the JSON value");
        }
        return tree;
    }

    private static @NotNull Component fromTree(@NotNull JsonElement tree, @NotNull NbtTag original) {
        try {
            return GsonComponentSerializer.gson().deserializeFromTree(tree);
        } catch (RuntimeException e) {
            StringBuilder plain = new StringBuilder();
            plain(original, plain);
            return Component.text(plain.toString());
        }
    }

    /** The NBT form of a component as its JSON form, without click events. */
    private static @NotNull JsonElement json(@NotNull NbtTag tag) {
        return switch (tag) {
            case NbtTag.NbtByte value -> value.value() == 0 || value.value() == 1
                    ? new JsonPrimitive(value.value() == 1) : new JsonPrimitive(value.value());
            case NbtTag.NbtShort value -> new JsonPrimitive(value.value());
            case NbtTag.NbtInt value -> new JsonPrimitive(value.value());
            case NbtTag.NbtLong value -> new JsonPrimitive(value.value());
            case NbtTag.NbtFloat value -> new JsonPrimitive(value.value());
            case NbtTag.NbtDouble value -> new JsonPrimitive(value.value());
            case NbtTag.NbtString value -> new JsonPrimitive(value.value());
            case NbtTag.NbtByteArray value -> {
                JsonArray array = new JsonArray();
                for (byte element : value.values()) {
                    array.add(element);
                }
                yield array;
            }
            case NbtTag.NbtIntArray value -> {
                JsonArray array = new JsonArray();
                for (int element : value.values()) {
                    array.add(element);
                }
                yield array;
            }
            case NbtTag.NbtLongArray value -> {
                JsonArray array = new JsonArray();
                for (long element : value.values()) {
                    array.add(element);
                }
                yield array;
            }
            case NbtTag.NbtList list -> {
                JsonArray array = new JsonArray();
                list.values().forEach(element -> array.add(json(Objects.requireNonNull(unwrap(element)))));
                yield array;
            }
            case NbtTag.NbtCompound compound -> {
                JsonObject object = new JsonObject();
                compound.values().forEach((key, value) -> {
                    if (!CLICK_KEYS.contains(key)) {
                        object.add(key, json(value));
                    }
                });
                yield object;
            }
        };
    }

    private static @NotNull JsonElement withoutClicks(@NotNull JsonElement tree) {
        if (tree instanceof JsonObject object) {
            JsonObject copy = new JsonObject();
            object.entrySet().forEach(entry -> {
                if (!CLICK_KEYS.contains(entry.getKey())) {
                    copy.add(entry.getKey(), withoutClicks(entry.getValue()));
                }
            });
            return copy;
        }
        if (tree instanceof JsonArray array) {
            JsonArray copy = new JsonArray();
            array.forEach(element -> copy.add(withoutClicks(element)));
            return copy;
        }
        return tree;
    }

    /** The plain text of a component Adventure could not read: its {@code text} entries in order. */
    private static void plain(@Nullable NbtTag tag, @NotNull StringBuilder into) {
        switch (unwrap(tag)) {
            case NbtTag.NbtString string -> into.append(string.value());
            case NbtTag.NbtList list -> list.values().forEach(element -> plain(element, into));
            case NbtTag.NbtCompound compound -> {
                compound.get("text").ifPresent(text -> plain(text, into));
                compound.get("extra").ifPresent(extra -> plain(extra, into));
            }
            case null, default -> {
            }
        }
    }
}
