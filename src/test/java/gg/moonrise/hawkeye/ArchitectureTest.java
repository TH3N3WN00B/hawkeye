package gg.moonrise.hawkeye;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;
import org.junit.jupiter.api.Test;

/**
 * The layered package structure of the java-development skill. Each layer uses only the layers below
 * it; the plugin's main class in the root package may use any of them. Every main class must sit in a
 * layer, so a new top-level package fails here until it is placed in one.
 */
public class ArchitectureTest {

    private static final String ROOT = HawkeyePlugin.class.getPackageName();

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    public void layersOnlyUseTheLayersBelowThem() {
        architecture().check(CLASSES);
    }

    @Test
    public void everyClassSitsInALayer() {
        architecture().ensureAllClassesAreContainedInArchitecture().check(CLASSES);
    }

    private static LayeredArchitecture architecture() {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Plugin").definedBy(ROOT)
                .layer("Loader").definedBy(ROOT + ".loader..")
                .layer("PlayerFacing").definedBy(ROOT + ".command..", ROOT + ".listener..", ROOT + ".ui..",
                        ROOT + ".type..", ROOT + ".indicator..", ROOT + ".integration..")
                .layer("Service").definedBy(ROOT + ".core.service..")
                .layer("Backend").definedBy(ROOT + ".core.repository..", ROOT + ".core.sql..",
                        ROOT + ".core.time..", ROOT + ".currency..")
                .layer("Bottom").definedBy(ROOT + ".entity..", ROOT + ".config..", ROOT + ".util..")
                .whereLayer("Plugin").mayNotBeAccessedByAnyLayer()
                .whereLayer("Loader").mayNotBeAccessedByAnyLayer()
                .whereLayer("PlayerFacing").mayOnlyBeAccessedByLayers("Plugin")
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Plugin", "PlayerFacing")
                .whereLayer("Backend").mayOnlyBeAccessedByLayers("Plugin", "Service")
                .whereLayer("Bottom").mayOnlyBeAccessedByLayers("Plugin", "Loader", "PlayerFacing", "Service",
                        "Backend");
    }
}
