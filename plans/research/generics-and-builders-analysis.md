# Generics and Builder Patterns Analysis

Analysis of patterns from Spring Framework, Spring Boot, Spring Security, Spring Integration, Spring AI, and OpenRewrite.

---

## Part 1: Generics Patterns Catalog

### Pattern 1: Single Type Parameter `<T>`

**Use Case:** Payload/data type, generic handlers, functional interfaces

**Example: GenericHandler (Spring Integration)**
```java
@FunctionalInterface
public interface GenericHandler<P> {
    Object handle(P payload, MessageHeaders headers);
}
```

**Example: GenericSelector**
```java
@FunctionalInterface
public interface GenericSelector<S> {
    boolean accept(S source);
}
```

**When to Use:** Simple generic types where one type parameter represents the main data being processed.

---

### Pattern 2: Dual Type Parameters `<S, T>`

**Use Case:** Transformation, mapping from source to target type

**Example: GenericTransformer (Spring Integration)**
```java
@FunctionalInterface
public interface GenericTransformer<S, T> {
    T transform(S source);
}
```

**Example: HandlerFilterFunction (Spring WebMVC)**
```java
@FunctionalInterface
public interface HandlerFilterFunction<T extends ServerResponse, R extends ServerResponse> {
    R filter(ServerRequest request, HandlerFunction<T> next) throws Exception;
}
```

**When to Use:** When converting between types or when input and output types differ.

---

### Pattern 3: Bounded Type Parameter `<T extends Base>`

**Use Case:** Constrained types that must implement/extend a specific type

**Example: RouterFunction (Spring WebMVC)**
```java
@FunctionalInterface
public interface RouterFunction<T extends ServerResponse> {
    Optional<HandlerFunction<T>> route(ServerRequest request);

    default RouterFunction<T> and(RouterFunction<T> other) {
        return new RouterFunctions.SameComposedRouterFunction<>(this, other);
    }

    default <S extends ServerResponse> RouterFunction<S> filter(
            HandlerFilterFunction<T, S> filterFunction) {
        return new RouterFunctions.FilteredRouterFunction<>(this, filterFunction);
    }
}
```

**When to Use:** When the generic type must have certain capabilities defined by a base class/interface.

---

### Pattern 4: Recursive Type Bound (Self-Referential) `<S extends Class<S, T>>`

**Use Case:** Fluent builders that return "self" type for method chaining

**Example: IntegrationComponentSpec (Spring Integration)**
```java
public abstract class IntegrationComponentSpec<S extends IntegrationComponentSpec<S, T>, T>
        implements FactoryBean<T>, InitializingBean, DisposableBean, SmartLifecycle {

    protected S id(String idToSet) {
        this.id = idToSet;
        return _this();
    }

    @SuppressWarnings("unchecked")
    protected final S _this() {
        return (S) this;
    }

    protected T doGet() {
        throw new UnsupportedOperationException();
    }
}
```

**Example: HeadersBuilder (Spring ResponseEntity)**
```java
public interface HeadersBuilder<B extends HeadersBuilder<B>> {
    B header(String headerName, String... headerValues);
    B headers(@Nullable HttpHeaders headers);
    B headers(Consumer<HttpHeaders> headersConsumer);
    B location(URI location);
    <T> ResponseEntity<T> build();
}

public interface BodyBuilder extends HeadersBuilder<BodyBuilder> {
    BodyBuilder contentLength(long contentLength);
    BodyBuilder contentType(MediaType contentType);
    <T> ResponseEntity<T> body(@Nullable T body);
}
```

**When to Use:** Abstract builders or specs that need fluent method chaining while allowing subclasses to return their own type.

---

### Pattern 5: Multi-Type Parameters with Complex Bounds

**Use Case:** Complex specifications with multiple related types

**Example: EndpointSpec (Spring Integration)**
```java
public abstract class EndpointSpec<S extends EndpointSpec<S, F, H>,
                                   F extends BeanNameAware & FactoryBean<? extends AbstractEndpoint>,
                                   H>
        extends IntegrationComponentSpec<S, Tuple2<F, H>>
        implements ComponentsRegistration {
    // S = self type for fluent API
    // F = factory type (bounded by multiple interfaces)
    // H = handler type
}
```

**When to Use:** Framework-level abstractions where multiple types must work together with specific constraints.

---

### Pattern 6: Observation/Request-Response Context `<REQ, RES>`

**Use Case:** Wrapping request/response pairs in observability or context objects

