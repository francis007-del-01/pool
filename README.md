# Pool

A policy-driven prioritization library with TPS-based hierarchical executors for Java applications.

## What is Pool?

Pool is a **pluggable prioritization engine** that evaluates tasks against a policy tree and orders them by priority. It features **TPS-based rate limiting** with hierarchical executors and unbounded thread pools.

Instead of fixed thread pools, Pool uses **TPS limits** as the primary control mechanism, with priority-based queuing when limits are reached.

## Why Pool?

- **No Code Changes for Priority Logic** - Change prioritization rules via YAML, no redeployment needed
- **Business-Driven Ordering** - Prioritize by customer tier, transaction amount, region, or any field
- **TPS-Based Rate Limiting** - Configure throughput limits instead of thread counts
- **Hierarchical Executors** - Child executors consume from parent's TPS budget
- **Priority Under Contention** - When TPS limit hit, queued tasks ordered by priority
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

  # TPS-based hierarchical executors
  adapters:
    executors:
      # Root executor - defines system capacity
      - id: "main"
        tps: 1000              # Max 1000 requests/second
        queue_capacity: 5000   # Shared queue budget when TPS exceeded
        identifier_field: "$req.requestId"  # Count by request ID

      # Child executor for VIP customers
      - id: "vip"
        parent: "main"
        tps: 400               # Max 400 TPS (from main's budget)
        queue_capacity: 2000   # Queue for VIP executor
        identifier_field: "$req.requestId"  # Count by request ID

      # Child executor for bulk processing  
      - id: "bulk"
        parent: "main"
        tps: 200               # Max 200 TPS (from main's budget)
        queue_capacity: 1000   # Queue for bulk executor
        identifier_field: "$req.requestId"  # Count by request ID

  priority-strategy:
    type: FIFO

  priority-tree:
    # Platinum customers → VIP executor
    - name: "PLATINUM"
      condition: '$req.customerTier == "PLATINUM"'
      sort-by:
        field: $req.priority
        direction: DESC
      executor: "vip"           # Route to VIP executor

    # High-value transactions → VIP executor
    - name: "HIGH_VALUE"
      condition: "$req.amount > 100000"
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      executor: "vip"

    # Everything else → bulk executor
    - name: "DEFAULT"
      condition: "true"
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      executor: "bulk"           # Route to bulk executor
```

### 2. Initialize the TPS-Based Executor

```java
import com.pool.config.ConfigLoader;
import com.pool.config.PoolConfig;
import com.pool.adapter.executor.PoolExecutor;
import com.pool.adapter.executor.tps.TpsPoolExecutor;

// Load configuration
PoolConfig config = ConfigLoader.load("classpath:pool.yaml");
// Or from file path: ConfigLoader.load("/etc/myapp/pool.yaml");

// Create TPS-based executor with hierarchical rate limiting
PoolExecutor executor = new TpsPoolExecutor(config);
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

// Submit task - Pool routes to the correct queue automatically
// Workers for that queue will pick it up
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
import com.pool.adapter.spring.EnablePool;

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

### Condition Syntax

Conditions use expression strings evaluated against task context:

```yaml
priority-tree:
  - name: "VIP_CUSTOMERS"
    condition: '$req.customerTier == "PLATINUM"'   # Expression string
    executor: "fast"
```

**Supported Operators:**
- Comparison: `==`, `!=`, `>`, `<`, `>=`, `<=`
- Logical: `AND`, `OR`, `NOT`
- Collection: `IN`, `NOT IN`
- String: `REGEX`, `STARTS_WITH`, `ENDS_WITH`, `CONTAINS`
- Special: `EXISTS()`, `IS_NULL()`, `true` (catch-all)

### TPS-Based Executor Configuration

Path: `pool.adapters.executors[]`

Each executor defines a TPS limit and optional parent:

| Property | Default | Description |
|----------|---------|-------------|
| `id` | required | Unique executor identifier |
| `parent` | null | Parent executor ID (null for root) |
| `tps` | 0 | Max TPS limit (0 = unbounded) |
| `queue_capacity` | 0 | Max queue size when TPS exceeded |
| `identifier_field` | null | Field to extract unique request ID for TPS counting (e.g., `$req.requestId`) |

**Hierarchical TPS:**
- Child executors consume from parent's TPS budget
- If parent at limit, child requests are queued
- Child TPS cannot exceed parent TPS

