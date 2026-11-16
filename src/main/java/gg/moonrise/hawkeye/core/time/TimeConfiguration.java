package gg.moonrise.hawkeye.core.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The wall clock every time-dependent bean receives, so tests can substitute a fixed one. */
@Configuration(proxyBeanMethods = false)
public final class TimeConfiguration {

    /**
     * The system clock in UTC.
     *
     * @return the clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
