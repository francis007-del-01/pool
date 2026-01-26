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
// Graceful shutdown - finish queued tasks
executor.shutdown();
executor.awaitTermination(30, TimeUnit.SECONDS);

// Or immediate shutdown - interrupt running tasks
executor.shutdownNow();
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
| `allow-core-thread-timeout` | true | Allow core threads to time out when idle |

### Priority Strategy

Pool currently supports `FIFO` only. Other types (`TIME_BASED`, `BUCKET_BASED`) are reserved for future implementations and will raise a configuration error if selected.

### Priority Tree

The priority tree is evaluated top-to-bottom. First matching node wins. Always include a catch-all `ALWAYS_TRUE` at the end.

```yaml
priority-tree:
  - name: "NODE_NAME"
    condition:
      type: EQUALS
      field: $req.region
      value: "US"
    nested-levels:
      - name: "NESTED_NODE"
        condition:
          type: GREATER_THAN
          field: $req.amount
          value: 10000
        sort-by:
          field: $req.priority
          direction: DESC
```

---

## How Priority Tree & Vector Comparison Works

This is the core algorithm that makes Pool powerful. Understanding this helps you design effective prioritization rules.

### The Priority Tree Structure

The priority tree is a **hierarchical decision tree** where each level represents a prioritization dimension. Think of it like a multi-level sorting system:

**Depth limit:** The tree supports up to 10 levels (levels 1-10). Deeper configurations are rejected.

```
Level 1: Region          Level 2: Customer Tier    Level 3: Transaction Size
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│ 1. NORTH_AMERICA│──────│ 1. PLATINUM      │──────│ 1. HIGH_VALUE   │
│ 2. EUROPE       │      │ 2. GOLD          │      │ 2. DEFAULT      │
│ 3. DEFAULT      │      │ 3. DEFAULT       │      └─────────────────┘
└─────────────────┘      └──────────────────┘
```

### Path Matching

When a task is submitted, Pool traverses the tree to find the **first matching path**:

```yaml
priority-tree:
  - name: "L1.NORTH_AMERICA"        # Index 1 at Level 1
    condition:
      type: EQUALS
      field: $req.region
      value: "NORTH_AMERICA"
    nested-levels:
      - name: "L2.PLATINUM"         # Index 1 at Level 2
        condition:
          type: EQUALS
          field: $req.customerTier
          value: "PLATINUM"
        nested-levels:
          - name: "L3.HIGH_VALUE"   # Index 1 at Level 3
            condition:
              type: GREATER_THAN
              field: $req.amount
              value: 100000
            sort-by:
              field: $req.priority
              direction: DESC
```

**Example**: A task with `{region: "NORTH_AMERICA", customerTier: "PLATINUM", amount: 500000}` would match:
```
Path: L1.NORTH_AMERICA → L2.PLATINUM → L3.HIGH_VALUE
```

### The Path Vector

Each matched path is converted to a **Path Vector** - an array of indices representing which branch was taken at each level:

| Task | Matched Path | Path Vector |
|------|--------------|-------------|
| NA + Platinum + High Value | L1.NORTH_AMERICA → L2.PLATINUM → L3.HIGH_VALUE | `[1, 1, 1]` |
| NA + Platinum + Default | L1.NORTH_AMERICA → L2.PLATINUM → L3.DEFAULT | `[1, 1, 2]` |
| NA + Gold + Default | L1.NORTH_AMERICA → L2.GOLD → L3.DEFAULT | `[1, 2, 1]` |
| Europe + Default + Default | L1.EUROPE → L2.DEFAULT → L3.DEFAULT | `[2, 1, 1]` |
| Asia + Default + Default | L1.DEFAULT → L2.DEFAULT → L3.DEFAULT | `[3, 1, 1]` |

### Vector Comparison (Lexicographic)

Tasks are ordered by comparing their path vectors **lexicographically** (like dictionary sorting):

```
[1, 1, 1] < [1, 1, 2] < [1, 2, 1] < [2, 1, 1] < [3, 1, 1]
   ↑           ↑           ↑           ↑           ↑
 Highest    Second      Third       Fourth      Lowest
Priority   Priority    Priority    Priority    Priority
```

**Comparison Rules:**
1. Compare first element: lower wins
2. If equal, compare second element
3. Continue until a difference is found
4. **Lower values = Higher priority**

**Example Comparisons:**
```
[1, 1, 1] vs [1, 2, 1]  → [1, 1, 1] wins (1 < 2 at position 2)
[1, 2, 1] vs [2, 1, 1]  → [1, 2, 1] wins (1 < 2 at position 1)
[2, 1, 1] vs [2, 1, 2]  → [2, 1, 1] wins (1 < 2 at position 3)
```

