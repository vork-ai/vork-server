# Vork — Database Layer Agent Reference

## Overview

Vork provides a generic, type-safe `DatabaseRepository<T>` backed by MongoDB.
Entities are plain **Java records** that implement `DatabaseEntity`. Serialisation
is handled automatically via Jackson — no annotations are required on the records.

---

## CLEAN-SLATE PROTOCOL

Backward compatibility is explicitly forbidden. Do not introduce fallback overloads, compatibility layers, or deprecated methods to keep old call sites compiling. Rewrite all affected upstream and downstream components, configurations, and test classes to natively support the new schema directly.

---

## Core Contracts

### `DatabaseEntity`
```java
public interface DatabaseEntity {
    String uuid();
}
```
Every entity record **must** implement this interface.  
`uuid()` is used as the MongoDB `_id` and as the lookup key.

### `DatabaseRepository<T extends DatabaseEntity>`
```java
T      get(String uuid);                   // null if not found
void   save(T entity);                     // create or update (upsert by uuid)
void   delete(String uuid);                // no-op if not found
Stream<T> list(int page, int pageSize);    // lazy; MUST be closed — see below
long   count();
```

---

## Declaring a New Entity

### Step 1 — Define the record

```java
public record Product(
    String uuid,           // required — maps to DatabaseEntity.uuid()
    String name,
    BigDecimal price,
    List<String> tags
) implements DatabaseEntity {}
```

**Rules:**
- The record **must** declare a field named exactly `uuid` of type `String`.
- All field types must be Jackson-serialisable: primitives, wrappers, `String`,
  nested records, `List<T>`, `Map<String, V>`, etc.
- Nested records (e.g. `AddressRecord`) do **not** need to implement `DatabaseEntity`.
- `null` field values are preserved through the serialisation round-trip.

### Step 2 — Register a Spring bean

```java
@Configuration
public class RepositoryConfig {

    @Bean
    public DatabaseRepository<Product> productRepository(DatabaseRepositoryFactory factory) {
        return factory.create(Product.class);
    }
}
```

### Step 3 — Inject and use

```java
@Service
public class ProductService {

    @Autowired
    private DatabaseRepository<Product> productRepository;

    public void add(Product p) {
        productRepository.save(p);
    }

    public Product find(String uuid) {
        return productRepository.get(uuid);   // returns null if absent
    }

    public List<Product> firstPage() {
        try (Stream<Product> stream = productRepository.list(0, 20)) {
            return stream.toList();
        }
    }
}
```

---

## Stream Usage — Important

`list()` returns a **lazily-loaded** `Stream` backed by a live MongoDB cursor.
Always close it to release the cursor:

```java
// Preferred: try-with-resources
try (Stream<Product> page = repo.list(0, 50)) {
    page.forEach(this::process);
}

// Also fine: collect then close
List<Product> items;
try (Stream<Product> page = repo.list(0, 50)) {
    items = page.toList();
}
```

Never store the stream itself and use it outside the try block — the cursor
will have been closed.

---

## MongoDB Collection Naming

Collection names are derived automatically from the record's simple class name
via `CamelCase → snake_case`:

| Record class    | MongoDB collection |
|-----------------|--------------------|
| `Product`       | `product`          |
| `ProductEntity` | `product_entity`   |
| `MyType`        | `my_type`          |

---

## Configuration

Connection properties live in `conf.d/database.properties` relative to the
working directory at startup (typically the project root):

```properties
mongo.host=localhost
mongo.port=27017
mongo.database=vork

# Optional — remove comment hashes to enable authentication
# mongo.username=admin
# mongo.password=secret
```

If the file is absent, the defaults (`localhost:27017`, database `vork`,
no authentication) are used.

---

## Testing with `MapDatabaseRepository`

`MapDatabaseRepository<T>` is an in-memory mock that stores entities as JSON
strings in a `LinkedHashMap`. No Spring context or MongoDB instance is needed.

```java
// Setup
DatabaseRepository<Product> db = new MapDatabaseRepository<>(Product.class);

// Use exactly the same API as the real repo
Product p = new Product(UUID.randomUUID().toString(), "Widget", BigDecimal.ONE, List.of("sale"));
db.save(p);

Product loaded = db.get(p.uuid());
assertEquals("Widget", loaded.name());

// Assert on the raw JSON if needed
Map<String, String> rawStore = ((MapDatabaseRepository<Product>) db).getJsonStore();
assertTrue(rawStore.get(p.uuid()).contains("Widget"));
```

---

## Module Layout

```
src/main/java/com/vork/
├── VorkApplication.java
└── database/
    ├── DatabaseEntity.java             ← base interface
    ├── DatabaseRepository.java         ← generic repository interface
    ├── DatabaseException.java          ← runtime exception
    ├── DatabaseRepositoryFactory.java  ← Spring bean; creates typed repos
    └── mongo/
        ├── MongoConfig.java            ← loads conf.d/database.properties
        └── MongoDBRepository.java      ← MongoDB implementation

src/test/java/com/vork/
└── database/
    ├── mock/
    │   └── MapDatabaseRepository.java  ← in-memory JSON mock for tests
    ├── entities/                       ← example entity records
    │   ├── SimpleEntity.java           ← primitives: String, int, boolean, double
    │   ├── AddressRecord.java          ← nested value-object
    │   ├── PersonEntity.java           ← nested record + List<String>
    │   ├── TaggedEntity.java           ← List<String> + Map<String,String>
    │   ├── ContactInfo.java            ← nested value-object (for ProductEntity)
    │   ├── DimensionsRecord.java       ← nested value-object (for ProductEntity)
    │   └── ProductEntity.java          ← deep nesting + List<Record> + long
    └── DatabaseRepositoryTest.java     ← unit tests (no Spring context needed)
```