### Standalone Scheduler (Without Executor)

For integrating with external systems (Kafka, Redis, etc.), use the standalone scheduler:

**Configuration:**
```yaml
pool:
  name: "my-scheduler"
  
  scheduler:
    queues:
      - name: "fast"
        index: 0
        capacity: 1000
      - name: "bulk"
        index: 1
        capacity: 5000
  
  priority-tree:
    - name: "HIGH_PRIORITY"
      condition: "$req.priority > 80"
      queue: "fast"           # Use queue: for standalone scheduler
    
    - name: "DEFAULT"
      condition: "true"
      queue: "bulk"
```

**Usage:**
```java
PoolConfig config = ConfigLoader.load("classpath:pool-scheduler.yaml");
PriorityScheduler<MyPayload> scheduler = new DefaultPriorityScheduler<>(config);

// Submit - returns queue name
String queueName = scheduler.submit(ctx, payload);

// Pull from first non-empty queue (by index order)
MyPayload next = scheduler.getNext();

// Pull from specific queue
MyPayload task = scheduler.getNext("fast");
```

| Property | Default | Description |
|----------|---------|-------------|
| `queues[].name` | required | Queue name |
| `queues[].index` | auto | Priority index (lower = higher priority) |
| `queues[].capacity` | 1000 | Maximum queue capacity |

**Note:** For TPS-based execution, use `adapters.executors` with `executor:` in priority-tree instead.

### Priority Strategy

Pool currently supports `FIFO` only. Other types (`TIME_BASED`, `BUCKET_BASED`) are reserved for future implementations.

### Priority Tree

The priority tree is evaluated top-to-bottom. First matching node wins. Leaf nodes specify the target `executor` (for TPS executor) or `queue` (for standalone scheduler).

```yaml
priority-tree:
  - name: "NODE_NAME"
    condition: '$req.region == "US"'
    nested-levels:
      - name: "NESTED_NODE"
        condition: "$req.amount > 10000"
        sort-by:
          field: $req.priority
          direction: DESC
        executor: "fast"      # Route matching tasks to "fast" executor
```

---

## TPS-Based Hierarchical Executor

Pool uses **TPS (Transactions Per Second)** as the primary rate control mechanism with hierarchical executors:

- **TPS Limits** - Configure throughput instead of thread counts
- **Hierarchical Executors** - Child executors share parent's TPS budget
- **Sliding Window** - 1-second window for accurate TPS tracking
- **Priority Queuing** - When TPS exceeded, tasks queued by priority

### How It Works

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Task Submission                               │
│                                                                         │
│   submit(context, task)                                                 │
│         │                                                               │
│         ▼                                                               │
│   ┌─────────────────┐                                                   │
│   │  Policy Engine  │  Evaluates priority tree                          │
│   │                 │  Determines: priority key + target executor       │
│   └────────┬────────┘                                                   │
│            │                                                            │
│            │  returns executor ID                                       │
│            ▼                                                            │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                      TPS Gate                                   │   │
│   │   • Check executor TPS limit                                    │   │
│   │   • Check all ancestor TPS limits                               │   │
│   │   • Track unique request IDs in sliding window                  │   │
│   └────────┬────────────────────────────────────────────────────────┘   │
│            │                                                            │
│     ┌──────┴──────┐                                                     │
│     │             │                                                     │
│  TPS OK      TPS Exceeded                                               │
│     │             │                                                     │
│     ▼             ▼                                                     │
│  ┌───────┐   ┌─────────────────────────────────────────┐                │
│  │Execute│   │  Per-Executor Priority Queue            │                │
│  │  Now  │   │  • Ordered by priority vector           │                │
│  └───────┘   │  • Drainer pulls when TPS frees up      │                │
│              └─────────────────────────────────────────┘                │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Concepts

1. **Executor Routing**: Leaf nodes in the priority tree specify an `executor` field. Tasks are routed to that executor's TPS gate.

2. **Hierarchical TPS**: Child executors consume from parent's budget:
   ```
   main (1000 TPS, queue: 5000)
   ├── vip (400 TPS, queue: 2000)    # Can use up to 400 of main's 1000
   └── bulk (200 TPS, queue: 1000)   # Can use up to 200 of main's 1000
   ```

3. **Unbounded Threads**: Threads spawn as needed when TPS allows - no fixed pool size.

4. **Priority Queuing**: When TPS exceeded, tasks queue ordered by priority vector.