### Within-Bucket Ordering (sort-by)

When multiple tasks have the **same path vector** (same bucket), they're ordered by the `sort-by` field defined at the leaf node:

```yaml
- name: "L3.HIGH_VALUE"
  condition:
    type: GREATER_THAN
    field: $req.amount
    value: 100000
  sort-by:
    field: $req.priority    # Sort by this field within this bucket
    direction: DESC         # Higher priority values first
```

**Example**: Three tasks all match `[1, 1, 1]`:
| Task | priority | Order |
|------|----------|-------|
| Task A | 95 | 1st (highest priority) |
| Task B | 80 | 2nd |
| Task C | 50 | 3rd |

### Complete Priority Calculation

The final priority is determined by:

1. **Primary**: Path Vector (which bucket)
2. **Secondary**: Sort-by value (within bucket)

```
Final Order = PathVector comparison → then → SortBy comparison
```

### Visual Example

Given this tree:
```yaml
priority-tree:
  - name: "PLATINUM"       # Index 1
    condition: { type: EQUALS, field: $req.tier, value: "PLATINUM" }
    sort-by: { field: $req.priority, direction: DESC }
    
  - name: "GOLD"           # Index 2
    condition: { type: EQUALS, field: $req.tier, value: "GOLD" }
    sort-by: { field: $sys.submittedAt, direction: ASC }
    
  - name: "DEFAULT"        # Index 3
    condition: { type: ALWAYS_TRUE }
    sort-by: { field: $sys.submittedAt, direction: ASC }
```

And these tasks submitted:
| Task | tier | priority | submittedAt | Path Vector | Bucket Order |
|------|------|----------|-------------|-------------|--------------|
| T1 | PLATINUM | 90 | 1000 | `[1]` | 2nd in bucket |
| T2 | PLATINUM | 95 | 1001 | `[1]` | 1st in bucket |
| T3 | GOLD | - | 999 | `[2]` | 1st in bucket |
| T4 | GOLD | - | 1002 | `[2]` | 2nd in bucket |
| T5 | SILVER | - | 998 | `[3]` | 1st in bucket |

**Execution Order**: T2 → T1 → T3 → T4 → T5

```
┌─────────────────────────────────────────────────────────────┐
│  Bucket [1] PLATINUM     │  Bucket [2] GOLD  │ Bucket [3]   │
│  (sorted by priority↓)   │  (sorted by time↑)│ DEFAULT      │
│  ┌─────┐ ┌─────┐         │  ┌─────┐ ┌─────┐  │ ┌─────┐      │
│  │ T2  │ │ T1  │         │  │ T3  │ │ T4  │  │ │ T5  │      │
│  │p=95 │ │p=90 │         │  │t=999│ │t=1002│ │ │t=998│      │
│  └─────┘ └─────┘         │  └─────┘ └─────┘  │ └─────┘      │
│     ↓       ↓            │     ↓       ↓     │    ↓         │
└─────────────────────────────────────────────────────────────┘
        1st     2nd              3rd     4th        5th
```

### Design Tips

1. **Put highest priority conditions first** - They get lower indices (higher priority)
2. **Always end with ALWAYS_TRUE** - Catch-all for unmatched tasks
3. **Use nested-levels for multi-dimensional priority** - Region → Tier → Amount
4. **Choose sort-by wisely**:
   - `$req.priority DESC` - User-provided priority
   - `$sys.submittedAt ASC` - FIFO within bucket
   - `$req.amount DESC` - Highest value first

---

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

## API Reference

### PoolExecutor

```java
public interface PoolExecutor {
    // Submit a runnable task
    void submit(TaskContext context, Runnable task);
    
    // Submit a callable task with return value
    <T> Future<T> submit(TaskContext context, Callable<T> task);
    
    // Graceful shutdown
    void shutdown();
    
    // Immediate shutdown
    void shutdownNow();
    
    // Wait for termination
    boolean awaitTermination(long timeout, TimeUnit unit);
    
    // Status
    boolean isShutdown();
    boolean isTerminated();
    int getQueueSize();
    int getActiveCount();
}
```

### TaskContextFactory

```java
// Create from JSON and context map
TaskContext ctx = TaskContextFactory.create(jsonPayload, contextMap);

// Create with specific task ID
TaskContext ctx = TaskContextFactory.create("task-123", jsonPayload, contextMap);
```

## Requirements

- Java 21+
- Spring Boot 3.2+ (optional, for auto-configuration)

## License

MIT License - see [LICENSE](LICENSE)