---

## Serialisation Notes

- Jackson serialises each record to JSON using its canonical constructor parameter names.
- The JSON `uuid` field is stored both in the MongoDB document body **and** as `_id`
  (for efficient server-side lookups).  On read, `_id` is stripped and Jackson uses
  the `uuid` field in the document body to reconstruct the record.
- Nested `Document` objects from the MongoDB driver serialise as plain JSON objects;
  Jackson can handle them transparently.
- `ObjectMapper` is configured with `findAndRegisterModules()` to pick up
  `jackson-module-parameter-names` and any other modules on the classpath.

---

## Paging Notes

`list(page, pageSize)` uses MongoDB's `skip()` + `limit()`.  
`skip()` scans over documents server-side, so it can be slow on very large collections.
For high-volume scenarios, consider keyset / cursor-based pagination instead and
extend the `DatabaseRepository` interface accordingly.

---

---

# Vork — AI Service Reference

## Overview

The AI layer provides a dynamically-routed `AiOrchestrationService` backed by
Spring AI `ChatClient`. The active provider is selected at call-time via an
`AiProvider` enum value. Only **Gemini** is wired today; adding further providers
requires no changes to the service or controller.

Spring AI version: **1.1.0-SNAPSHOT** (from `https://repo.spring.io/snapshot`).  
Artifact: `spring-ai-starter-model-google-genai` — this is the direct Google AI
Studio Gemini API starter (not Vertex AI).

---

## Core Classes

| Class | Package | Role |
|---|---|---|
| `AiProvider` | `com.vork.ai` | Enum of supported providers (`GEMINI`, `OPENAI`, `ANTHROPIC`) |
| `AiConfig` | `com.vork.ai.config` | Declares `ChatClient` beans, the provider registry map, and tool beans |
| `AiOrchestrationService` | `com.vork.ai.service` | Routes `generate(prompt, provider)` to the correct `ChatClient` |
| `AiController` | `com.vork.ai.controller` | `GET /ai/generate?prompt=…&provider=GEMINI` |
| `WeatherRequest` | `com.vork.ai.function` | Input record for the `getCurrentWeather` tool |

---

## Calling the Service

```java
@Autowired
AiOrchestrationService aiService;

String reply = aiService.generate("What is the capital of France?", AiProvider.GEMINI);
```

REST equivalent:
```
GET /ai/generate?prompt=What+is+the+capital+of+France%3F
GET /ai/generate?prompt=What+is+the+capital+of+France%3F&provider=GEMINI
```

`provider` defaults to `GEMINI` when omitted.

---

## Adding a New Provider

**1. Add the enum entry** in `AiProvider`:
```java
public enum AiProvider { GEMINI, OPENAI, ANTHROPIC }
```

**2. Add the Maven dependency** in `pom.xml` (version managed by the Spring AI BOM):
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

**3. Add API key** in `application.yml`:
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        options:
          model: gpt-4o
```

**4. Declare the `ChatClient` bean** in `AiConfig`, injecting the provider's
auto-configured `ChatModel` directly (avoids `ChatClient.Builder` ambiguity when
multiple providers are present):
```java
@Bean
public ChatClient openAiChatClient(OpenAiChatModel model, ToolCallback getCurrentWeather) {
    return ChatClient.builder(model)
            .defaultToolCallbacks(getCurrentWeather)
            .build();
}
```

**5. Register in the provider map** — the only other change needed:
```java
@Bean
public Map<AiProvider, ChatClient> chatClientRegistry(
        @Qualifier("geminiChatClient")  ChatClient geminiChatClient,
        @Qualifier("openAiChatClient")  ChatClient openAiChatClient) {
    return Map.of(
            AiProvider.GEMINI,  geminiChatClient,
            AiProvider.OPENAI,  openAiChatClient
    );
}
```

`AiOrchestrationService` and `AiController` require **no changes**.

---

## The `mutate()` Pattern

`AiOrchestrationService.generate()` calls `base.mutate().build()` before each
request rather than using the shared `ChatClient` directly:

```java
return base.mutate()   // copies shared config into a new Builder
        .build()       // creates a fresh ChatClient for this request
        .prompt()
        .user(userPrompt)
        .call()
        .content();
```

This means:
- The shared `ChatClient` bean is never mutated between concurrent calls.
- Per-request overrides (extra tools, system-prompt, options) can be applied to
  the mutated builder without leaking to other in-flight requests.

---

## Adding a New Tool

**1. Define an input record** with Jackson schema annotations:
```java
public record StockRequest(
        @JsonProperty(required = true, value = "ticker")
        @JsonPropertyDescription("Stock ticker symbol, e.g. AAPL")
        String ticker
) {}
```

**2. Register a `ToolCallback` bean** in `AiConfig`:
```java
@Bean
public ToolCallback getStockPrice() {
    return FunctionToolCallback
            .builder("getStockPrice",
                    (StockRequest req) -> "Price of " + req.ticker() + ": $123.45 (stub)")
            .description("Get the current stock price for a given ticker symbol.")
            .inputType(StockRequest.class)
            .build();
}
```

**3. Attach to the relevant `ChatClient` bean(s)**:
```java
@Bean
public ChatClient geminiChatClient(ChatClient.Builder chatClientBuilder,
                                   ToolCallback getCurrentWeather,
                                   ToolCallback getStockPrice) {
    return chatClientBuilder
            .defaultToolCallbacks(getCurrentWeather, getStockPrice)
            .build();
}
```

The model will invoke the tool automatically whenever it decides the tool is
relevant to the user's prompt.

---

## Configuration

API keys are resolved from environment variables via `application.yml`:

```yaml
spring:
  ai:
    google:
      genai:
        api-key: ${GEMINI_API_KEY:your-api-key-here}
        chat:
          model: gemini-2.0-flash
