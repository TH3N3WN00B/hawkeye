package gg.moonrise.hawkeye.util.compat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers a component only on a server whose API has every named class and method, decided once while
 * the plugin loads. Main compiles against the oldest supported Paper API; a component that needs anything
 * newer lives in the {@code newerApi} source set (compiled against the newest API) and carries this
 * annotation, naming {@link NewerApi} constants. Spring reads it from the class file, so on an older server
 * the component's class is never loaded. A fallback for the older servers carries
 * {@link ConditionalOnMissingApi} with the same names.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ApiCondition.class)
public @interface ConditionalOnApi {

    /**
     * What the component needs. Names, not class literals: a fallback compiled against the oldest API names
     * the same classes and cannot refer to them.
     *
     * @return fully qualified class names, or {@code Class#method} for a public method of that name
     */
    String[] value();
}