### Example: VIP vs Bulk Processing

```yaml
adapters:
  executors:
    # Root executor: System capacity
    - id: "main"
      tps: 1000
      queue_capacity: 5000
      identifier_field: "$req.requestId"

    # VIP executor: Premium customers
    - id: "vip"
      parent: "main"
      tps: 400
      queue_capacity: 2000
      identifier_field: "$req.requestId"

    # Bulk executor: Background processing
    - id: "bulk"
      parent: "main"
      tps: 200
      queue_capacity: 1000
      identifier_field: "$req.requestId"

priority-tree:
  - name: "PLATINUM_CUSTOMERS"
    condition: '$req.tier == "PLATINUM"'
    executor: "vip"           # VIPs get dedicated TPS budget

  - name: "HIGH_VALUE"
    condition: "$req.amount > 50000"
    executor: "vip"           # High-value gets VIP treatment

  - name: "DEFAULT"
    condition: "true"
    executor: "bulk"          # Everything else to bulk
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
    condition: '$req.region == "NORTH_AMERICA"'
    nested-levels:
      - name: "L2.PLATINUM"         # Index 1 at Level 2
        condition: '$req.customerTier == "PLATINUM"'
        nested-levels:
          - name: "L3.HIGH_VALUE"   # Index 1 at Level 3
            condition: "$req.amount > 100000"
            sort-by:
              field: $req.priority
              direction: DESC
            executor: "fast"
```

**Example**: A task with `{region: "NORTH_AMERICA", customerTier: "PLATINUM", amount: 500000}` would match:
```
Path: L1.NORTH_AMERICA → L2.PLATINUM → L3.HIGH_VALUE → Executor: fast
```

### The Path Vector

Each matched path is converted to a **Path Vector** - an array of indices representing which branch was taken at each level:

| Task | Matched Path | Path Vector | Executor |
|------|--------------|-------------|----------|
| NA + Platinum + High Value | L1.NORTH_AMERICA → L2.PLATINUM → L3.HIGH_VALUE | `[1, 1, 1]` | fast |
| NA + Platinum + Default | L1.NORTH_AMERICA → L2.PLATINUM → L3.DEFAULT | `[1, 1, 2]` | fast |
| NA + Gold + Default | L1.NORTH_AMERICA → L2.GOLD → L3.DEFAULT | `[1, 2, 1]` | fast |
| Europe + Default + Default | L1.EUROPE → L2.DEFAULT → L3.DEFAULT | `[2, 1, 1]` | fast |
| Asia + Default + Default | L1.DEFAULT → L2.DEFAULT → L3.DEFAULT | `[3, 1, 1]` | bulk |

### Vector Comparison (Lexicographic)

Within an executor's queue, tasks are ordered by comparing their path vectors **lexicographically** (like dictionary sorting):

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

### Within-Bucket Ordering (sort-by)

When multiple tasks have the **same path vector** (same bucket), they're ordered by the `sort-by` field defined at the leaf node:

```yaml
- name: "L3.HIGH_VALUE"
  condition: "$req.amount > 100000"
  sort-by:
    field: $req.priority    # Sort by this field within this bucket
    direction: DESC         # Higher priority values first
  executor: "fast"
```

### Complete Priority Calculation

The final priority is determined by:

1. **Primary**: Executor routing (which executor)
2. **Secondary**: Path Vector (which bucket within executor's queue)
3. **Tertiary**: Sort-by value (within bucket)

```
Task → Policy Evaluation → Executor + PathVector + SortValue
```

### Design Tips

1. **Put highest priority conditions first** - They get lower indices (higher priority)
2. **Always end with `condition: "true"`** - Catch-all for unmatched tasks
3. **Use nested-levels for multi-dimensional priority** - Region → Tier → Amount
4. **Specify executor at leaf nodes** - Route tasks to appropriate TPS-limited executors
5. **Choose sort-by wisely**:
   - `$req.priority DESC` - User-provided priority
   - `$sys.submittedAt ASC` - FIFO within bucket
   - `$req.amount DESC` - Highest value first

---

## Architecture Design (Prioritization-First)

This design makes the prioritization engine reusable beyond the in-memory executor, while keeping the current thread-pool behavior as one adapter.

### Core Components

**1) Priority Engine (pure, no scheduling/execution)**  
Evaluates policy and produces `EvaluationResult` / `PriorityKey`.