```

Set `GEMINI_API_KEY` in the environment (or `.env` / IDE run config) before
starting the application. Get a key at https://aistudio.google.com/app/apikey.

---

## Module Layout

```
src/main/java/com/vork/
└── ai/
    ├── AiProvider.java                 ← enum: GEMINI, OPENAI, ANTHROPIC
    ├── config/
    │   └── AiConfig.java               ← ChatClient beans, tool beans, registry map
    ├── controller/
    │   └── AiController.java           ← GET /ai/generate
    ├── function/
    │   └── WeatherRequest.java         ← input record for getCurrentWeather tool
    └── service/
        └── AiOrchestrationService.java ← generate(prompt, provider)
```

---

---

# Vork — Chat / WebSocket Service Reference

## Overview

The chat layer adds persistent, session-scoped conversation history on top of the
AI orchestration service. Each browser HTTP session maps to one `AiSession` stored
in MongoDB. Messages are delivered in real-time over WebSocket (STOMP).

---

## Entities

### `AiChatMessage` (embedded — not a `DatabaseEntity`)
```java
public record AiChatMessage(
    String uuid,       // random UUID per message
    String role,       // "USER" | "ASSISTANT" | "ERROR"
    String content,
    long   timestamp   // epoch milliseconds (System.currentTimeMillis())
) {}
```
Stored embedded in `AiSession.messages`; never persisted independently.

### `AiSession` (implements `DatabaseEntity`)
```java
public record AiSession(
    String uuid,                    // HTTP session ID — used as MongoDB _id
    String provider,                // e.g. "GEMINI"
    long   createdAt,               // epoch milliseconds
    List<AiChatMessage> messages    // full conversation history
) implements DatabaseEntity {}
```
Stored in MongoDB collection `ai_session`.

---

## WebSocket Configuration

| Setting | Value |
|---|---|
| STOMP endpoint | `/ws` (SockJS enabled) |
| Application destination prefix | `/app` |
| Broker destination prefix | `/topic` |

Configured in `WebSocketConfig.java` (`@EnableWebSocketMessageBroker`).

---

## HTTP Endpoint — Session Init

```
GET /api/chat/session?provider=GEMINI
```

Returns `SessionResponse { sessionUuid, messages[] }`.  
Creates a new `AiSession` keyed by the HTTP session ID, or resumes the existing
one. Call this once on page load before connecting the WebSocket.

---

## WebSocket Endpoint — Send a Message

**Publish** to: `/app/chat.send`  
**Subscribe** to: `/topic/chat/{sessionUuid}`

Message payload (JSON):
```json
{ "sessionUuid": "...", "content": "Hello!", "provider": "GEMINI" }
```

The server broadcasts the AI reply as a serialised `AiChatMessage` to the
session's topic. The user message is **not** echoed back — render it client-side
immediately on submit.

### Example (StompJS)
```js
// Subscribe
stomp.subscribe('/topic/chat/' + sessionUuid, function (frame) {
    const msg = JSON.parse(frame.body);   // AiChatMessage
    renderMessage(msg);
});

// Publish
stomp.publish({
    destination: '/app/chat.send',
    body: JSON.stringify({ sessionUuid, content, provider })
});
```

---

## Service — `ChatService`

```java
AiSession getOrCreateSession(String httpSessionId, String provider);
AiChatMessage sendMessage(String sessionUuid, String content, String provider);
```

`sendMessage`:
1. Loads the `AiSession` from MongoDB.
2. Converts stored messages to Spring AI `Message` objects.
3. Calls `AiOrchestrationService.generateWithHistory(history, newUserMessage, provider)`.
4. Persists both the user message and the AI reply before returning.
5. Returns the AI `AiChatMessage`.

---

## Module Layout additions

```
src/main/java/com/vork/ai/
├── entity/
│   ├── AiChatMessage.java              ← embedded message record
│   └── AiSession.java                  ← DatabaseEntity; collection: ai_session
├── config/
│   ├── AiRepositoryConfig.java         ← @Bean DatabaseRepository<AiSession>
│   └── WebSocketConfig.java            ← STOMP / SockJS configuration
├── controller/
│   └── ChatController.java             ← GET /api/chat/session  +  @MessageMapping /chat.send
└── service/
    └── ChatService.java                ← session lifecycle + message persistence
