package gg.moonrise.hawkeye.util.serialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.nbt.NbtGzip;
import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class ItemDetailsTest {

    @Test
    public void aWrittenBookHasItsTitleAuthorAndPagesInEveryStoredForm() {
        NbtTag.NbtCompound content = compound(
                "pages", list(NbtTag.COMPOUND,
                        compound("raw", compound("text", str("Chapter one"), "color", str("red"),
                                "bold", new NbtTag.NbtByte((byte) 1))),
                        compound("raw", str("Plain [two]"), "filtered", str("****")),
                        compound("text", str("Bare compound"), "extra", list(NbtTag.STRING, str("!"))),
                        compound("", str("Wrapped string"))),
                "title", compound("raw", str("Diary")),
                "author", str("Alex"),
                "generation", new NbtTag.NbtInt(0),
                "resolved", new NbtTag.NbtByte((byte) 1));
        byte[] bytes = item("minecraft:written_book", compound("minecraft:written_book_content", content));

        ItemDetails details = ItemDetails.of(bytes).orElseThrow();
        ItemDetails.BookContent book = details.book().orElseThrow();
        assertTrue(book.signed());
        assertEquals("Diary", book.title());
        assertEquals("Alex", book.author());
        assertEquals(List.of("Chapter one", "Plain [two]", "Bare compound!", "Wrapped string"),
                book.pages().stream().map(ItemDetailsTest::plain).toList());
        Component first = book.pages().getFirst();
        assertEquals(NamedTextColor.RED, first.color());
        assertEquals(TextDecoration.State.TRUE, first.decoration(TextDecoration.BOLD));
        assertFalse(details.isEmpty());
        assertEquals(details, ItemDetails.of(NbtGzip.gzip(bytes)).orElseThrow(), "gzip NBT reads the same");
    }

    @Test
    public void aWrittenBookWithABareTitleAndStringPages() {
        NbtTag.NbtCompound content = compound(
                "pages", list(NbtTag.STRING, str("first"), str("{\"text\":\"json\",\"italic\":true}")),
                "title", str("Notes"),
                "author", str("Steve"));
        ItemDetails.BookContent book = ItemDetails.of(item("minecraft:written_book",
                compound("minecraft:written_book_content", content))).orElseThrow().book().orElseThrow();
        assertEquals("Notes", book.title());
        assertEquals(List.of("first", "json"), book.pages().stream().map(ItemDetailsTest::plain).toList());
        assertEquals(TextDecoration.State.TRUE, book.pages().get(1).decoration(TextDecoration.ITALIC));
    }

    @Test
    public void clickEventsInPagesAreDropped() {
        NbtTag.NbtCompound content = compound(
                "pages", list(NbtTag.COMPOUND,
                        compound("text", str("click me"), "click_event",
                                compound("action", str("run_command"), "command", str("/op Griefer"))),
                        compound("raw", str("{\"text\":\"old\",\"clickEvent\":{\"action\":\"run_command\","
                                + "\"value\":\"/op Griefer\"}}"))),
                "title", str("Trap"),
                "author", str("Griefer"));
        ItemDetails.BookContent book = ItemDetails.of(item("minecraft:written_book",
                compound("minecraft:written_book_content", content))).orElseThrow().book().orElseThrow();
        assertEquals(List.of("click me", "old"), book.pages().stream().map(ItemDetailsTest::plain).toList());
        book.pages().forEach(page -> assertNull(page.clickEvent(), page.toString()));
    }

    @Test
    public void aBookAndQuillHasPlainPagesAndNoTitle() {
        NbtTag.NbtCompound content = compound("pages", list(NbtTag.COMPOUND,
                compound("raw", str("draft {not json")),
                compound("", str("[Chapter]"))));
        ItemDetails.BookContent book = ItemDetails.of(item("minecraft:writable_book",
                compound("minecraft:writable_book_content", content))).orElseThrow().book().orElseThrow();
        assertFalse(book.signed());
        assertEquals("", book.title());
        assertEquals("", book.author());
        assertEquals(List.of(Component.text("draft {not json"), Component.text("[Chapter]")), book.pages());
    }

    @Test
    public void aCustomNameIsATextComponent() {
        ItemDetails compound = ItemDetails.of(item("minecraft:diamond_sword", compound("minecraft:custom_name",
                compound("text", str("Excalibur"), "color", str("gold"))))).orElseThrow();
        assertEquals("Excalibur", plain(compound.name().orElseThrow()));
        assertEquals(NamedTextColor.GOLD, compound.name().orElseThrow().color());

        ItemDetails string = ItemDetails.of(item("minecraft:stick", compound("minecraft:custom_name",
                str("Wand")))).orElseThrow();
        assertEquals(Component.text("Wand"), string.name().orElseThrow());

        ItemDetails list = ItemDetails.of(item("minecraft:stick", compound("minecraft:custom_name",
                list(NbtTag.STRING, str("Big "), str("Stick"))))).orElseThrow();
        assertEquals("Big Stick", plain(list.name().orElseThrow()));

        ItemDetails legacy = ItemDetails.of(item("minecraft:stick", compound("minecraft:custom_name",
                str("{\"text\":\"Old\",\"color\":\"aqua\"}")))).orElseThrow();
        assertEquals("Old", plain(legacy.name().orElseThrow()));
        assertEquals(NamedTextColor.AQUA, legacy.name().orElseThrow().color());
    }

    @Test
    public void enchantmentsAreReadInTheNewAndTheOldForm() {
        ItemDetails current = ItemDetails.of(item("minecraft:diamond_sword", compound("minecraft:enchantments",
                compound("minecraft:sharpness", new NbtTag.NbtInt(5), "minecraft:unbreaking",
                        new NbtTag.NbtInt(3))))).orElseThrow();
        assertEquals(List.of(new ItemDetails.Enchantment("minecraft:sharpness", 5),
                new ItemDetails.Enchantment("minecraft:unbreaking", 3)), current.enchantments());

        ItemDetails old = ItemDetails.of(item("minecraft:bow", compound("minecraft:enchantments",
                compound("levels", compound("minecraft:power", new NbtTag.NbtInt(4)),
                        "show_in_tooltip", new NbtTag.NbtByte((byte) 0))))).orElseThrow();
        assertEquals(List.of(new ItemDetails.Enchantment("minecraft:power", 4)), old.enchantments());
    }

    @Test
    public void anEnchantedBookShowsItsStoredEnchantments() {
        ItemDetails book = ItemDetails.of(item("minecraft:enchanted_book", compound(
                "minecraft:stored_enchantments", compound("minecraft:mending", new NbtTag.NbtInt(1)))))
                .orElseThrow();
        assertEquals(List.of(new ItemDetails.Enchantment("minecraft:mending", 1)), book.enchantments());
        assertTrue(book.book().isEmpty(), "an enchanted book is not a written book");
    }

    @Test
    public void anItemWithoutComponentsHasNoDetails() {
        ItemDetails plain = ItemDetails.of(ItemSummaryTest.item("minecraft:stone", 64)).orElseThrow();
        assertTrue(plain.isEmpty());
        ItemDetails other = ItemDetails.of(item("minecraft:leather_helmet", compound("minecraft:dyed_color",
                new NbtTag.NbtInt(0xff0000)))).orElseThrow();
        assertTrue(other.isEmpty(), "components that show nothing here");
    }

    @Test
    public void malformedComponentsDegradeInsteadOfThrowing() {
        ItemDetails details = ItemDetails.of(item("minecraft:written_book", compound(
                "minecraft:custom_name", new NbtTag.NbtInt(7),
                "minecraft:enchantments", compound("minecraft:sharpness", str("five")),
                "minecraft:written_book_content", compound(
                        "pages", list(NbtTag.INT, new NbtTag.NbtInt(1)),
                        "title", new NbtTag.NbtInt(3))))).orElseThrow();
        assertTrue(details.name().isEmpty());
        assertTrue(details.enchantments().isEmpty());
        ItemDetails.BookContent book = details.book().orElseThrow();
        assertEquals("", book.title());
        assertEquals(List.of(Component.empty()), book.pages());

        ItemDetails unreadable = ItemDetails.of(item("minecraft:written_book", compound(
                "minecraft:written_book_content", compound("pages", list(NbtTag.COMPOUND,
                        compound("text", str("kept"), "color", new NbtTag.NbtInt(-1),
                                "hover_event", compound("action", str("nonsense")))))))).orElseThrow();
        assertEquals("kept", plain(unreadable.book().orElseThrow().pages().getFirst()));
    }

    @Test
    public void bytesThatAreNotItemNbtHaveNoDetails() {
        assertTrue(ItemDetails.of(new byte[] {(byte) 0xac, (byte) 0xed, 0, 5}).isEmpty(), "Java serialization");
        assertTrue(ItemDetails.of(new byte[] {10, 0}).isEmpty(), "truncated NBT");
        assertTrue(ItemDetails.of(new byte[0]).isEmpty(), "no bytes");
        assertTrue(ItemDetails.of(new byte[] {1, 2, 3, 4, 5, 6}).isEmpty(), "garbage");
    }

    /**
     * The raw NBT of an item with data components, as Paper writes it.
     *
     * @param id         the material
     * @param components the {@code components} compound
     * @return the bytes
     */
    public static byte @NotNull [] item(@NotNull String id, @NotNull NbtTag.NbtCompound components) {
        return new NbtRoot("", compound("id", str(id), "count", new NbtTag.NbtInt(1),
                "components", components, "DataVersion", new NbtTag.NbtInt(4800))).write();
    }

    /**
     * A compound from alternating keys and tags.
     *
     * @param entries key, tag, key, tag, ...
     * @return the compound
     */
    public static @NotNull NbtTag.NbtCompound compound(@NotNull Object... entries) {
        Map<String, NbtTag> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], (NbtTag) entries[i + 1]);
        }
        return new NbtTag.NbtCompound(values);
    }

    /**
     * A list of one element type.
     *
     * @param type     the element type id
     * @param elements the elements
     * @return the list
     */
    public static @NotNull NbtTag.NbtList list(int type, @NotNull NbtTag... elements) {
        return NbtTag.NbtList.of(type, elements);
    }

    /**
     * A string tag.
     *
     * @param value the value
     * @return the tag
     */
    public static @NotNull NbtTag.NbtString str(@NotNull String value) {
        return new NbtTag.NbtString(value);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
