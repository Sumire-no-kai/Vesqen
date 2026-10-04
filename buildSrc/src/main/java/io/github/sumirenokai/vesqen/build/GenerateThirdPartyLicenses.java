package io.github.sumirenokai.vesqen.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves no network metadata: every selected artifact must have a reviewed license record. */
@CacheableTask
public abstract class GenerateThirdPartyLicenses extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getRuntimeArtifacts();

    @Input
    public abstract MapProperty<String, String> getArtifactNamesByCoordinate();

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getCatalogDirectory();

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getFontDirectory();

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getFontLicenseDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void generate() throws Exception {
        Map<String, File> byName = new LinkedHashMap<>();
        for (File file : getRuntimeArtifacts()) {
            File previous = byName.put(file.getName(), file);
            if (previous != null && !previous.equals(file)) {
                throw new IllegalStateException("Ambiguous runtime artifact filename: " + file.getName());
            }
        }
        Map<String, File> artifacts = new LinkedHashMap<>();
        getArtifactNamesByCoordinate().get().forEach((coordinate, name) -> {
            File file = byName.get(name);
            if (file == null) throw new IllegalStateException("Missing runtime artifact: " + coordinate);
            artifacts.put(coordinate, file);
        });
        if (!artifacts.values().containsAll(byName.values())) {
            throw new IllegalStateException("A runtime artifact has no component coordinate");
        }
        LicenseCatalog.generate(
            getCatalogDirectory().get().getAsFile(), artifacts,
            getFontDirectory().get().getAsFile(), getFontLicenseDirectory().get().getAsFile(),
            getOutputDirectory().file("licenses/third-party.xml").get().getAsFile()
        );
    }
}