**Example: ModelObservationContext (Spring AI)**
```java
public class ModelObservationContext<REQ, RES> extends Observation.Context {
    private final REQ request;
    @Nullable
    private RES response;

    public REQ getRequest() { return this.request; }
    public RES getResponse() { return this.response; }
}
```

**When to Use:** When you need to carry request and response types through a processing pipeline.

---

### Summary: When to Use Each Generic Pattern

| Pattern | Use When |
|---------|----------|
| `<T>` | Single data type flows through |
| `<S, T>` | Transforming from source to target |
| `<T extends Base>` | Type must have specific capabilities |
| `<S extends S<S>>` | Fluent builder returning self type |
| `<S, F, H>` complex | Framework abstractions with multiple related types |
| `<REQ, RES>` | Request/response or input/output pairing |

---

## Part 2: Builder Patterns Comparison

### Spring's Manual Builder Convention

**File:** `RegisteredClient.java` (Spring Security OAuth2)

```java
public class RegisteredClient implements Serializable {
    private String id;
    private String clientId;
    private Set<AuthorizationGrantType> authorizationGrantTypes;
    // ... more fields

    protected RegisteredClient() { }  // Protected constructor

    // Static factory methods
    public static Builder withId(String id) {
        Assert.hasText(id, "id cannot be empty");
        return new Builder(id);
    }

    public static Builder from(RegisteredClient registeredClient) {
        Assert.notNull(registeredClient, "registeredClient cannot be null");
        return new Builder(registeredClient);
    }

    public static class Builder {
        private String id;
        private String clientId;
        private final Set<AuthorizationGrantType> authorizationGrantTypes = new HashSet<>();

        protected Builder(String id) {
            this.id = id;
        }

        protected Builder(RegisteredClient registeredClient) {
            this.id = registeredClient.getId();
            this.clientId = registeredClient.getClientId();
            // ... copy all fields
        }

        // Fluent setters
        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        // Add methods for collections
        public Builder authorizationGrantType(AuthorizationGrantType authorizationGrantType) {
            this.authorizationGrantTypes.add(authorizationGrantType);
            return this;
        }

        // Consumer-based modification
        public Builder authorizationGrantTypes(Consumer<Set<AuthorizationGrantType>> consumer) {
            consumer.accept(this.authorizationGrantTypes);
            return this;
        }

        // Build with validation
        public RegisteredClient build() {
            Assert.hasText(this.clientId, "clientId cannot be empty");
            Assert.notEmpty(this.authorizationGrantTypes, "authorizationGrantTypes cannot be empty");
            // ... more validation
            return create();
        }

        private RegisteredClient create() {
            RegisteredClient client = new RegisteredClient();
            client.id = this.id;
            client.clientId = this.clientId;
            client.authorizationGrantTypes = Collections.unmodifiableSet(
                new HashSet<>(this.authorizationGrantTypes));
            return client;
        }
    }
}
```

**Key Conventions:**
1. Protected/private constructor on target class
2. `withId()` or `builder()` static factory methods
3. `from(existing)` for copy construction
4. Fluent setters returning `this`
5. Validation in `build()` method
6. Immutable collections in final object

---

### OpenRewrite's Lombok Builder Pattern

**File:** `Dependency.java` (OpenRewrite Maven)

```java
@Value
@Builder
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@With
public class Dependency implements Serializable, Attributed {
    GroupArtifactVersion gav;

    @Nullable
    String classifier;

    @Nullable
    String type;

    @With
    @Nullable
    String scope;

    @Builder.Default
    @Nullable
    List<GroupArtifact> exclusions = emptyList();
}
```

**Key Annotations:**
- `@Value` - Immutable class (final fields, no setters)
- `@Builder` - Generate builder class
- `@Builder.Default` - Default values for optional fields
- `@With` - Generate `withField()` copy methods
- `@AllArgsConstructor` - Constructor for all fields

**Usage:**
```java
Dependency dep = Dependency.builder()
    .gav(gav)
    .classifier("javadoc")
    .exclusions(emptyList())
    .build();

// Copy with modification
Dependency updated = dep.withScope("runtime");
```

---

### Lombok @SuperBuilder for Inheritance

```java
@SuperBuilder
public class Parent {
    String lastName;
}

@SuperBuilder
public class Child extends Parent {
    String firstName;
}

// Usage - fluent chaining across hierarchy
Child child = Child.builder()
    .firstName("John")  // from Child
    .lastName("Doe")    // from Parent
    .build();
```

---

### Comparison Table

