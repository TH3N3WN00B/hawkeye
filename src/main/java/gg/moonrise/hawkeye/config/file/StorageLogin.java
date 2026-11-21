package gg.moonrise.hawkeye.config.file;

import gg.moonrise.hawkeye.util.redact.Redactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The resolved credentials of a database connection ({@link StorageSettings#login}). Hand them to the
 * driver only; {@link #toString()} shows none of them.
 *
 * @param username   the user
 * @param password   the password
 * @param parameters the extra connection parameters
 * @param secrets    the parameter values that came from environment variables
 */
public record StorageLogin(@NotNull String username, @NotNull String password,
        @NotNull Map<String, String> parameters, @NotNull List<String> secrets) {

    /**
     * Copies the collections.
     *
     * @param username   the user
     * @param password   the password
     * @param parameters the parameters
     * @param secrets    the parameter values read from environment variables
     */
    public StorageLogin {
        parameters = Map.copyOf(parameters);
        secrets = List.copyOf(secrets);
    }

    /**
     * What a driver's message must not show: the password and the parameter values from environment
     * variables anywhere, the user name where it stands on its own.
     *
     * @return the redactor
     */
    public @NotNull Redactor redactor() {
        List<String> hidden = new ArrayList<>(secrets);
        hidden.add(password);
        return Redactor.of(hidden, List.of(username));
    }

    @Override
    public @NotNull String toString() {
        return "StorageLogin[" + parameters.size() + " parameters, credentials hidden]";
    }
}
