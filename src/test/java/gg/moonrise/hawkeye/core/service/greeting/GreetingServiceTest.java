package gg.moonrise.hawkeye.core.service.greeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import gg.moonrise.hawkeye.config.HawkeyeConfig;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class GreetingServiceTest {

    private TestHawkeyePlugin plugin;
    private GreetingService service;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        service = TestPlugins.bean(GreetingService.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void firstJoinAndReturningPlayersGetTheirOwnMessage() {
        HawkeyeConfig.Messages defaults = new HawkeyeConfig().getMessages();
        GreetingResult.Greet first = assertInstanceOf(GreetingResult.Greet.class, service.greetingFor(true));
        assertEquals(defaults.getFirstJoin(), first.message().content());
        GreetingResult.Greet back = assertInstanceOf(GreetingResult.Greet.class, service.greetingFor(false));
        assertEquals(defaults.getWelcomeBack(), back.message().content());
    }

    @Test
    public void disabledGreetingsGreetNobody() {
        TestPlugins.writeConfig(plugin, "greetings-enabled: false\n");
        TestPlugins.bean(HawkeyeConfigProvider.class).reloadConfig();
        assertInstanceOf(GreetingResult.Disabled.class, service.greetingFor(true));
    }
}
