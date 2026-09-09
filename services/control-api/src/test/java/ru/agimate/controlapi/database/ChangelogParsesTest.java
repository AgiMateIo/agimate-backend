package ru.agimate.controlapi.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every changelog file is well-formed XML. The master changelog pulls both directories in through
 * {@code includeAll} with {@code recursive: true}, so one unparseable file anywhere takes down the
 * whole changelog and with it the service start — and nothing else in the build looks at these
 * files. A raw {@code <} inside {@code <sql>} (the {@code <>} operator) is the way it happens.
 */
@DisplayName("db/changelog — все файлы разбираются")
class ChangelogParsesTest {

    @Test
    @DisplayName("каждый xml — well-formed, и их больше одного")
    void everyChangelogFileParses() throws Exception {
        List<Path> files;
        URL root = getClass().getClassLoader().getResource("db/changelog");
        if (root == null) {
            fail("db/changelog is not on the test classpath");
            return;
        }
        try (var stream = Files.walk(Path.of(root.toURI()))) {
            files = stream.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no changelog files found");
        // Namespace-aware and non-validating: we check the syntax, not the Liquibase schema — the XSD
        // lives on the network, and a build that reaches for it fails offline for the wrong reason.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                factory.newDocumentBuilder().parse(in);
            } catch (Exception e) {
                fail("changelog does not parse: " + file.getFileName() + " — " + e.getMessage());
            }
        }
        assertTrue(files.size() > 1, "expected the changelog tree, found " + files.size() + " file(s)");
    }
}
