package io.github.sumirenokai.vesqen.build;

import groovy.json.JsonOutput;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.Assert.*;

public class LicenseCatalogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void variantsSelectOnlyTheirOwnRuntimeComponents() throws Exception {
        Fixture fixture = fixture();
        fixture.generate(Map.of("example:one:1", fixture.artifact));
        String first = Files.readString(fixture.output.toPath());
        assertTrue(first.contains("example:one:1"));
        assertFalse(first.contains("example:two:1"));
        assertTrue(first.contains("Copyright &amp; authors\n"));
        assertTrue(first.contains("Full license\n"));
        fixture.generate(Map.of("example:two:1", fixture.artifact));
        String second = Files.readString(fixture.output.toPath());
        assertFalse(second.contains("example:one:1"));
        assertTrue(second.contains("example:two:1"));
    }

    @Test public void addedOrUpgradedDependencyFailsUntilReviewed() throws Exception {
        Fixture fixture = fixture();
        for (String id : List.of("example:new:1", "example:one:2")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.generate(Map.of(id, fixture.artifact)));
            assertTrue(failure.getMessage().contains("Unreviewed runtime dependency"));
        }
        assertFalse(fixture.output.exists());
    }

    @Test public void changedBytesAtSameVersionFail() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.artifact.toPath(), "replacement");
        assertTrue(assertThrows(IllegalStateException.class,
            () -> fixture.generate(Map.of("example:one:1", fixture.artifact)))
            .getMessage().contains("Artifact changed"));
    }

    @Test public void missingOrBlankLicenseFails() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(new File(fixture.catalog, "LICENSE").toPath(), " ");
        assertThrows(IllegalStateException.class, () -> fixture.generate(Map.of("example:one:1", fixture.artifact)));
        Files.delete(new File(fixture.catalog, "LICENSE").toPath());
        assertThrows(java.io.IOException.class, () -> fixture.generate(Map.of("example:one:1", fixture.artifact)));
    }

    @Test public void unreviewedFontOrFontLicenseFails() throws Exception {
        Fixture fixture = fixture();
        File font = new File(fixture.fonts, "new.ttf");
        Files.writeString(font.toPath(), "font");
        assertThrows(IllegalStateException.class, () -> fixture.generate(Map.of("example:one:1", fixture.artifact)));
        Files.delete(font.toPath());
        Files.writeString(new File(fixture.fontLicenses, "new-OFL.txt").toPath(), "license");
        assertThrows(IllegalStateException.class, () -> fixture.generate(Map.of("example:one:1", fixture.artifact)));
    }

    @Test public void duplicateReviewEntryFails() throws Exception {
        Fixture fixture = fixture();
        writeCatalog(fixture, List.of(component(fixture, "example:one:1"), component(fixture, "example:one:1")));
        assertThrows(IllegalStateException.class, () -> fixture.generate(Map.of("example:one:1", fixture.artifact)));
    }

    @Test public void shippedLicenseDocumentsMustBeCited() throws Exception {
        Fixture fixture = fixture();
        writeArchive(fixture.artifact, Map.of(
            "META-INF/NOTICE.txt", "top-level notice",
            "libs/inner.jar", "nested",
            "lint.jar", "ignored"));
        List<String> cited = List.of(
            "example:one:1!/META-INF/NOTICE.txt",
            "example:one:1!/libs/inner.jar!/META-INF/LICENSE");
        writeCatalog(fixture, List.of(component(fixture, "example:one:1", cited)));
        fixture.generate(Map.of("example:one:1", fixture.artifact));

        writeCatalog(fixture, List.of(component(fixture, "example:one:1", cited.subList(0, 1))));
        assertTrue(assertThrows(IllegalStateException.class,
            () -> fixture.generate(Map.of("example:one:1", fixture.artifact)))
            .getMessage().contains("Unreviewed license document: example:one:1!/libs/inner.jar!/META-INF/LICENSE"));
    }

    private void writeArchive(File archive, Map<String, String> entries) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                if (entry.getKey().endsWith(".jar")) {
                    // A nested jar shipping a LICENSE; lint.jar ships one too but is never packaged.
                    var nested = new java.io.ByteArrayOutputStream();
                    try (var inner = new java.util.zip.ZipOutputStream(nested)) {
                        inner.putNextEntry(new java.util.zip.ZipEntry("META-INF/LICENSE"));
                        inner.write(entry.getValue().getBytes());
                    }
                    zip.write(nested.toByteArray());
                } else {
                    zip.write(entry.getValue().getBytes());
                }
            }
        }
    }

    private Fixture fixture() throws Exception {
        Fixture fixture = new Fixture(temporary.newFolder("catalog"), temporary.newFolder("fonts"),
            temporary.newFolder("font-licenses"), temporary.newFile("library.jar"), new File(temporary.getRoot(), "out/catalog.xml"));
        Files.writeString(fixture.artifact.toPath(), "reviewed binary");
        Files.writeString(new File(fixture.catalog, "LICENSE").toPath(), "Full license\n");
        Files.writeString(new File(fixture.catalog, "NOTICE").toPath(), "Copyright & authors\n");
        writeCatalog(fixture, List.of(component(fixture, "example:one:1"), component(fixture, "example:two:1")));
        return fixture;
    }

    private Map<String, Object> component(Fixture fixture, String id) throws Exception {
        return component(fixture, id, List.of());
    }

    private Map<String, Object> component(Fixture fixture, String id, List<String> citedNotices) throws Exception {
        List<Map<String, String>> notices = new ArrayList<>(List.of(Map.of("file", "NOTICE", "source", "upstream/NOTICE")));
        for (String source : citedNotices) notices.add(Map.of("file", "NOTICE", "source", source));
        return Map.of("id", id, "name", id, "version", "1",
            "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture.artifact.toPath()))),
            "licenses", List.of(Map.of("name", "Apache-2.0", "file", "LICENSE", "source", "upstream/LICENSE")),
            "notices", notices);
    }

    private void writeCatalog(Fixture fixture, List<Map<String, Object>> entries) throws Exception {
        Files.writeString(new File(fixture.catalog, "catalog.json").toPath(), JsonOutput.toJson(
            Map.of("schemaVersion", 1, "components", entries, "fonts", List.of())));
    }

    private record Fixture(File catalog, File fonts, File fontLicenses, File artifact, File output) {
        void generate(Map<String, File> artifacts) throws Exception {
            LicenseCatalog.generate(catalog, artifacts, fonts, fontLicenses, output);
        }
    }
}
