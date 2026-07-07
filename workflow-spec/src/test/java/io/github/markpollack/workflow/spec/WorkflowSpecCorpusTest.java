package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Corpus-driven conformance (DD-9): every fixture in {@code spec/fixtures/valid} passes
 * both validation phases (and satisfies the round-trip law); every fixture in
 * {@code spec/fixtures/invalid} is rejected with exactly the codes its
 * {@code <name>.expected.json} sidecar pins. This is the same corpus the Python and
 * TypeScript SDK suites run against.
 */
class WorkflowSpecCorpusTest {

    private final WorkflowSpecReader reader = new DefaultWorkflowSpecReader();
    private final WorkflowSpecWriter writer = new DefaultWorkflowSpecWriter();
    private final ObjectMapper sidecarMapper = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> validFixturesPassBothPhasesAndRoundTrip() throws Exception {
        List<Path> fixtures = list("/spec/fixtures/valid");
        assertThat(fixtures).as("valid corpus present").isNotEmpty();
        return fixtures.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            byte[] raw = Files.readAllBytes(path);
            WorkflowSpec spec = reader.read(new java.io.ByteArrayInputStream(raw));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(spec, out);
            assertThat(out.toByteArray())
                    .as("write(read(j)) == canonicalize(j) for %s", path.getFileName())
                    .isEqualTo(CanonicalJson.canonicalize(raw));
        }));
    }

    @TestFactory
    Stream<DynamicTest> invalidFixturesFailWithPinnedCodes() throws Exception {
        List<Path> fixtures = list("/spec/fixtures/invalid").stream()
                .filter(p -> !p.getFileName().toString().endsWith(".expected.json"))
                .toList();
        assertThat(fixtures).as("invalid corpus present").isNotEmpty();
        return fixtures.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            Path sidecar = path.resolveSibling(
                    path.getFileName().toString().replace(".json", ".expected.json"));
            assertThat(sidecar).as("sidecar for %s", path.getFileName()).exists();
            JsonNode expected = sidecarMapper.readTree(Files.readAllBytes(sidecar));

            var thrown = assertThatExceptionOfType(WorkflowSpecValidationException.class)
                    .isThrownBy(() -> {
                        try (InputStream in = Files.newInputStream(path)) {
                            reader.read(in);
                        }
                    });

            Set<String> reported = new LinkedHashSet<>();
            thrown.actual().errors().forEach(e -> reported.add(e.code()));

            if (expected.has("errorCodes")) {
                Set<String> pinned = new LinkedHashSet<>();
                expected.get("errorCodes").forEach(n -> pinned.add(n.asText()));
                assertThat(reported)
                        .as("distinct reported codes must equal the pinned set for %s", path.getFileName())
                        .containsExactlyInAnyOrderElementsOf(pinned);
            } else if (expected.has("anyOfCodes")) {
                Set<String> allowed = new LinkedHashSet<>();
                expected.get("anyOfCodes").forEach(n -> allowed.add(n.asText()));
                assertThat(reported)
                        .as("reported codes must be non-empty and drawn from %s for %s",
                                allowed, path.getFileName())
                        .isNotEmpty()
                        .isSubsetOf(allowed);
            } else {
                throw new AssertionError("sidecar must define errorCodes or anyOfCodes: " + sidecar);
            }
        }));
    }

    private List<Path> list(String resourceDir) throws IOException, URISyntaxException {
        var url = getClass().getResource(resourceDir);
        assertThat(url).as("corpus directory %s on test classpath", resourceDir).isNotNull();
        try (Stream<Path> entries = Files.list(Paths.get(url.toURI()))) {
            List<Path> files = new ArrayList<>(entries
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList());
            return files;
        }
    }
}