**2) Priority Scheduler (multi-queue ordering)**  
Stores submitted items across multiple queues and orders by priority. No workers. Provides `submit()` (returns queue name), `getNext()`, and `getNext(queueName)`.

**3) Adapters (execution/transport)**  
Consume from the priority scheduler and either execute locally or forward to external systems.

### Package Organization

- `com.pool.core` — core engine types and context (`TaskContext`, `TaskContextFactory`)
- `com.pool.policy` — policy evaluation (`DefaultPolicyEngine`, `ExpressionPolicyEngine`)
- `com.pool.priority` — priority keys, vectors, calculators
- `com.pool.scheduler` — priority scheduler API and default implementation
- `com.pool.config` — configuration records (`PoolConfig`, `ExecutorSpec`, `QueueConfig`)
- `com.pool.config.expression` — expression parser components (`ExpressionTokenizer`, `ExpressionParser`, `ExpressionConfig`)
- `com.pool.condition` — condition evaluation interfaces and implementations
- `com.pool.variable` — variable resolution (`$req.*`, `$sys.*`)
- `com.pool.adapter.executor` — in-process thread pool executor
- `com.pool.adapter.spring` — Spring Boot auto-configuration

### Interface Sketch

- `PriorityEngine`
  - `EvaluationResult evaluate(TaskContext ctx)`
  - `void updateConfig(PoolConfig cfg)` (optional)
- `PriorityScheduler<T>`
  - `String submit(TaskContext ctx, T payload)` — returns queue name
  - `T getNext()` — from first non-empty queue
  - `T getNext(String queueName)` — from specific queue
  - `Optional<T> getNext(long timeout, TimeUnit unit)`
  - `Optional<T> getNext(String queueName, long timeout, TimeUnit unit)`
  - `int size()`, `int size(String queueName)`

### Example Flows

**A) In-memory execution (current behavior)**  
`submit` → priority scheduler → workers poll from their assigned queues

**B) Kafka/External queue relay**  
`submit` → priority scheduler → `getNext` → publish to Kafka/Redis/SQS, etc.

### Configuration Loading Flow

Configuration is loaded at application startup through a multi-stage pipeline:

```
application.properties → ConfigLoader → YAML Parser → Section Parsers → PoolConfig
```

**1. Spring Boot Integration**
```properties
# application.properties
pool.config-path=classpath:pool.yaml  # or file path
```

**2. Resource Resolution**
- `classpath:` prefix → loads from `src/main/resources/`
- No prefix → loads from filesystem

**3. YAML Parsing**
```java
ConfigLoader.load("classpath:pool.yaml")
  → SnakeYAML parses YAML into nested maps
  → Extract root config (supports `pool:` wrapper or root-level)
```

**4. Section Parsing**
- `adapters.executors` → `List<ExecutorSpec>` (TPS-based executors)
- `scheduler.queues` → `SchedulerConfig` (standalone scheduler)
- `priority-tree` → `List<PriorityNodeConfig>` with expression-based conditions
- `priority-strategy` → `StrategyConfig`

