package gg.moonrise.hawkeye.util.compat;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/**
 * Evaluates {@link ConditionalOnApi} and {@link ConditionalOnMissingApi} against what the plugin's class
 * loader sees (the server's API). Spring runs it while it scans the plugin, before any component is created,
 * and reads the annotations from the class file, so the gated class itself is not loaded.
 */
public final class ApiCondition implements Condition {

    @Override
    public boolean matches(@NotNull ConditionContext context, @NotNull AnnotatedTypeMetadata metadata) {
        ClassLoader loader = context.getClassLoader();
        boolean needed = names(metadata, ConditionalOnApi.class).map(names -> allPresent(names, loader)).orElse(true);
        boolean fallback = names(metadata, ConditionalOnMissingApi.class).map(names -> !allPresent(names, loader))
                .orElse(true);
        return needed && fallback;
    }

    /**
     * Whether a class loader sees every named class and method. Loading a class does not initialize it.
     *
     * @param names  fully qualified class names, or {@code Class#method} for a public method of that name
     * @param loader the class loader, or {@code null} for Spring's default
     * @return {@code true} when every class and method exists
     */
    public static boolean allPresent(@NotNull String @NotNull [] names, @Nullable ClassLoader loader) {
        return Arrays.stream(names).allMatch(name -> present(name, loader));
    }

    private static boolean present(@NotNull String name, @Nullable ClassLoader loader) {
        int hash = name.indexOf('#');
        if (hash < 0) {
            return ClassUtils.isPresent(name, loader);
        }
        String type = name.substring(0, hash);
        String method = name.substring(hash + 1);
        if (!ClassUtils.isPresent(type, loader)) {
            return false;
        }
        Class<?> owner = ClassUtils.resolveClassName(type, loader);
        return Arrays.stream(owner.getMethods()).anyMatch(candidate -> candidate.getName().equals(method));
    }

    private static @NotNull Optional<String[]> names(@NotNull AnnotatedTypeMetadata metadata,
            @NotNull Class<? extends Annotation> annotation) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(annotation.getName());
        return Optional.ofNullable(attributes).map(found -> (String[]) found.get("value"));
    }
}
