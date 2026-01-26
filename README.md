# Pool

A policy-driven executor library with intelligent task prioritization for Java applications.

## What is Pool?

Pool is a **pluggable library** that replaces your standard thread pool executor with one that supports dynamic, policy-based task prioritization. Simply add the dependency, provide a YAML configuration, and submit tasks - Pool handles the intelligent ordering.

Instead of simple FIFO queues, Pool uses a **priority tree** to dynamically route and order tasks based on request attributes, business rules, and system state.

## Why Pool?

- **No Code Changes for Priority Logic** - Change prioritization rules via YAML, no redeployment needed
- **Business-Driven Ordering** - Prioritize by customer tier, transaction amount, region, or any field
- **Drop-in Replacement** - Works like a standard executor, just smarter
- **Framework Agnostic** - Works with Spring Boot, Micronaut, plain Java, or any JVM application

## Installation

### Maven

```xml
<dependency>
    <groupId>com.pool</groupId>
    <artifactId>pool</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.pool:pool:1.0.0-SNAPSHOT'
```

## Quick Start

### 1. Add your configuration

Create `pool.yaml` in your resources or any location:

```yaml
pool:
  name: "order-processing-pool"
  version: "1.0"

  executor:
    core-pool-size: 10
    max-pool-size: 50
    queue-capacity: 1000

  priority-strategy:
    type: FIFO

  priority-tree:
    # Platinum customers get highest priority
    - name: "PLATINUM"
      condition:
        type: EQUALS
        field: $req.customerTier
        value: "PLATINUM"
      sort-by:
        field: $req.priority
        direction: DESC

    # High-value transactions next
    - name: "HIGH_VALUE"
      condition:
        type: GREATER_THAN
        field: $req.amount
        value: 100000
      sort-by:
        field: $sys.submittedAt
        direction: ASC

    # Everything else - FIFO
    - name: "DEFAULT"
      condition:
        type: ALWAYS_TRUE
      sort-by:
        field: $sys.submittedAt
        direction: ASC
```

### 2. Initialize the Pool Executor

```java
import com.pool.config.ConfigLoader;
import com.pool.config.PoolConfig;
import com.pool.core.PoolExecutor;
import com.pool.core.DefaultPoolExecutor;

// Load configuration
PoolConfig config = ConfigLoader.load("classpath:pool.yaml");
// Or from file path: ConfigLoader.load("/etc/myapp/pool.yaml");

// Create executor
PoolExecutor executor = new DefaultPoolExecutor(config);
```

### 3. Submit Tasks

```java
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;

// Your incoming request (JSON payload)
String jsonPayload = """
    {
        "customerTier": "PLATINUM",
        "amount": 250000,
        "orderId": "ORD-12345",
        "priority": 95
    }
    """;

// Context (headers, metadata, etc.)
Map<String, String> context = Map.of(
    "clientId", "mobile-app",
    "region", "us-east-1"
);

// Create task context
TaskContext taskContext = TaskContextFactory.create(jsonPayload, context);

// Submit task - Pool handles prioritization automatically
executor.submit(taskContext, () -> {
    processOrder(taskContext);
});
```

### 4. Shutdown

```java
executor.shutdown();
executor.awaitTermination(30, TimeUnit.SECONDS);
```

## Spring Boot Integration

For Spring Boot applications, use auto-configuration:

```java
@SpringBootApplication
@EnablePool
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

```properties
# application.properties
pool.config-path=classpath:pool.yaml
```

```java
@Service
public class OrderService {
    
    @Autowired
    private PoolExecutor poolExecutor;
    