```

---

---

# Vork — TypeGen Service Reference

## Overview

The TypeGen layer provides runtime Java compilation and persistence. Source code
supplied as a string is compiled in-memory by `javax.tools.JavaCompiler`, the
resulting bytecode is stored in MongoDB as a `JavaType` entity, and the class is
loaded into the running JVM. Previously saved types are automatically resolvable
as compile-time dependencies on subsequent compilations and after restarts.

A Spring AI `compileJavaType` tool wires the whole stack to the Gemini chat
interface so the AI can generate and persist new types on demand.

---

## Entities

### `JavaType` (implements `DatabaseEntity`)
```java
public record JavaType(
    String uuid,                  // FQN of primary type — used as MongoDB _id
    String source,                // original Java source
    Map<String, String> bytecode, // fqn → Base64-encoded bytes (outer + inners)
    long createdAt,
    long updatedAt
) implements DatabaseEntity {}
```
MongoDB collection: `java_type`.

---

## Core Classes

| Class | Package | Role |
|---|---|---|
| `JavaType` | `com.vork.typegen` | Persisted entity for a compiled type |
| `TypeGeneratorConfig` | `com.vork.typegen` | `@Bean DatabaseRepository<JavaType>` |
| `JavaTypeClassLoader` | `com.vork.typegen` | App-scoped ClassLoader backed by DB |
| `TypeGeneratorService` | `com.vork.typegen` | Compile, load, persist |
| `CompileTypeRequest` | `com.vork.ai.function` | Input record for `compileJavaType` tool |

---

## `TypeGeneratorService` API

```java
Class<?> compile(String source);
Class<?> compile(String source, Map<String, byte[]> dependencies);
Class<?> compileAndSave(String source);   // compile + persist + register
Class<?> get(String fqn);                 // null if not compiled this session
byte[]   getBytecode(String fqn);         // null if not compiled this session
Map<String, byte[]> getAllBytecode();      // all bytecode compiled this session
```

`compile()` auto-enriches the compiler's dependency classpath from DB when
`javaTypeRepository` is wired — previously saved types are resolvable without
explicit `dependencies` argument.

`compileAndSave()` requires `JavaTypeClassLoader` and `DatabaseRepository<JavaType>`
to be wired (always true in production Spring context). Use `compile()` in tests
that don't need persistence.

---

## `JavaTypeClassLoader` Lifecycle

```java
@Autowired JavaTypeClassLoader loader;

// After compileAndSave, bytes are staged for lazy load:
loader.cacheSize();                     // staged + loaded count

// Evict one type (+ its $Inner variants) to force DB reload:
loader.evict("com.vork.generated.Foo"); // clears staged bytes + class cache

// Evict everything:
loader.evictAll();

// cacheSize() counts both staged bytes and already-loaded classes.
int n = loader.cacheSize();
```

**Lazy `defineClass` design**: `register()` stages bytes rather than calling
`defineClass` immediately. `defineClass` is called on first `loadClass` access.
This prevents `LinkageError: duplicate class definition` when the same FQN is
recompiled in the same JVM session.

---

## AI Tool — `compileJavaType`

**Tool name**: `compileJavaType`  
**Description**: Compile a Java type from source code and load it into the
running application. The type is persisted to MongoDB and available after restart.

**Input** (`CompileTypeRequest`):
```json
{ "source": "package com.vork.generated;\npublic record Foo(String bar) {}" }
```

**Success response**:
```json
{ "status": "ok", "class": "com.vork.generated.Foo" }
```

**Error response**:
```json
{ "status": "error", "message": "Compilation failed for ..." }
```

Use `package com.vork.generated` for all AI-generated types.

---

## Configuration

No extra properties needed. `TypeGeneratorConfig` registers the `DatabaseRepository<JavaType>`
bean. `JavaTypeClassLoader` is auto-wired by Spring via `@Component`.

Requires a JDK (not a bare JRE) — `javax.tools.JavaCompiler` must be available.

---

## Module Layout

```
src/main/java/com/vork/
└── typegen/
    ├── JavaType.java                  ← DatabaseEntity; collection: java_type
    ├── TypeGeneratorConfig.java       ← @Bean DatabaseRepository<JavaType>
    ├── JavaTypeClassLoader.java       ← @Component; lazy DB-backed ClassLoader
    ├── TypeGeneratorService.java      ← @Service; compile/save/load
    ├── InMemoryJavaFileManager.java   ← in-memory javac file manager
    ├── InMemoryClassLoader.java       ← per-compilation ClassLoader
    └── TypeGenerationException.java   ← runtime exception

src/main/java/com/vork/ai/function/
    └── CompileTypeRequest.java        ← input schema for compileJavaType tool

src/test/java/com/vork/typegen/
    └── TypeGeneratorServiceTest.java  ← 54 tests (7 groups, no Spring context)
```

---

# Vork — UI Standards Reference

## Overview

All front-end pages follow a strict asset separation policy. No inline CSS or
JavaScript is permitted in HTML files.

---

## Asset Organisation

| Asset type | Location | Notes |
|---|---|---|
| Stylesheets | `src/main/resources/static/css/` | One file per page/component |
| JavaScript | `src/main/resources/static/js/` | One file per page/component |
| Images | `src/main/resources/static/images/` | PNG/SVG/ICO |

Served by Spring Boot's default static resource handler at paths `/css/…`, `/js/…`, `/images/…`.

---

## Rules

1. **No inline CSS** — no `<style>` blocks, no `style="…"` attributes in HTML files.
   All visual styling must live in a `.css` file under `static/css/`.

2. **No inline JavaScript** — no `<script>…</script>` blocks with code in HTML files.
   All logic must live in a `.js` file under `static/js/`.

3. **Vanilla JavaScript only** — no jQuery, no frontend frameworks (React, Vue, etc.).
   Use the native DOM API (`document.getElementById`, `addEventListener`, `fetch`, etc.).

4. **Bootstrap 5** — use Bootstrap 5.3.x CDN for layout, typography, components, and
   utility classes. Do not write custom CSS for anything Bootstrap already covers.

5. **Mobile responsive** — every page must be usable on small screens.
   Use Bootstrap's responsive grid (`col-*`, `col-sm-*`, etc.) and utility classes
   (`d-flex`, `gap-*`, `px-*`). Add `@media (max-width: 575.98px)` overrides in the
   page's CSS file for any custom component that needs them.

6. **Dark theme** — `<html data-bs-theme="dark">` is the default.

7. **Favicon** — always include:
   ```html
   <link rel="icon" type="image/png" href="/images/Vork-Faviconx128.png">
   ```

---

## HTML Template Skeleton

```html
<!DOCTYPE html>
<html lang="en" data-bs-theme="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Page Title — Vork</title>
    <link rel="icon" type="image/png" href="/images/Vork-Faviconx128.png">
    <!-- Bootstrap CSS -->
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <!-- Font Awesome -->
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css">
    <!-- Page stylesheet (no inline CSS) -->
    <link rel="stylesheet" href="/css/page-name.css">
