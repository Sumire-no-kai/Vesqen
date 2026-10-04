package io.github.sumirenokai.vesqen.build;

import groovy.json.JsonSlurper;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

final class LicenseCatalog {
    private LicenseCatalog() {}

    static void generate(File catalogDirectory, Map<String, File> artifacts, File fonts,
                         File fontLicenses, File output) throws Exception {
        Map<?, ?> catalog = (Map<?, ?>) new JsonSlurper().parse(new File(catalogDirectory, "catalog.json"), "UTF-8");
        require(Integer.valueOf(1).equals(catalog.get("schemaVersion")), "Unsupported license catalog schema");
        Map<String, Map<?, ?>> reviewed = index(records(catalog, "components"));
        List<Map<?, ?>> selected = new ArrayList<>();
        for (String coordinate : new TreeSet<>(artifacts.keySet())) {
            Map<?, ?> entry = reviewed.get(coordinate);
            require(entry != null, "Unreviewed runtime dependency: " + coordinate);
            require(coordinate.endsWith(":" + string(entry, "version")), "Version mismatch: " + coordinate);
            verifyHash(artifacts.get(coordinate), string(entry, "sha256"));
            requireArchiveDocumentsCited(coordinate, artifacts.get(coordinate), entry);
            selected.add(entry);
        }
        List<Map<?, ?>> fontEntries = records(catalog, "fonts");
        index(fontEntries);
        Set<String> reviewedFontFiles = new TreeSet<>();
        Set<String> reviewedFontLicenses = new TreeSet<>();
        for (Map<?, ?> entry : fontEntries) {
            Map<?, ?> files = (Map<?, ?>) entry.get("files");
            require(files != null && !files.isEmpty(), "Font has no reviewed files");
            for (Map.Entry<?, ?> file : files.entrySet()) {
                String name = (String) file.getKey();
                require(reviewedFontFiles.add(name), "Duplicate reviewed font: " + name);
                verifyHash(child(fonts, name), (String) file.getValue());
            }
            for (Map<?, ?> license : records(entry, "licenses")) reviewedFontLicenses.add(string(license, "file"));
            for (Map<?, ?> notice : records(entry, "notices")) reviewedFontLicenses.add(string(notice, "file"));
        }
        require(reviewedFontFiles.equals(fileNames(fonts)), "Font files differ from reviewed license catalog");
        require(reviewedFontLicenses.equals(fileNames(fontLicenses)), "Font license files differ from reviewed catalog");

        // Build in memory first: validation failure never publishes a partially generated catalog.
        java.io.StringWriter buffer = new java.io.StringWriter();
        XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(buffer);
        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeStartElement("thirdPartyLicenses");
        xml.writeAttribute("schemaVersion", "1");
        for (Map<?, ?> entry : selected) writeComponent(xml, entry, catalogDirectory);
        for (Map<?, ?> entry : fontEntries) writeComponent(xml, entry, fontLicenses);
        xml.writeEndElement();
        xml.writeEndDocument();
        xml.close();
        Files.createDirectories(output.toPath().getParent());
        Files.writeString(output.toPath(), buffer.toString(), StandardCharsets.UTF_8);
    }

    private static void writeComponent(XMLStreamWriter xml, Map<?, ?> entry, File texts) throws Exception {
        xml.writeStartElement("component");
        for (String key : List.of("id", "name", "version")) xml.writeAttribute(key, string(entry, key));
        List<Map<?, ?>> licenses = records(entry, "licenses");
        require(!licenses.isEmpty(), "No license text for " + entry.get("id"));
        for (Map<?, ?> license : licenses) writeDocument(xml, "license", license, texts);
        for (Map<?, ?> notice : records(entry, "notices")) writeDocument(xml, "notice", notice, texts);
        xml.writeEndElement();
    }

    private static void writeDocument(XMLStreamWriter xml, String kind, Map<?, ?> document, File root) throws Exception {
        String text = Files.readString(child(root, string(document, "file")).toPath(), StandardCharsets.UTF_8);
        require(!text.isBlank(), "Empty " + kind + " text");
        xml.writeStartElement(kind);
        if (kind.equals("license")) xml.writeAttribute("name", string(document, "name"));
        xml.writeAttribute("source", string(document, "source"));
        xml.writeCharacters(text);
        xml.writeEndElement();
    }

    /**
     * Every LICENSE, NOTICE or COPYING document a pinned archive ships, including those inside nested
     * jars, must be cited as a source by its record. A dependency bump then cannot keep an old review
     * while a new notice goes unshipped. lint.jar holds Android Lint checks and never reaches the app.
     */
    private static void requireArchiveDocumentsCited(String coordinate, File archive, Map<?, ?> entry) throws Exception {
        Set<String> cited = new HashSet<>();
        for (String key : List.of("licenses", "notices")) {
            for (Map<?, ?> document : records(entry, key)) cited.add(string(document, "source"));
        }
        Set<String> shipped = new TreeSet<>();
        try (var input = Files.newInputStream(archive.toPath())) {
            collectLicenseDocuments(input, "", shipped);
        }
        for (String path : shipped) {
            String source = coordinate + "!/" + path;
            require(cited.contains(source), "Unreviewed license document: " + source);
        }
    }

    private static void collectLicenseDocuments(java.io.InputStream input, String prefix, Set<String> found)
            throws java.io.IOException {
        // Not closed here: a nested stream must not close the archive it is read from.
        java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(input);
        java.util.zip.ZipEntry zipEntry;
        while ((zipEntry = zip.getNextEntry()) != null) {
            if (zipEntry.isDirectory()) continue;
            String name = zipEntry.getName();
            if (name.endsWith(".jar")) {
                if (!(prefix.isEmpty() && name.equals("lint.jar"))) {
                    collectLicenseDocuments(new java.io.ByteArrayInputStream(zip.readAllBytes()), prefix + name + "!/", found);
                }
            } else if (LICENSE_DOCUMENT.matcher(name).find()) {
                found.add(prefix + name);
            }
        }
    }

    private static final java.util.regex.Pattern LICENSE_DOCUMENT =
        java.util.regex.Pattern.compile("(?i)(^|/)(LICENSE|NOTICE|COPYING)[^/]*$");

    private static Map<String, Map<?, ?>> index(List<Map<?, ?>> entries) {
        Map<String, Map<?, ?>> index = new LinkedHashMap<>();
        for (Map<?, ?> entry : entries) {
            String id = string(entry, "id");
            require(index.put(id, entry) == null, "Duplicate license component: " + id);
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> records(Map<?, ?> object, String key) {
        Object value = object.get(key);
        require(value instanceof List<?>, "Missing catalog list: " + key);
        return (List<Map<?, ?>>) value;
    }

    private static String string(Map<?, ?> object, String key) {
        Object value = object.get(key);
        require(value instanceof String && !((String) value).isBlank(), "Missing catalog value: " + key);
        return (String) value;
    }

    private static File child(File directory, String path) throws Exception {
        File file = new File(directory, path).getCanonicalFile();
        require(file.toPath().startsWith(directory.getCanonicalFile().toPath()), "Catalog path escapes its directory");
        return file;
    }

    private static Set<String> fileNames(File directory) throws Exception {
        Set<String> result = new TreeSet<>();
        try (var paths = Files.walk(directory.toPath())) {
            paths.filter(Files::isRegularFile).forEach(path -> result.add(directory.toPath().relativize(path).toString().replace(File.separatorChar, '/')));
        }
        return result;
    }

    private static void verifyHash(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        require(actual.equals(expected), "Artifact changed; review license and NOTICE again: " + file.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
