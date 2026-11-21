package gg.moonrise.hawkeye.util.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ImportStateCodecTest {

    private static final ImportState STATE = new ImportState(7, "survival-coreprotect 2=ü", "rowid",
            Map.of(SourceTable.BLOCK, new SourceCursor(1_767_225_600, 812), SourceTable.ENTITY_CONTAINER,
                    new SourceCursor(1_767_225_601, Long.MAX_VALUE)),
            31_622_400, 812, 3, Map.of(SkipReason.NO_EQUIVALENT, 3L, SkipReason.BEFORE_EPOCH, 9_000_000_000L), true,
            1_767_225_600_000L, 1_767_225_700_000L);

    @Test
    public void roundTripsEveryField() {
        String text = ImportStateCodec.encode(STATE);
        assertEquals(STATE, ImportStateCodec.decode(text, 7, 1_767_225_700_000L));
    }

    @Test
    public void aFreshStateRoundTrips() {
        ImportState fresh = ImportState.fresh("creative-coreprotect", "time", 42);
        assertEquals(fresh, ImportStateCodec.decode(ImportStateCodec.encode(fresh), 1, 42));
    }

    @Test
    public void theTextIsReadableLines() {
        String text = ImportStateCodec.encode(STATE);
        assertTrue(text.startsWith("v=1\n"), text);
        assertTrue(text.contains("\ncursor.block=1767225600:812\n"), text);
        assertTrue(text.contains("\nskipped.no_equivalent=3\n"), text);
        assertTrue(text.contains("\ncomplete=true\n"), text);
    }

    @Test
    public void unknownKeysTablesAndReasonsAreIgnored() {
        String text = ImportStateCodec.encode(STATE) + "\nfuture-field=1\ncursor.hologram=5:6\nskipped.later=4\n\n";
        assertEquals(STATE, ImportStateCodec.decode(text, 7, 1_767_225_700_000L));
    }

    @Test
    public void anUnknownVersionOrAMissingFieldIsRejected() {
        String text = ImportStateCodec.encode(STATE);
        assertThrows(CodecException.class, () -> ImportStateCodec.decode(text.replace("v=1", "v=2"), 1, 0));
        assertThrows(CodecException.class, () -> ImportStateCodec.decode(text.replace("\nimported=812", ""), 1, 0));
        assertThrows(CodecException.class, () -> ImportStateCodec.decode(text.replace("imported=812", "imported=x"),
                1, 0));
        assertThrows(CodecException.class, () -> ImportStateCodec.decode(text.replace("1767225600:812", "17"), 1,
                0));
    }
}
