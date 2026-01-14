# Testing Conventions

This document describes the testing conventions and patterns used in spring-ai-agent-harnesses.

## Test Types

### Unit Tests (*Test.java)

Unit tests are fast, isolated tests that don't require external resources. They run with the Maven Surefire plugin during the `test` phase.

```bash
mvn test
```

**Naming convention**: `*Test.java` (e.g., `MiniAgentConfigTest.java`)

**Characteristics**:
- No external API calls
- No network dependencies
- Use mocks/stubs for dependencies (e.g., `DeterministicChatModel`)
- Fast execution (< 100ms per test)

### Integration Tests (*IT.java)

Integration tests verify end-to-end behavior with real external services. They run with the Maven Failsafe plugin during the `verify` phase.

```bash
mvn verify -pl harness-examples
```

**Naming convention**: `*IT.java` (e.g., `MiniAgentIT.java`)

**Characteristics**:
- May call real LLM APIs (Anthropic, OpenAI, etc.)
- May require environment variables (e.g., `ANTHROPIC_API_KEY`)
- Slower execution (seconds per test)
- Should be skipped gracefully if dependencies unavailable

## Maven Plugin Configuration

### Surefire (Unit Tests)

Configured in parent `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.2</version>
    <configuration>
        <argLine>--enable-preview</argLine>
    </configuration>
</plugin>
```

### Failsafe (Integration Tests)

Configured in parent `pom.xml` pluginManagement:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.5.2</version>
    <configuration>
        <argLine>--enable-preview</argLine>
        <includes>
            <include>**/*IT.java</include>
        </includes>
    </configuration>
</plugin>
```

Modules with integration tests add in their `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <executions>
                <execution>
                    <goals>
                        <goal>integration-test</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## Environment Variables for IT Tests

Integration tests that require API keys should use JUnit 5's `@EnabledIfEnvironmentVariable`:

```java
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class MiniAgentIT {
    // tests skipped if ANTHROPIC_API_KEY not set
}
```

## Test Patterns

### DeterministicChatModel

For unit testing agent behavior without real LLM calls:

```java
List<String> responses = List.of(
    "I'll check the files",
    "Found 3 files"
);
DeterministicChatModel model = new DeterministicChatModel(responses);
ChatClient chatClient = ChatClient.builder(model).build();
```

### @TempDir for File Operations

Use JUnit 5's `@TempDir` for tests that create/modify files:

```java
@TempDir
Path tempDir;

@BeforeEach
void setUp() {
    config = MiniAgentConfig.builder()
        .workingDirectory(tempDir)
        .build();
}
```

## Running Tests

```bash
# Unit tests only
mvn test

# Unit + Integration tests
mvn verify

# Specific module
mvn verify -pl harness-examples

# With API key for integration tests
export ANTHROPIC_API_KEY=sk-... && mvn verify
```