| Aspect | Manual (Spring) | Lombok |
|--------|-----------------|--------|
| **Boilerplate** | ~50-100 lines per class | ~5 annotations |
| **Validation** | Custom in `build()` | Requires custom method |
| **Default Values** | In builder constructor | `@Builder.Default` |
| **Copy Constructor** | `from(existing)` method | `@With` annotations |
| **Inheritance** | Complex, manual | `@SuperBuilder` |
| **IDE Support** | Native | Requires plugin |
| **Compile Dependencies** | None | Lombok dependency |
| **Debugging** | Straightforward | Generated code |
| **Customization** | Full control | Limited to annotations |

---

### Record + Builder Hybrid (Spring AI Pattern)

**File:** `AssistantMessage.java` (Spring AI)

```java
public class AssistantMessage extends AbstractMessage implements MediaContent {
    private final List<ToolCall> toolCalls;
    protected final List<Media> media;

    protected AssistantMessage(@Nullable String content, Map<String, Object> properties,
                               List<ToolCall> toolCalls, List<Media> media) {
        super(MessageType.ASSISTANT, content, properties);
        this.toolCalls = toolCalls;
        this.media = media;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Nested record for supporting type
    public record ToolCall(String id, String type, String name, String arguments) { }

    public static final class Builder {
        private @Nullable String content;
        private Map<String, Object> properties = Map.of();
        private List<ToolCall> toolCalls = List.of();
        private List<Media> media = List.of();

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public AssistantMessage build() {
            return new AssistantMessage(this.content, this.properties,
                                        this.toolCalls, this.media);
        }
    }
}
```

**Pattern Notes:**
- Class contains nested record (`ToolCall`) as supporting type
- Manual builder with immutable defaults (`List.of()`, `Map.of()`)
- Builder is a final inner class

---

## Part 3: Record Organization Patterns

### When to Nest Records vs Separate Files

| Context | Pattern | Example |
|---------|---------|---------|
| **API DTOs** | Bundle many records in one API class | `OpenAiApi.java` (29+ records) |
| **Sealed variants** | Records implement sealed interface | `DockerConnectionConfiguration { record Host(...) }` |
| **Supporting types** | Nested in parent class | `AssistantMessage { record ToolCall(...) }` |
| **Public boundaries** | Single record per file | `ChatClientRequest.java` |
| **Test fixtures** | Local records in test methods | JSON schema validation |

---

### Pattern A: API Class with Many Nested Records

**Use Case:** External API contract definitions (request/response DTOs)

**File:** `OpenAiApi.java` - 29+ nested records in one class

```java
public class OpenAiApi {

    public record ChatCompletionRequest(
        @JsonProperty("messages") List<ChatCompletionMessage> messages,
        @JsonProperty("model") String model,
        @JsonProperty("stream") Boolean stream,
        // ... more fields
    ) {
        public ChatCompletionRequest {
            // Compact constructor validation
        }
    }

    public record ChatCompletionMessage(
        @JsonProperty("role") Role role,
        @JsonProperty("content") Object rawContent,
        // ... more fields
    ) { }

    public record ChatCompletion(
        @JsonProperty("id") String id,
        @JsonProperty("choices") List<Choice> choices,
        // ... more fields
    ) { }

    // 26+ more records...
}
```

**Why Bundle:**
- All records belong to one API contract
- Keeps namespace clean (OpenAiApi.ChatCompletionRequest)
- Easy to find all related types
- Typical for external API wrappers

---

### Pattern B: Nested Record as Supporting Type

**Use Case:** Record is tightly coupled to parent class, used only by parent

**File:** `AssistantMessage.java`

```java
public class AssistantMessage extends AbstractMessage {
    private final List<ToolCall> toolCalls;

    // Record nested inside - only used by AssistantMessage
    public record ToolCall(String id, String type, String name, String arguments) { }
}
```

**File:** `ToolResponseMessage.java`

```java
public class ToolResponseMessage extends AbstractMessage {
    protected final List<ToolResponse> responses;

    public record ToolResponse(String id, String name, String responseData) { }
}
```

**Why Nest:**
- Record is conceptually "owned" by parent class
- Not reused elsewhere
- Keeps related code together

---

### Pattern C: Sealed Interface with Record Variants

**Use Case:** Exhaustive type hierarchies, variant types

**File:** `DockerConnectionConfiguration.java` (Spring Boot)

```java
public sealed interface DockerConnectionConfiguration {

    record Host(String address, boolean secure, @Nullable String certificatePath)
            implements DockerConnectionConfiguration {

        public Host(String address) {
            this(address, false, null);
        }

        public Host {
            Assert.hasLength(address, "'address' must not be empty");
        }
    }

    record Context(String context) implements DockerConnectionConfiguration {
        public Context {
            Assert.hasLength(context, "'context' must not be empty");
        }
    }
}
```

