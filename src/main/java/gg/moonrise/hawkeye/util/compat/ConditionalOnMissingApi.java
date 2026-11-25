package gg.moonrise.hawkeye.util.compat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers a component only on a server whose API lacks at least one named class or method: the fallback
 * for the {@link ConditionalOnApi} component with the same names, so exactly one of the two is registered.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ApiCondition.class)
public @interface ConditionalOnMissingApi {

    /**
     * What the component stands in for when the server lacks it.
     *
     * @return the same names as the {@link ConditionalOnApi} component's
     */
    String[] value();
}