</head>
<body>

    <!-- Page content using Bootstrap classes only -->

    <!-- Bootstrap JS bundle (if needed for dropdowns, modals, etc.) -->
    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
    <!-- Third-party scripts (SockJS, StompJS, marked, etc.) if needed -->
    <!-- Page script (no inline JS) -->
    <script src="/js/page-name.js"></script>

</body>
</html>
```

---

## CDN Versions in Use

| Library | Version | CDN |
|---|---|---|
| Bootstrap CSS/JS | 5.3.3 | `cdn.jsdelivr.net/npm/bootstrap@5.3.3/…` |
| Font Awesome | 6.5.2 | `cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/…` |
| SockJS | 1.6.1 | `cdn.jsdelivr.net/npm/sockjs-client@1.6.1/dist/sockjs.min.js` |
| StompJS | 7.0.0 | `cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js` |
| marked | 12.x | `cdn.jsdelivr.net/npm/marked@12.0.0/marked.min.js` |

> **marked v12 API note:** `marked.setOptions()` was removed.  
> Use `marked.use({ breaks: true, gfm: true })` instead.

---

---

# Vork — TypeDatabase Service & Controller Reference

## Overview

The TypeDatabase layer provides a **runtime-generic CRUD API** over any Java record
that implements `DatabaseEntity`. Type classes are resolved at request-time via
`JavaTypeClassLoader`, so types compiled and persisted by the AI (`compileJavaType`
tool) are immediately addressable without a restart.

---

## Core Classes

| Class | Package | Role |
|---|---|---|
| `TypeDatabaseService` | `sh.vork.typegen` | Routes CRUD ops to a lazily-created `DatabaseRepository` per type |
| `FormToObjectConverter` | `sh.vork.typegen` | Converts multipart form parameters into a record instance via reflection |
| `FormConversionException` | `sh.vork.typegen` | Thrown when form→object conversion fails |
| `TypeDatabaseController` | `sh.vork.typegen.controller` | `REST /api/types/{fqn}/…` endpoints |

---

## `TypeDatabaseService` API

```java
@Autowired TypeDatabaseService typeDatabaseService;

// Get a single entity — returns null if not found
Object entity = typeDatabaseService.get(MyRecord.class, uuid);

// Save (create or update — upsert by uuid)
typeDatabaseService.save(myRecordInstance);   // entity must implement DatabaseEntity

// Delete — no-op if not found
typeDatabaseService.delete(MyRecord.class, uuid);

// Paginated list — stream MUST be closed
try (Stream<Object> stream = typeDatabaseService.list(MyRecord.class, 0, 20)) {
    List<Object> items = stream.toList();
}

// Count
long n = typeDatabaseService.count(MyRecord.class);
```

**Repository caching:** A `DatabaseRepository` is created once per `Class<?>` object
and cached in a `ConcurrentHashMap`. If a type is recompiled (producing a new `Class`
object), a fresh repository is automatically created for the new version.

**Validation:** Both `save()` and the internal `repositoryFor()` verify that the
supplied class implements `DatabaseEntity`. An `IllegalArgumentException` is thrown
if it does not.

---

## `FormToObjectConverter` — Field Naming Convention

`FormToObjectConverter` maps HTTP request parameters to record constructor arguments
by name. Nested structures use **dot notation**; lists use **bracket-index notation**.

### Flat record
```
POST /api/types/sh.vork.generated.Product
name=Widget&price=9.99&uuid=abc-123
```

### Nested record (`address` is itself a record)
```
address.street=1+Main+St&address.city=London&uuid=abc-123
```

### List of scalars
```
tags[0]=sale&tags[1]=new        ← indexed
tags=sale&tags=new              ← repeated key (also accepted)
```

### List of nested records
```
items[0].name=Widget&items[0].price=9.99
items[1].name=Gadget&items[1].price=4.99
```

### Type coercion
| Java type | Accepted form values |
|---|---|
| `String` | any string |
| `int` / `Integer` | `"42"` |
| `long` / `Long` | `"1234567890"` |
| `double` / `Double` | `"3.14"` |
| `boolean` / `Boolean` | `"true"` / `"false"` |
| `BigDecimal` | `"9.99"` (via Jackson) |
| Any other serialisable type | JSON-string value parsed by Jackson |

Missing parameters yield `null` for object types, `0`/`false` for primitives.

---

## REST API — `TypeDatabaseController`

Base path: `/api/types/{fqn}`

The `{fqn}` path segment is the **fully-qualified class name** with dots, e.g.
`sh.vork.generated.Product`.

### Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/types/{fqn}/list` | Paginated list |
| `GET` | `/api/types/{fqn}/count` | Document count |
| `GET` | `/api/types/{fqn}/{uuid}` | Single entity |
| `POST` | `/api/types/{fqn}` | Save (upsert) |
| `DELETE` | `/api/types/{fqn}/{uuid}` | Delete |

All responses are `application/json`.

---

### `GET /api/types/{fqn}/list`

Query parameters:

| Parameter | Default | Description |
|---|---|---|
| `page` | `0` | Zero-based page number |
| `pageSize` | `20` | Entities per page |

**Response:** JSON array of serialised entities.
```json
[
  { "uuid": "abc-123", "name": "Widget", "price": 9.99 },
  { "uuid": "def-456", "name": "Gadget", "price": 4.99 }
]
```

---

### `GET /api/types/{fqn}/count`

