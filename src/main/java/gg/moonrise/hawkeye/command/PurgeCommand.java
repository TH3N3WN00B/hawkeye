package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.command.support.QueryInput;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.config.message.PurgeMessages;
import gg.moonrise.hawkeye.core.service.purge.PurgeOutcome;
import gg.moonrise.hawkeye.core.service.purge.PurgeRequest;
import gg.moonrise.hawkeye.core.service.purge.PurgeService;
import gg.moonrise.hawkeye.core.service.query.BuildResult;
import gg.moonrise.hawkeye.core.service.query.QueryParameters;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.util.Durations;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /hawkeye purge t:<age> [r:#<world>]}: deletes events older than the age from the home
 * database, optionally only in some of this server's worlds. Players may only purge data older than
 * {@code purge.player-minimum-days}; the console has no limit.
 */
@SpringComponent
@RequiredArgsConstructor
public final class PurgeCommand implements PaperCommand {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull QueryInput queryInput;
    private final @NotNull PurgeService purgeService;
    private final @NotNull ServerThreads threads;

    @Command(Permissions.ROOT + " purge <parameters>")
    @CommandDescription("Deletes events older than t:<age>")
    @Permission(Permissions.PURGE)
    public void purge(@NotNull CommandSourceStack source,
            @Argument(value = "parameters", suggestions = QueryInput.SUGGESTIONS) @Greedy @NotNull String parameters) {
        CommandSender sender = source.getSender();
        Optional<BuildResult.Built> built = queryInput.read(sender, parameters, 0, 0);
        if (built.isEmpty()) {
            return;
        }
        QueryParameters given = built.get().parameters();
        if (given.time() instanceof QueryParameters.TimeSpec.Any) {
            Message.of(general().getTimeRequired()).send(sender, Placeholder.unparsed("command", "purge"));
            return;
        }
        Optional<PurgeRequest> request = request(given, sender instanceof Player);
        if (request.isEmpty()) {
            Message.of(messages().getUnsupportedParameters()).send(sender);
            return;
        }
        String age = Durations.format(request.get().olderThan());
        purgeService.purge(request.get(), () -> Message.of(messages().getStarted()).send(sender,
                        Placeholder.unparsed("age", age)))
                .whenComplete((outcome, error) -> {
                    PurgeOutcome reply = outcome != null ? outcome : new PurgeOutcome.Failed(
                            Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
                    threads.runFor(sender, () -> tell(sender, reply));
                });
    }

    /** The purge the parameters ask for: one age, optionally one world, nothing else. */
    private static @NotNull Optional<PurgeRequest> request(@NotNull QueryParameters given, boolean player) {
        if (!(given.time() instanceof QueryParameters.TimeSpec.Within within)
                || !(given.servers() instanceof QueryParameters.ServerSpec.Local)
                || !given.actors().isEmpty() || !given.excludedActors().isEmpty() || !given.actions().isEmpty()
                || !given.includedTypes().isEmpty() || !given.excludedTypes().isEmpty() || !given.flags().isEmpty()) {
            return Optional.empty();
        }
        Optional<Set<String>> worlds = switch (given.radius()) {
            case QueryParameters.RadiusSpec.World world -> Optional.of(Set.of(world.world()));
            case QueryParameters.RadiusSpec.Global global -> Optional.of(Set.of());
            case QueryParameters.RadiusSpec.Unspecified unspecified -> Optional.of(Set.of());
            case QueryParameters.RadiusSpec.Blocks blocks -> Optional.empty();
        };
        return worlds.map(names -> new PurgeRequest(Duration.ofMillis(within.millis()), names, player));
    }

    private void tell(@NotNull CommandSender sender, @NotNull PurgeOutcome outcome) {
        PurgeMessages messages = messages();
        switch (outcome) {
            case PurgeOutcome.Purged purged -> Message.of(messages.getFinished()).send(sender,
                    Placeholder.unparsed("events", Long.toString(purged.events())),
                    Placeholder.unparsed("seconds", String.format(Locale.ROOT, "%.1f", purged.seconds())));
            case PurgeOutcome.TooRecent tooRecent -> Message.of(messages.getTooRecent()).send(sender,
                    Placeholder.unparsed("days", Integer.toString(tooRecent.minimumDays())));
            case PurgeOutcome.AlreadyRunning running -> Message.of(messages.getAlreadyRunning()).send(sender);
            case PurgeOutcome.NotReady notReady -> Message.of(general().getStorageNotReady()).send(sender);
            case PurgeOutcome.Failed failed -> Message.of(general().getOperationFailed()).send(sender,
                    Placeholder.unparsed("reason", failed.reason()));
        }
    }

    private @NotNull PurgeMessages messages() {
        return configProvider.messages().getPurge();
    }

    private @NotNull GeneralMessages general() {
        return configProvider.messages().getGeneral();
    }
}