**Why Use Sealed Interface:**
- Compiler ensures exhaustive `switch` statements
- Clear variant types
- Records get automatic `equals()`, `hashCode()`, `toString()`

---

### Pattern D: Separate File for Public API Boundaries

**Use Case:** Core domain types that may evolve independently

**File:** `ChatClientRequest.java`

```java
public record ChatClientRequest(Prompt prompt, Map<String, Object> context) {

    public ChatClientRequest {
        Assert.notNull(prompt, "prompt cannot be null");
        Assert.notNull(context, "context cannot be null");
    }

    public ChatClientRequest copy() {
        return new ChatClientRequest(this.prompt, new HashMap<>(this.context));
    }

    public Builder mutate() {
        return new Builder().prompt(this.prompt).context(new HashMap<>(this.context));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Prompt prompt;
        private Map<String, Object> context = new HashMap<>();

        public Builder prompt(Prompt prompt) { this.prompt = prompt; return this; }
        public Builder context(Map<String, Object> context) { this.context = context; return this; }

        public ChatClientRequest build() {
            return new ChatClientRequest(this.prompt, this.context);
        }
    }
}
```

**Why Separate File:**
- Public API boundary
- May evolve with versioning
- Has its own builder and methods
- Clearly documented

---

### Decision Tree: Nested vs Separate Records

```
Is the record part of an external API contract (DTOs)?
├─ Yes → Bundle in one API class (OpenAiApi pattern)
└─ No
   ├─ Is it a variant of a sealed type?
   │  └─ Yes → Nest inside sealed interface
   └─ No
      ├─ Is it only used by one parent class?
      │  └─ Yes → Nest as inner record
      └─ No
         ├─ Is it a public domain/API boundary?
         │  └─ Yes → Separate file with builder
         └─ No → Consider context and coupling
```

---

## Part 4: Recommendations

### For Your Codebase

#### Generics
1. **Use `<S>` for result types** like your `AgentLoop<S>` - clean, simple pattern
2. **Use recursive bounds** only when building fluent DSLs with inheritance
3. **Use `<REQ, RES>`** for observation/context types that wrap request/response pairs

#### Builders

**Recommended: Lombok @Builder for most cases**
```java
@Builder
public record LoopResult<S>(
    S summary,
    int turns,
    List<Message> messages,
    @Builder.Default List<ToolCall> toolCalls = List.of(),
    Duration duration,
    String terminationReason,
    @Builder.Default Map<String, Object> metadata = Map.of()
) {}
```

**Use Manual Builder when:**
- Complex validation logic in `build()`
- Need `Consumer<Set<T>>` modification patterns
- Building framework-level abstractions

#### Records

1. **Nest records** when they're supporting types for a single parent
2. **Bundle in API classes** for external API DTOs
3. **Separate files** for public domain types with their own lifecycle
4. **Use sealed interfaces** for exhaustive variant types

### Summary: Lombok vs Manual Trade-offs

| Choose Lombok When | Choose Manual When |
|-------------------|-------------------|
| Rapid development | Framework-level code |
| Simple immutable DTOs | Complex validation needs |
| Team is comfortable with it | Strict dependency control |
| Want copy-on-write (`@With`) | Need `Consumer<>` patterns |
| Need `@SuperBuilder` inheritance | Debugging transparency |

---

## Appendix: Key Source Files

### Generics Examples
- `spring-integration/.../dsl/IntegrationComponentSpec.java` - Recursive bounds
- `spring-framework/.../http/ResponseEntity.java` - HeadersBuilder<B>
- `spring-ai/.../model/ModelObservationContext.java` - <REQ, RES>

### Manual Builder Examples
- `spring-security/.../RegisteredClient.java` - Canonical Spring builder
- `spring-integration/.../support/MessageBuilder.java` - Generic builder
- `spring-ai/.../chat/client/ChatClientResponse.java` - Record with builder

### Lombok Builder Examples
- `openrewrite/.../maven/tree/Dependency.java` - @Value @Builder
- `openrewrite/.../gradle/marker/GradleProject.java` - @Builder.Default
- `openrewrite/.../maven/tree/ResolvedDependency.java` - Complex example

### Nested Records Examples
- `spring-ai/.../openai/api/OpenAiApi.java` - 29+ nested records
- `spring-ai/.../chat/messages/AssistantMessage.java` - ToolCall record
- `spring-boot/.../docker/DockerConnectionConfiguration.java` - Sealed interface
