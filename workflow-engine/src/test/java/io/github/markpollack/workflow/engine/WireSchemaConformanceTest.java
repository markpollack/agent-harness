package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.markpollack.workflow.spec.CanonicalJson;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corpus-driven conformance for the two wire-projection schemas frozen at Step 2.5:
 * {@code spec/operation-result.schema.json} and
 * {@code spec/events/workflow-event.schema.json}. Every valid fixture validates —
 * and every valid <em>result</em> fixture (including the Python- and TS-emitted ones,
 * the R7 cross-language smoke test) round-trips through {@link OperationResultCodec}
 * with canonical byte equality; every invalid fixture is rejected.
 */
class WireSchemaConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchema RESULT_SCHEMA = load("/spec/operation-result.schema.json");
    private static final JsonSchema EVENT_SCHEMA = load("/spec/events/workflow-event.schema.json");

    @TestFactory
    Stream<DynamicTest> validOperationResultsValidateAndRoundTrip() {
        return fixtures("/spec/operation-results/valid").stream().map(path ->
                DynamicTest.dynamicTest("valid result: " + path.getFileName(), () -> {
                    JsonNode fixture = MAPPER.readTree(Files.readAllBytes(path));
                    assertThat(RESULT_SCHEMA.validate(fixture))
                            .as("schema-valid").isEmpty();

                    OperationResult result = OperationResultCodec.fromJson(fixture);
                    String rewritten = CanonicalJson.canonicalize(
                            MAPPER.writeValueAsString(OperationResultCodec.toJson(result)));
                    String canonical = CanonicalJson.canonicalize(MAPPER.writeValueAsString(fixture));
                    assertThat(rewritten)
                            .as("round-trip law: write(read(j)) canonically equals canonicalize(j)")
                            .isEqualTo(canonical);
                }));
    }

    @TestFactory
    Stream<DynamicTest> invalidOperationResultsAreRejected() {
        return fixtures("/spec/operation-results/invalid").stream().map(path ->
                DynamicTest.dynamicTest("invalid result: " + path.getFileName(), () -> {
                    JsonNode fixture = MAPPER.readTree(Files.readAllBytes(path));
                    Set<ValidationMessage> errors = RESULT_SCHEMA.validate(fixture);
                    assertThat(errors).as("must be schema-rejected").isNotEmpty();
                }));
    }

    @TestFactory
    Stream<DynamicTest> validEventsValidate() {
        return fixtures("/spec/events/fixtures/valid").stream().map(path ->
                DynamicTest.dynamicTest("valid event: " + path.getFileName(), () -> {
                    JsonNode fixture = MAPPER.readTree(Files.readAllBytes(path));
                    assertThat(EVENT_SCHEMA.validate(fixture)).isEmpty();
                }));
    }

    @TestFactory
    Stream<DynamicTest> invalidEventsAreRejected() {
        return fixtures("/spec/events/fixtures/invalid").stream().map(path ->
                DynamicTest.dynamicTest("invalid event: " + path.getFileName(), () -> {
                    JsonNode fixture = MAPPER.readTree(Files.readAllBytes(path));
                    assertThat(EVENT_SCHEMA.validate(fixture)).isNotEmpty();
                }));
    }

    private static JsonSchema load(String resource) {
        InputStream stream = WireSchemaConformanceTest.class.getResourceAsStream(resource);
        assertThat(stream).as("schema resource " + resource).isNotNull();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(stream);
    }

    private static List<Path> fixtures(String resourceDir) {
        var url = WireSchemaConformanceTest.class.getResource(resourceDir);
        assertThat(url).as("fixture dir " + resourceDir).isNotNull();
        try (Stream<Path> entries = Files.list(Paths.get(url.toURI()))) {
            List<Path> fixtures = entries
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            assertThat(fixtures).isNotEmpty();
            return fixtures;
        } catch (Exception e) {
            throw new IllegalStateException("cannot list " + resourceDir, e);
        }
    }
}
