package gg.moonrise.hawkeye.core.service.importer.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class ServerBlockStatesTest {

    private final ServerBlockStates states = new ServerBlockStates();

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void statesTakeTheServersForm() {
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                states.normalize("minecraft:oak_stairs[facing=east,half=bottom]"));
        assertEquals("minecraft:stone", states.normalize("minecraft:stone"));
    }

    @Test
    public void aStateTheServerRejectsIsKept() {
        assertEquals("minecraft:no_such_block[x=1]", states.normalize("minecraft:no_such_block[x=1]"));
    }

    @Test
    public void eachStateIsParsedOnce() {
        String first = states.normalize("minecraft:oak_sign[rotation=4]");
        assertSame(first, states.normalize("minecraft:oak_sign[rotation=4]"));
    }
}