    public void processOrder(String orderJson, Map<String, String> headers) {
        TaskContext ctx = TaskContextFactory.create(orderJson, headers);
        poolExecutor.submit(ctx, () -> doProcess(ctx));
    }
}
```

## Configuration Reference

### Executor Settings

| Property | Default | Description |
|----------|---------|-------------|
| `core-pool-size` | 10 | Minimum number of threads |
| `max-pool-size` | 50 | Maximum number of threads |
| `queue-capacity` | 1000 | Task queue capacity |
| `keep-alive-seconds` | 60 | Idle thread timeout |
| `thread-name-prefix` | "pool-" | Thread naming prefix |

### Priority Tree

The priority tree is evaluated top-to-bottom. First matching node wins. Always include a catch-all `ALWAYS_TRUE` at the end.

```yaml
priority-tree:
  - name: "NODE_NAME"
    condition:
      type: CONDITION_TYPE
      field: $req.fieldName    # or $ctx.*, $sys.*
      value: "expectedValue"
    nested-levels:             # Optional: deeper nesting
      - name: "NESTED_NODE"
        condition: ...
        sort-by: ...
    sort-by:                   # Required at leaf nodes
      field: $req.priority
      direction: DESC          # or ASC
```

## Variables

Variables are resolved at task submission time:

| Prefix | Source | Example |
|--------|--------|---------|
| `$req.*` | JSON payload | `$req.amount`, `$req.customer.tier` |
| `$ctx.*` | Context map | `$ctx.clientId`, `$ctx.region` |
| `$sys.*` | System (auto) | `$sys.submittedAt`, `$sys.taskId` |

### Nested JSON Support

JSON payloads are automatically flattened:

```json
{"customer": {"tier": "PLATINUM", "id": 123}}
```

Access as: `$req.customer.tier`, `$req.customer.id`

### System Variables

| Variable | Description |
|----------|-------------|
| `$sys.taskId` | Auto-generated unique task ID |
| `$sys.submittedAt` | Submission timestamp (epoch ms) |
| `$sys.time.now` | Same as submittedAt |
| `$sys.correlationId` | Correlation ID if provided |

## Condition Types

### Comparison
```yaml
type: EQUALS | NOT_EQUALS | GREATER_THAN | LESS_THAN | BETWEEN
field: $req.amount
value: 1000
value2: 5000  # Only for BETWEEN
```

### Collection
```yaml
type: IN | NOT_IN
field: $req.region
values: ["US", "CA", "MX"]
```

### String
```yaml
type: REGEX | STARTS_WITH | ENDS_WITH
field: $req.email
pattern: ".*@company\\.com"  # For REGEX
value: "@company.com"         # For ENDS_WITH
```

### Logical (Combine Conditions)
```yaml
type: AND | OR
conditions:
  - type: EQUALS
    field: $req.tier
    value: "PLATINUM"
  - type: GREATER_THAN
    field: $req.amount
    value: 10000
```

### Special
```yaml
type: ALWAYS_TRUE  # Catch-all, always matches
type: EXISTS       # Field exists
type: IS_NULL      # Field is null/missing
```

## Example: E-Commerce Order Processing

```yaml
pool:
  name: "order-pool"
  
  executor:
    core-pool-size: 20
    max-pool-size: 100
    
  priority-tree:
    # VIP customers with large orders
    - name: "VIP_LARGE"
      condition:
        type: AND
        conditions:
          - type: IN
            field: $req.customer.tier
            values: ["PLATINUM", "GOLD"]
          - type: GREATER_THAN
            field: $req.order.total
            value: 5000
      sort-by:
        field: $req.order.total
        direction: DESC

    # Express shipping
    - name: "EXPRESS"
      condition:
        type: EQUALS
        field: $req.shipping.type
        value: "EXPRESS"
      sort-by:
        field: $sys.submittedAt
        direction: ASC

    # Standard orders by submission time
    - name: "STANDARD"
      condition:
        type: ALWAYS_TRUE
      sort-by:
        field: $sys.submittedAt
        direction: ASC
```

## Requirements

- Java 21+
- Spring Boot 3.2+ (optional, for auto-configuration)

## License

MIT License - see [LICENSE](LICENSE)