**Response:**
```json
{ "count": 42 }
```

---

### `GET /api/types/{fqn}/{uuid}`

**Response:** Serialised entity, or `404` if not found.
```json
{ "uuid": "abc-123", "name": "Widget", "price": 9.99 }
```

---

### `POST /api/types/{fqn}`

Content-Type: `multipart/form-data` or `application/x-www-form-urlencoded`.

The controller passes `HttpServletRequest.getParameterMap()` directly to
`FormToObjectConverter`. The resulting object is then passed to
`TypeDatabaseService.save()`.

**Success response:**
```json
{ "status": "ok", "uuid": "abc-123" }
```

**Error response (conversion failure or invalid type):**
```json
{ "status": "error", "message": "..." }
```

---

### `DELETE /api/types/{fqn}/{uuid}`

**Response:**
```json
{ "status": "ok" }
```

---

## UI Page Pattern

Pages that display or edit custom-type data **must not** render data server-side.
Follow this pattern:

1. **Server renders the empty page shell** — HTML + asset links only, no entity data.
2. **On page load, the JS calls the list/get endpoint** to populate the view:
   ```js
   fetch('/api/types/sh.vork.generated.Product/list?page=0&pageSize=20')
     .then(r => r.json())
     .then(items => renderTable(items));
   ```
3. **Forms submit via `fetch` with `FormData`** (multipart encoding):
   ```js
   const form = document.getElementById('product-form');
   form.addEventListener('submit', e => {
       e.preventDefault();
       fetch('/api/types/sh.vork.generated.Product', {
           method: 'POST',
           body: new FormData(form)   // multipart encoding, supports files
       })
       .then(r => r.json())
       .then(res => { if (res.status === 'ok') reload(); });
   });
   ```

This keeps the server stateless for data concerns and allows future file-upload
fields without changes to the controller or converter.

---

## Module Layout

```
src/main/java/sh/vork/
└── typegen/
    ├── TypeDatabaseService.java         ← @Service; per-type repo cache + CRUD
    ├── FormToObjectConverter.java       ← @Component; multipart params → record instance
    ├── FormConversionException.java     ← RuntimeException for conversion failures
    └── controller/
        └── TypeDatabaseController.java  ← @RestController; /api/types/{fqn}/…
```

---

---

# Vork — Search Query API Reference

## Overview

The Search Query API provides a type-safe, composable predicate system for
querying any `DatabaseEntity`. It has three entry points:

| Entry point | Best for |
|---|---|
| `SearchQuery` factory methods | Java code, programmatic query construction |
| `SqlQueryParser.parse(sql)` | Human-readable SQL-like WHERE clauses |
| Raw MongoDB JSON filter | Direct MongoDB expressions |

All three entry points feed into `DatabaseRepository.search()` /
`TypeDatabaseService.search*()` and the `searchTypeInstances` AI tool.

---

## `SortOrder`

```java
public enum SortOrder { ASC, DESC }
```

Used in every search method to control the sort direction of the `sortField`.

---

## `SearchQuery` — Predicate Building Blocks

`SearchQuery` is a **sealed interface** with 13 record implementations.
All implementations provide:
- `toMongoFilter()` — the MongoDB `Bson` filter for `MongoDBRepository`
- `test(Map<String, Object>)` — in-memory evaluation for `MapDatabaseRepository`

### Factory Methods

```java
// Equality / inequality
SearchQuery.eq("status", "active")           // field == value
SearchQuery.ne("status", "deleted")          // field != value

// Comparisons (numeric, string, date as epoch)
SearchQuery.gt("age", 18)                    // field > value
SearchQuery.gte("age", 18)                   // field >= value
SearchQuery.lt("score", 50.0)                // field < value
SearchQuery.lte("score", 50.0)              // field <= value

// String matching
SearchQuery.like("name", "ali")              // case-insensitive substring (%ali%)
SearchQuery.regex("name", "(?i)^al.*")      // full regex pattern

// Membership
SearchQuery.in("role", "admin", "mod")       // field IN (...)
SearchQuery.in("score", new int[]{1, 2, 3}) // primitive int[] overload
SearchQuery.in("ids", myList)               // List<?> overload

// Field presence
SearchQuery.exists("deletedAt")              // field is present
SearchQuery.exists("deletedAt", false)       // field is absent (IS NULL)
SearchQuery.exists("deletedAt", true)        // field is present (IS NOT NULL)

// Logical combinators
SearchQuery.and(q1, q2)                      // both must match
SearchQuery.or(q1, q2)                       // either must match
SearchQuery.not(q)                           // negates q (use with operator predicates)
```

### Predicate Records

| Record | Fields | MongoDB operator |
|---|---|---|
| `Eq(field, value)` | field, value | `$eq` |
| `Ne(field, value)` | field, value | `$ne` |
| `Gt(field, value)` | field, value | `$gt` |
| `Gte(field, value)` | field, value | `$gte` |
| `Lt(field, value)` | field, value | `$lt` |
| `Lte(field, value)` | field, value | `$lte` |
| `Like(field, substring)` | field, substring | `{$regex: "\\Q…\\E", $options:"i"}` |
| `Regex(field, pattern)` | field, pattern | `{$regex: pattern}` |
| `In(field, values)` | field, List<?> values | `$in` |
| `Exists(field, exists)` | field, boolean exists | `$exists` |
| `And(queries)` | List<SearchQuery> | `$and` |
| `Or(queries)` | List<SearchQuery> | `$or` |
| `Not(query)` | SearchQuery query | `$not` |

### Dot Notation for Nested Fields

Use `.` to address fields inside nested records:
```java
SearchQuery.eq("address.city", "London")
SearchQuery.gt("stats.score", 80)
SearchQuery.like("contact.email", "@example.com")
```

