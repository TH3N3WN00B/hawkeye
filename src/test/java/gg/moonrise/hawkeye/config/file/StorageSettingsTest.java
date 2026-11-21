package gg.moonrise.hawkeye.config.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.util.redact.Redactor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class StorageSettingsTest {

    private static final Environment NONE = name -> Optional.empty();

    private static final Environment SET = name -> Optional.ofNullable(Map.of(
            StorageSettings.USERNAME_VARIABLE, "alex",
            StorageSettings.PASSWORD_VARIABLE, "s3cr3t",
            "SSL_KEY_PASSWORD", "k3y").get(name));

    @Test
    public void defaultsReferenceTheTwoVariables() {
        StorageLogin login = new StorageSettings().login(SET);
        assertEquals("alex", login.username());
        assertEquals("s3cr3t", login.password());
        assertEquals(Map.of(), login.parameters());
    }

    @Test
    public void sqliteNeedsNoVariable() {
        assertEquals(List.of(), new StorageSettings().problems("storage", NONE));
    }

    @Test
    public void aServerEngineWithTheDefaultsAndNoVariableNamesBoth() {
        StorageSettings settings = server("${" + StorageSettings.USERNAME_VARIABLE + "}",
                "${" + StorageSettings.PASSWORD_VARIABLE + "}");
        assertEquals(List.of("storage.username: the environment variable HAWKEYE_DB_USERNAME is not set",
                "storage.password: the environment variable HAWKEYE_DB_PASSWORD is not set"),
                settings.problems("storage", NONE));
        assertEquals(List.of(), settings.problems("storage", SET));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> settings.login(NONE));
        assertTrue(failure.getMessage().contains("HAWKEYE_DB_USERNAME"), failure.getMessage());
    }

    @Test
    public void parametersResolveAndTheirSecretsAreHidden() throws ReflectiveOperationException {
        StorageSettings settings = server("${" + StorageSettings.USERNAME_VARIABLE + "}", "literal-pw");
        parameters(settings, Map.of("sslMode", "verify-full", "keyPassword", "${SSL_KEY_PASSWORD}",
                "other", "${MISSING_ONE}"));
        assertEquals(List.of("network.shards[0].storage.parameters.other: the environment variable MISSING_ONE "
                + "is not set"), settings.problems("network.shards[0].storage", SET));
        parameters(settings, Map.of("sslMode", "verify-full", "keyPassword", "${SSL_KEY_PASSWORD}"));
        StorageLogin login = settings.login(SET);
        assertEquals(Map.of("sslMode", "verify-full", "keyPassword", "k3y"), login.parameters());
        assertEquals(List.of("k3y"), login.secrets());
        assertEquals("literal-pw", login.password());
        Redactor redactor = login.redactor();
        assertEquals("user " + Redactor.MASK + " key " + Redactor.MASK + " pw " + Redactor.MASK + " verify-full",
                redactor.scrub("user alex key k3y pw literal-pw verify-full"));
        assertFalse(login.toString().contains("alex") || login.toString().contains("k3y")
                || login.toString().contains("literal-pw"), login.toString());
    }

    @Test
    public void toStringShowsNoCredential() throws ReflectiveOperationException {
        StorageSettings settings = server("alexander", "hunter2");
        parameters(settings, Map.of("sslMode", "verify-full-secretish"));
        String text = settings.toString();
        assertFalse(text.contains("alexander") || text.contains("hunter2") || text.contains("verify-full-secretish"),
                text);
        assertTrue(text.contains("db.example"), text);
    }

    @Test
    public void literalCredentialsStillWork() {
        StorageLogin login = server("alexander", "hunter2").login(NONE);
        assertEquals("alexander", login.username());
        assertEquals("hunter2", login.password());
        assertEquals(List.of(), server("alexander", "hunter2").problems("storage", NONE));
    }

    @Test
    public void equalityComparesTheRawText() {
        assertEquals(server("${A}", "${B}"), server("${A}", "${B}"));
        assertFalse(server("${A}", "${B}").equals(server("${A}", "${C}")));
    }

    private static StorageSettings server(String username, String password) {
        return StorageSettings.server(StorageType.POSTGRESQL, "db.example", 0, "hawkeye", username, password,
                "hawkeye_");
    }

    private static void parameters(StorageSettings settings, Map<String, String> parameters)
            throws ReflectiveOperationException {
        Field field = StorageSettings.class.getDeclaredField("parameters");
        field.setAccessible(true);
        field.set(settings, parameters);
    }
}
