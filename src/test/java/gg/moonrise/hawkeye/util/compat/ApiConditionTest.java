package gg.moonrise.hawkeye.util.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import gg.moonrise.hawkeye.HawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.moss.paper.MossPaper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class ApiConditionTest {

    private static final String PRESENT = "org.bukkit.Bukkit";
    private static final String PRESENT_METHOD = "org.bukkit.Bukkit#getServer";
    private static final String MISSING = "org.bukkit.NoSuchApi";
    private static final String MISSING_METHOD = "org.bukkit.Bukkit#noSuchMethod";

    @Test
    public void aComponentIsRegisteredOnlyWhenTheServerHasEverythingItNames() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(OnPresent.class, OnPresentMethod.class, OnPartlyMissing.class, OnMissingMethod.class,
                    FallbackForPresent.class, FallbackForPartlyMissing.class, FallbackForMissingMethod.class);
            context.refresh();

            for (Class<?> registered : List.of(OnPresent.class, OnPresentMethod.class, FallbackForPartlyMissing.class,
                    FallbackForMissingMethod.class)) {
                assertEquals(1, context.getBeanNamesForType(registered).length, registered.getSimpleName());
            }
            for (Class<?> skipped : List.of(OnPartlyMissing.class, OnMissingMethod.class, FallbackForPresent.class)) {
                assertEquals(0, context.getBeanNamesForType(skipped).length, skipped.getSimpleName());
            }
        }
    }

    @Test
    public void everyNewerApiClassIsGatedOnApiTheNewestServerHas() throws IOException {
        List<Class<?>> classes = newerApiClasses();
        assertFalse(classes.isEmpty(), "the newerApi classes are on the test class path");
        for (Class<?> type : classes) {
            ConditionalOnApi gate = type.getAnnotation(ConditionalOnApi.class);
            assertNotNull(gate, type.getName() + " needs @ConditionalOnApi, or it loads on servers without its API");
            assertTrue(ApiCondition.allPresent(gate.value(), getClass().getClassLoader()),
                    type.getName() + " names API the newest server lacks: " + Arrays.toString(gate.value()));
        }
    }

    @Test
    public void everyFallbackStandsInForAGatedComponentAndIsOffOnTheNewestServer() throws IOException {
        Set<Set<String>> gates = newerApiClasses().stream()
                .map(type -> Set.of(type.getAnnotation(ConditionalOnApi.class).value())).collect(Collectors.toSet());
        List<JavaClass> fallbacks = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(HawkeyePlugin.class.getPackageName()).stream()
                .filter(type -> type.isAnnotatedWith(ConditionalOnMissingApi.class)).toList();
        assertFalse(fallbacks.isEmpty(), "main has fallbacks");
        for (JavaClass fallback : fallbacks) {
            String[] names = fallback.reflect().getAnnotation(ConditionalOnMissingApi.class).value();
            assertTrue(gates.contains(Set.of(names)), fallback.getName() + " has no @ConditionalOnApi counterpart");
            assertTrue(ApiCondition.allPresent(names, getClass().getClassLoader()),
                    fallback.getName() + " would also be registered on the newest server");
        }
    }

    @Test
    public void onTheNewestServerThePluginHoldsEveryGatedComponentAndNoFallback() throws IOException {
        MockBukkit.mock();
        try {
            TestPlugins.load();
            for (Class<?> type : newerApiClasses()) {
                assertEquals(1, MossPaper.CONTEXT.getBeanNamesForType(type).length, type.getName());
            }
            assertTrue(MossPaper.CONTEXT.getBeansWithAnnotation(ConditionalOnMissingApi.class).isEmpty());
        } finally {
            MockBukkit.unmock();
        }
    }

    /** The top-level classes of the newerApi source set, from the directories the build passes in. */
    private static @NotNull List<Class<?>> newerApiClasses() throws IOException {
        String dirs = System.getProperty("hawkeye.newerApi.classes");
        assertNotNull(dirs, "the build passes hawkeye.newerApi.classes");
        List<Class<?>> classes = new ArrayList<>();
        for (String dir : dirs.split(File.pathSeparator)) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    String name = root.relativize(file).toString().replace(File.separatorChar, '.');
                    if (!name.contains("$")) {
                        classes.add(load(name.substring(0, name.length() - ".class".length())));
                    }
                }
            }
        }
        return classes;
    }

    private static @NotNull Class<?> load(@NotNull String name) {
        try {
            return Class.forName(name, false, ApiConditionTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }

    @ConditionalOnApi(PRESENT)
    public static final class OnPresent {
    }

    @ConditionalOnApi(PRESENT_METHOD)
    public static final class OnPresentMethod {
    }

    @ConditionalOnApi({PRESENT, MISSING})
    public static final class OnPartlyMissing {
    }

    @ConditionalOnApi(MISSING_METHOD)
    public static final class OnMissingMethod {
    }

    @ConditionalOnMissingApi(PRESENT)
    public static final class FallbackForPresent {
    }

    @ConditionalOnMissingApi({PRESENT, MISSING})
    public static final class FallbackForPartlyMissing {
    }

    @ConditionalOnMissingApi(MISSING_METHOD)
    public static final class FallbackForMissingMethod {
    }
}