### Composing Multiple Predicates

Multiple predicates passed to `search()` are **AND-combined** automatically:
```java
// Implicit AND — equivalent to an explicit and(...)
repo.search(0, 20, "name", SortOrder.ASC,
    SearchQuery.eq("active", true),
    SearchQuery.gt("age", 18),
    SearchQuery.like("name", "ali"));
```

---

## `DatabaseRepository` — Search Methods

```java
// Filtered + sorted + paged stream
Stream<T> search(int page, int pageSize, String sortField, SortOrder sortOrder,
                 SearchQuery... queries);

// Count of matching documents
long searchCount(SearchQuery... queries);

// Raw MongoDB JSON filter (MongoDBRepository only)
Stream<T> searchRaw(int page, int pageSize, String sortField, SortOrder sortOrder,
                    String filterJson);

// Count for raw MongoDB JSON filter
long searchCountRaw(String filterJson);
```

`search()` and `searchCount()` are supported by both `MongoDBRepository` and
`MapDatabaseRepository`. The `searchRaw` / `searchCountRaw` methods are only
implemented by `MongoDBRepository`; calling them on `MapDatabaseRepository` throws
`UnsupportedOperationException`.

Streams returned by `search()` and `searchRaw()` are lazy and **must be closed**:
```java
try (Stream<Product> stream = repo.search(0, 20, "name", SortOrder.ASC,
        SearchQuery.eq("active", true))) {
    List<Product> results = stream.toList();
}
```

---

## `SqlQueryParser` — SQL WHERE Clause Parsing

`SqlQueryParser.parse(sql)` converts a SQL WHERE-clause expression (without the
`WHERE` keyword) into a `SearchQuery`.

### Entry Point

```java
SearchQuery q = SqlQueryParser.parse("name = 'Alice' AND age > 18");
```

### Supported Syntax

| Syntax | Example | Maps to |
|---|---|---|
| Equality | `name = 'Alice'` | `SearchQuery.eq` |
| Inequality | `name != 'Bob'`, `name <> 'Bob'` | `SearchQuery.ne` |
| Comparisons | `age > 18`, `score <= 9.5` | `SearchQuery.gt/gte/lt/lte` |
| LIKE (substring) | `name LIKE '%ali%'` | `SearchQuery.like` (optimised) |
| LIKE (prefix) | `name LIKE 'ali%'` | `SearchQuery.regex` |
| LIKE (suffix) | `name LIKE '%ice'` | `SearchQuery.regex` |
| LIKE (underscore) | `code LIKE 'A_1'` | `SearchQuery.regex` (`.` for each `_`) |
| NOT LIKE | `name NOT LIKE '%ali%'` | `SearchQuery.not(like/regex)` |
| IN | `status IN ('active', 'pending')` | `SearchQuery.in` |
| NOT IN | `status NOT IN ('banned')` | `SearchQuery.not(in)` |
| IS NULL | `deletedAt IS NULL` | `SearchQuery.exists(field, false)` |
| IS NOT NULL | `createdAt IS NOT NULL` | `SearchQuery.exists(field, true)` |
| AND | `a = 1 AND b = 2` | `SearchQuery.and` |
| OR | `a = 1 OR b = 2` | `SearchQuery.or` |
| NOT | `NOT active = false` | `SearchQuery.not` |
| Grouping | `(a = 1 OR b = 2) AND c = 3` | nested combinators |
| Boolean literals | `active = true` / `flag = FALSE` | plain `Boolean` value |
| Null literal | `name = null` | `null` value in `Eq` |
| Negative numbers | `delta = -5`, `temp = -1.5` | `Integer` / `Double` |
| Dot notation | `address.city = 'London'` | dotted field path |

**Operator precedence** (high to low): `NOT` → `AND` → `OR`. Parentheses override.

**String literals** must be single-quoted. Escape a literal `'` by doubling: `'it''s fine'`.

**Keywords** (`AND`, `OR`, `NOT`, `LIKE`, `IN`, `IS`, `NULL`, `TRUE`, `FALSE`) are
case-insensitive.

### LIKE Pattern Translation Details

| SQL pattern | Translation | Notes |
|---|---|---|
| `%x%` (no inner wildcards) | `SearchQuery.like(field, x)` | Optimised; uses `$regex: "\\Qx\\E"` with `i` option |
| `x%` / `%x` / `x` | `SearchQuery.regex(…)` | Anchored regex with `(?i)` prefix |
| `a_b` | `SearchQuery.regex(…)` | Each `_` → `.` in regex |
| `%a%b%` | `SearchQuery.regex(…)` | Each `%` → `.*` in regex |
| Literal `.`, `+`, `(` etc. | `SearchQuery.regex(…)` | Quoted via `Pattern.quote()` |

### Error Handling

Throws `SqlParseException` (a `RuntimeException`) on malformed input:
```java
try {
    SearchQuery q = SqlQueryParser.parse(userInput);
} catch (SqlParseException e) {
    // e.getMessage() describes the parse error
}
```

---

## `TypeDatabaseService` — Runtime-Type Search Methods

All search methods on `TypeDatabaseService` accept a `Class<?>` (resolved at
runtime via `JavaTypeClassLoader`) instead of a static type parameter.