**5. Validation**
- Priority tree not empty (creates default if needed)
- Executors have valid parent references
- Leaf nodes specify `executor` (TPS mode) or `queue` (scheduler mode)

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
condition: "$req.amount == 1000"      # EQUALS
condition: "$req.amount != 1000"      # NOT_EQUALS  
condition: "$req.amount > 1000"       # GREATER_THAN
condition: "$req.amount < 1000"       # LESS_THAN
condition: "$req.amount >= 1000"      # GREATER_THAN_OR_EQUALS
condition: "$req.amount <= 1000"      # LESS_THAN_OR_EQUALS
```

### Collection
```yaml
condition: '$req.region IN ["US", "CA", "MX"]'
condition: '$req.region NOT IN ["EU", "ASIA"]'
```

### String
```yaml
condition: '$req.email REGEX ".*@company\\.com"'
condition: '$req.email STARTS_WITH "admin"'
condition: '$req.email ENDS_WITH "@company.com"'
condition: '$req.name CONTAINS "Corp"'
```

### Logical (Combine Conditions)
```yaml
condition: '$req.tier == "PLATINUM" AND $req.amount > 10000'
condition: '$req.tier == "VIP" OR $req.amount > 50000'
condition: 'NOT ($req.status == "CANCELLED")'
```

### Special
```yaml
condition: "true"                     # Catch-all, always matches
condition: "EXISTS($req.email)"       # Field exists
condition: "IS_NULL($req.email)"      # Field is null/missing
```

## Complex Expressions

Conditions support complex boolean expressions with parentheses:

```yaml
condition: '$req.tier == "VIP" AND ($req.channel == "SUPPORT" OR $req.priority >= 8)'
```

**Supported:**
- `AND`, `OR`, `NOT` with parentheses
- Comparisons: `==`, `!=`, `>`, `>=`, `<`, `<=`
- `IN` / `NOT IN` with lists: `$req.region IN ["US","CA"]`
- `REGEX`, `STARTS_WITH`, `ENDS_WITH`, `CONTAINS`, `EXISTS`, `IS_NULL`

All field references must use `$req.`, `$ctx.`, or `$sys.` prefix.

### Expression Parser Architecture

The expression parser is modular and cleanly separated:

```
Expression String → Tokenizer → Tokens → Parser → ConditionConfig Tree
```

**Package: `com.pool.config.expression`**

| Component | Responsibility |
|-----------|---------------|
| `ExpressionConfig` | Constants for keywords (AND, OR, IN), operators (==, !=, >), and prefixes |
| `TokenType` | Enum of all token types (IDENT, STRING, NUMBER, AND, OR, EQ, etc.) |
| `Token` | Immutable record: `(type, text, literal, position)` |
| `ExpressionTokenizer` | Converts string → List&lt;Token&gt; (lexical analysis) |
| `ExpressionParser` | Converts List&lt;Token&gt; → ConditionConfig tree (syntax analysis) |
| `ConditionExpressionParser` | Facade that orchestrates tokenizer + parser |

**Parsing Flow:**

1. **Tokenize**: `"$req.tier == \"VIP\" AND $req.priority > 8"` → `[FIELD($req.tier), EQ, STRING(VIP), AND, FIELD($req.priority), GT, NUMBER(8)]`
2. **Parse**: Recursive descent with precedence: `NOT` > `AND` > `OR` (parentheses override)
3. **Output**: ConditionConfig tree

**Example:**

```java
// Input
String expr = "$req.tier == \"PLATINUM\" AND ($req.amount > 1000 OR $req.urgent == true)";

// Parsing
ConditionConfig config = ConditionExpressionParser.parse(expr);

// Result tree:
// AND
// ├── EQUALS ($req.tier, "PLATINUM")
// └── OR
//     ├── GREATER_THAN ($req.amount, 1000)
//     └── EQUALS ($req.urgent, true)
```

**Benefits:**
- **Maintainable**: Each class has a single responsibility
- **Testable**: Components can be tested independently
- **Extensible**: Easy to add new operators or keywords via `ExpressionConfig`
- **Configurable**: All keywords/operators in one place

### Examples

**Expression Syntax (Recommended):**
```yaml
priority-tree:
  - name: "VIP_OR_HIGH_VALUE"
    condition: '$req.tier == "PLATINUM" OR $req.amount > 50000'
    executor: "fast"
```

**With Nested Levels:**
```yaml
priority-tree:
  - name: "VIP_CUSTOMERS"
    condition: '$req.tier == "PLATINUM"'
    nested-levels:
      - name: "HIGH_VALUE"
        condition: "$req.amount > 50000"
        executor: "fast"
      - name: "DEFAULT"
        condition: "true"
        executor: "standard"
```

## Example: E-Commerce Order Processing

```yaml
pool:
  name: "order-pool"
  version: "1.0"

  adapters:
    executors:
      # Root executor - total system capacity
      - id: "main"
        tps: 1000
        queue_capacity: 5000
        identifier_field: "$req.orderId"

      # Fast lane: VIP and high-value orders
      - id: "fast"
        parent: "main"
        tps: 500
        queue_capacity: 2000
        identifier_field: "$req.orderId"

      # Standard lane: Regular orders
      - id: "standard"
        parent: "main"
        tps: 300
        queue_capacity: 1500
        identifier_field: "$req.orderId"

      # Bulk lane: Low priority batch processing
      - id: "bulk"
        parent: "main"
        tps: 200
        queue_capacity: 1000
        identifier_field: "$req.orderId"

  priority-strategy:
    type: FIFO
    
  priority-tree:
    # VIP customers with large orders → fast
    - name: "VIP_LARGE"
      condition: '$req.customer.tier IN ["PLATINUM", "GOLD"] AND $req.order.total > 5000'
      sort-by:
        field: $req.order.total
        direction: DESC
      executor: "fast"

    # Express shipping → fast
    - name: "EXPRESS"
      condition: '$req.shipping.type == "EXPRESS"'
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      executor: "fast"

    # Standard orders → standard
    - name: "STANDARD"
      condition: '$req.shipping.type == "STANDARD"'
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      executor: "standard"

    # Everything else → bulk
    - name: "DEFAULT"
      condition: "true"
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      executor: "bulk"
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

