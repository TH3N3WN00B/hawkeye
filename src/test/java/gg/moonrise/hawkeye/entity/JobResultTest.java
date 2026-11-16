package gg.moonrise.hawkeye.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.entity.job.JobResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class JobResultTest {

    @Test
    public void roundTripsBothOutcomes() {
        JobResult applied = new JobResult.Applied(12, 3, 1, 450);
        JobResult failed = new JobResult.Failed("world | missing");
        assertEquals(Optional.of(applied), JobResult.decode(applied.encode()));
        assertEquals(Optional.of(failed), JobResult.decode(failed.encode()));
        assertEquals(Optional.empty(), JobResult.decode("ok|1|2"));
    }
}
