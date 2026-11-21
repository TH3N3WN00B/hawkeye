package gg.moonrise.hawkeye.config.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class EnvReferencesTest {

    private static final Environment ENVIRONMENT = name -> Optional.ofNullable(Map.of(
            "DB_USER", "alex",
            "DB_PASSWORD", "s3cr3t",
            "EMPTY", "",
            "LOOP", "${DB_PASSWORD}",
            "DOLLAR", "a$1\\b").get(name));

    @Test
    public void literalTextIsKept() {
        assertEquals(new EnvValue.Resolved("hawkeye", false), EnvReferences.resolve("hawkeye", ENVIRONMENT));
        assertEquals(new EnvValue.Resolved("", false), EnvReferences.resolve("", ENVIRONMENT));
    }

    @Test
    public void oneReferenceIsReplaced() {
        assertEquals(new EnvValue.Resolved("s3cr3t", true), EnvReferences.resolve("${DB_PASSWORD}", ENVIRONMENT));
    }

    @Test
    public void severalAndEmbeddedReferencesAreReplaced() {
        assertEquals(new EnvValue.Resolved("redis://alex:s3cr3t@localhost:6379/alex", true),
                EnvReferences.resolve("redis://${DB_USER}:${DB_PASSWORD}@localhost:6379/${DB_USER}", ENVIRONMENT));
    }

    @Test
    public void unsetVariablesAreListedByNameOnce() {
        assertEquals(new EnvValue.Unset(List.of("NOPE", "OTHER")),
                EnvReferences.resolve("${NOPE}:${DB_PASSWORD}:${OTHER}:${NOPE}", ENVIRONMENT));
        assertEquals(List.of("NOPE"), EnvReferences.unset("x${NOPE}", ENVIRONMENT));
        assertEquals(List.of(), EnvReferences.unset("${DB_USER}", ENVIRONMENT));
    }

    @Test
    public void anEmptyVariableIsAValue() {
        assertEquals(new EnvValue.Resolved("", true), EnvReferences.resolve("${EMPTY}", ENVIRONMENT));
    }

    @Test
    public void malformedReferencesStayLiteral() {
        for (String text : List.of("${", "${DB_USER", "$DB_USER", "${1DB}", "${DB-USER}", "${}", "$ {DB_USER}")) {
            assertEquals(new EnvValue.Resolved(text, false), EnvReferences.resolve(text, ENVIRONMENT), text);
        }
        assertEquals(new EnvValue.Resolved("${alex", true), EnvReferences.resolve("${${DB_USER}", ENVIRONMENT));
    }

    @Test
    public void aSubstitutedValueIsNotExpandedAgain() {
        assertEquals(new EnvValue.Resolved("${DB_PASSWORD}", true), EnvReferences.resolve("${LOOP}", ENVIRONMENT));
        assertEquals(new EnvValue.Resolved("a$1\\b", true), EnvReferences.resolve("${DOLLAR}", ENVIRONMENT),
                "replacement characters are taken literally");
    }

    @Test
    public void requireNamesTheVariableNeverAValue() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> EnvReferences.require("${DB_PASSWORD}${NOPE}", ENVIRONMENT, "storage.password"));
        assertEquals("storage.password: the environment variable NOPE is not set", failure.getMessage());
        assertEquals("s3cr3t", EnvReferences.require("${DB_PASSWORD}", ENVIRONMENT, "storage.password").value());
    }

    @Test
    public void toStringHidesTheValue() {
        EnvValue resolved = EnvReferences.resolve("${DB_PASSWORD}", ENVIRONMENT);
        assertFalse(resolved.toString().contains("s3cr3t"), resolved.toString());
        assertTrue(resolved.toString().contains("substituted=true"), resolved.toString());
    }
}
