package gg.moonrise.hawkeye.core.service.importer.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.entity.payload.SignSides;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

public class LegacySignTextTest {

    private static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();

    @Test
    public void legacyCodesBecomeAdventureJson() {
        List<String> front = LegacySignText.side(List.of("§cRed", "§lBold§r plain", "plain", ""));
        assertEquals(4, front.size());
        assertEquals(Component.text("Red", NamedTextColor.RED), JSON.deserialize(front.get(0)).compact());
        assertEquals(Component.text("plain"), JSON.deserialize(front.get(2)).compact());
        assertEquals("", plain(front.get(3)));
        Component bold = JSON.deserialize(front.get(1));
        assertEquals("Bold plain", plain(front.get(1)));
        assertEquals(TextDecoration.State.TRUE, bold.children().isEmpty() ? bold.decoration(TextDecoration.BOLD)
                : bold.children().getFirst().decoration(TextDecoration.BOLD));
    }

    @Test
    public void aSideWithoutTextHasNoLines() {
        assertEquals(List.of(), LegacySignText.side(List.of("", "", "", "")));
        assertEquals(List.of(), LegacySignText.side(List.of("§c", "", "§l", "")));
    }

    @Test
    public void linesOneToFourAreTheFrontAndFiveToEightTheBack() {
        SignSides sides = LegacySignText.sides(List.of("a", "", "", "", "", "", "", "h"));
        assertEquals(List.of("a", "", "", ""), sides.front().stream().map(LegacySignTextTest::plain).toList());
        assertEquals(List.of("", "", "", "h"), sides.back().stream().map(LegacySignTextTest::plain).toList());
        assertEquals(SignSides.EMPTY, LegacySignText.sides(List.of("", "", "", "", "", "", "", "")));
    }

    private static String plain(String json) {
        return PlainTextComponentSerializer.plainText().serialize(JSON.deserialize(json));
    }
}