```java
@Autowired TypeDatabaseService typeDatabaseService;

// Search with SearchQuery predicates directly
try (Stream<Object> s = typeDatabaseService.search(
        MyRecord.class, 0, 20, "name", SortOrder.ASC,
        SearchQuery.eq("active", true),
        SearchQuery.gt("age", 18))) {
    List<Object> results = s.toList();
}
long n = typeDatabaseService.searchCount(MyRecord.class,
        SearchQuery.eq("active", true));

// Search via SQL WHERE clause
try (Stream<Object> s = typeDatabaseService.searchBySql(
        MyRecord.class, "name LIKE '%ali%' AND age > 18",
        0, 20, "name", SortOrder.ASC)) {
    List<Object> results = s.toList();
}
long n = typeDatabaseService.searchCountBySql(MyRecord.class,
        "status IN ('active', 'pending')");

// Search via raw MongoDB JSON filter (MongoDBRepository only)
try (Stream<Object> s = typeDatabaseService.searchByMongoFilter(
        MyRecord.class, "{\"active\":true,\"age\":{\"$gt\":18}}",
        0, 20, "name", SortOrder.ASC)) {
    List<Object> results = s.toList();
}
long n = typeDatabaseService.searchCountByMongoFilter(MyRecord.class,
        "{\"status\":\"active\"}");
```

### Method Summary

| Method | Description |
|---|---|
| `search(class, page, pageSize, sortField, sortOrder, queries...)` | Predicate-based search |
| `searchCount(class, queries...)` | Count matching predicates |
| `searchBySql(class, sqlWhere, page, pageSize, sortField, sortOrder)` | SQL WHERE clause |
| `searchCountBySql(class, sqlWhere)` | Count via SQL WHERE |
| `searchByMongoFilter(class, filterJson, page, pageSize, sortField, sortOrder)` | Raw MongoDB JSON |
| `searchCountByMongoFilter(class, filterJson)` | Count via raw MongoDB JSON |

---

## AI Tool — `searchTypeInstances`

Allows the AI to search any runtime-compiled type using either SQL or MongoDB syntax.

**Tool name:** `searchTypeInstances`

### Input Schema (`SearchTypeInstancesRequest`)

| Field | Required | Default | Description |
|---|---|---|---|
| `fqn` | ✓ | — | Fully-qualified class name, e.g. `sh.vork.generated.Product` |
| `query` | ✓ | — | The search expression (SQL or MongoDB JSON depending on `queryType`) |
| `queryType` | | `SQL` | `SQL` or `MONGO` (case-insensitive) |
| `sortField` | | `uuid` | Field to sort results by |
| `sortOrder` | | `ASC` | `ASC` or `DESC` |
| `page` | | `0` | Zero-based page number |
| `pageSize` | | `20` | Results per page |

### SQL Query Examples (queryType = SQL)

```
name = 'Alice'
age > 18 AND active = true
status IN ('active', 'pending')
name LIKE '%ali%'
address.city = 'London' AND score >= 8.0
deletedAt IS NULL
createdAt IS NOT NULL AND name NOT LIKE '%test%'
(role = 'admin' OR role = 'mod') AND active = true
```

### MongoDB Query Examples (queryType = MONGO)

```json
{"status": "active"}
{"age": {"$gt": 18}, "active": true}
{"$or": [{"role": "admin"}, {"role": "mod"}]}
{"name": {"$regex": "ali", "$options": "i"}}
```

### Response Format

```json
{
  "total": 42,
  "page": 0,
  "pageSize": 20,
  "results": [
    { "uuid": "abc-123", "name": "Alice", "age": 30 },
    ...
  ]
}
```

On parse or lookup error:
```json
{ "status": "error", "message": "Cannot find type: sh.vork.generated.Missing" }
```

---

## Testing with `MapDatabaseRepository`

`MapDatabaseRepository` supports `search()` and `searchCount()` via in-memory
`SearchQuery.test()` evaluation. No MongoDB server needed:

```java
DatabaseRepository<Product> repo = new MapDatabaseRepository<>(Product.class);

repo.save(new Product("1", "Alice Widget", new BigDecimal("9.99"), List.of("sale")));
repo.save(new Product("2", "Bob Gadget",   new BigDecimal("4.99"), List.of()));

// Predicate search
try (Stream<Product> s = repo.search(0, 10, "name", SortOrder.ASC,
        SearchQuery.like("name", "alice"))) {
    List<Product> found = s.toList();
    assertEquals(1, found.size());
}

// Count
assertEquals(2, repo.searchCount(/* no predicates = all */));
assertEquals(1, repo.searchCount(SearchQuery.gt("price", 5.0)));
```

Note: `searchRaw()` / `searchCountRaw()` are **not** supported by
`MapDatabaseRepository` and throw `UnsupportedOperationException`. Use
`SqlQueryParser.parse()` + `search()` when testing SQL-like queries in unit tests.

---

## Module Layout

```
src/main/java/sh/vork/
├── database/
│   ├── SearchQuery.java                ← sealed interface + 13 implementations
│   ├── SortOrder.java                  ← enum: ASC, DESC
│   ├── DatabaseRepository.java         ← search() / searchCount() / searchRaw() / searchCountRaw()
│   └── mongo/
│       └── MongoDBRepository.java      ← full search implementation
└── typegen/
    ├── SqlQueryParser.java             ← SQL WHERE → SearchQuery
    ├── SqlParseException.java          ← thrown on parse failure
    └── TypeDatabaseService.java        ← search*() / searchBy*() runtime entry points

src/main/java/sh/vork/ai/function/
    └── SearchTypeInstancesRequest.java ← input schema for searchTypeInstances tool

src/test/java/sh/vork/
├── database/
│   ├── SearchQueryTest.java            ← 60 tests (all 13 predicate types + combinators)
│   └── DatabaseRepositorySearchTest.java ← 108 integration tests (MapDatabaseRepository)
└── typegen/
    └── SqlQueryParserTest.java         ← 147 tests (all syntax, LIKE patterns, errors)
```