### PriorityScheduler (Multi-Queue)

Use the in-memory priority scheduler to integrate with external systems (Kafka/Redis/etc.).

```java
PoolConfig config = ConfigLoader.load("classpath:pool.yaml");
PriorityScheduler<MyPayload> scheduler = new DefaultPriorityScheduler<>(config);

// Submit returns queue name
String queueName = scheduler.submit(ctx, payload);

// Pull from first non-empty queue
MyPayload next = scheduler.getNext();

// Pull from specific queue
MyPayload fastTask = scheduler.getNext("fast");

// Timed pull from specific queue
Optional<MyPayload> task = scheduler.getNext("fast", 5, TimeUnit.SECONDS);
```

#### Example: Relay to Kafka

```java
PoolConfig config = ConfigLoader.load("classpath:pool.yaml");
PriorityScheduler<OrderEvent> scheduler = new DefaultPriorityScheduler<>(config);

// Submit events as they arrive
String queue = scheduler.submit(ctx, orderEvent);

// Relay loop per queue
while (true) {
    OrderEvent event = scheduler.getNext("fast");
    
    ProducerRecord<String, OrderEvent> record = new ProducerRecord<>(
            "orders-fast",
            event.getOrderId(),
            event
    );
    kafkaProducer.send(record);
}
```

## Testing

Pool includes comprehensive unit tests covering all major functionality.

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=PoolApplicationTest

# Run with verbose output
mvn test -Dtest=PoolApplicationTest -X
```

### Test Coverage

| Test Class | Coverage |
|------------|----------|
| `PoolApplicationTest` | End-to-end pool functionality, routing, and task execution |
| `TpsPoolExecutorTest` | TPS-based executor, submission, and lifecycle |
| `TpsGateTest` | TPS rate limiting and sliding window |
| `ExecutorHierarchyTest` | Hierarchical executor relationships |
| `SlidingWindowCounterTest` | Sliding window counter accuracy |

### PoolApplicationTest Scenarios

The main test class validates all routing scenarios from `pool.yaml`:

**Region Routing:**
- `NORTH_AMERICA` + `PLATINUM` → `fast` executor
- `NORTH_AMERICA` + `GOLD` → `fast` executor
- `NORTH_AMERICA` + default tier → `bulk` executor
- `EUROPE` → `fast` executor
- Unknown regions → `bulk` executor (default)

**Customer Tier Routing:**
- `PLATINUM` with high-value transactions (>100k) → `fast`
- `PLATINUM` with low-value transactions → `fast`
- `GOLD` → `fast`
- `SILVER`, `STANDARD` → `bulk`

**Task Execution:**
- Callable and Runnable task submission
- Concurrent multi-region task submission
- Mixed-tier concurrent workloads
- Statistics tracking (submitted, executed, rejected)

**Error Handling:**
- Null context rejection
- Null task rejection
- Post-shutdown task rejection

**Lifecycle:**
- Graceful shutdown
- Immediate shutdown
- Await termination

### Writing New Tests

```java
@Test
void myCustomRoutingTest() {
    // Create context with JSON payload
    String json = """
        {
            "region": "NORTH_AMERICA",
            "customerTier": "PLATINUM",
            "transactionAmount": 150000
        }
        """;
    TaskContext ctx = TaskContextFactory.create(json, Map.of());
    
    // Evaluate routing
    EvaluationResult result = policyEngine.evaluate(ctx);
    
    // Verify executor assignment
    assertEquals("fast", result.getMatchedPath().executor());
    
    // Verify path traversal
    String path = result.getMatchedPath().toPathString();
    assertTrue(path.contains("L1.NORTH_AMERICA"));
    assertTrue(path.contains("L2.PLATINUM"));
}
```

## Requirements

- Java 21+
- Spring Boot 3.2+ (optional, for auto-configuration)

## License

MIT License - see [LICENSE](LICENSE)
