# Pool

A policy-driven prioritization library with an optional in-process executor adapter for Java applications.

## What is Pool?

Pool is a **pluggable prioritization engine** that evaluates tasks against a policy tree and orders them by priority. It can be used standalone (via `PriorityScheduler`) or through the optional in-process executor adapter.

Instead of simple FIFO queues, Pool uses a **priority tree** to dynamically route and order tasks based on request attributes, business rules, and system state.

## Why Pool?

- **No Code Changes for Priority Logic** - Change prioritization rules via YAML, no redeployment needed
- **Business-Driven Ordering** - Prioritize by customer tier, transaction amount, region, or any field
- **Multi-Queue Architecture** - Route tasks to different queues with independent worker pools
- **Executor Adapter** - Optional in-process thread-pool adapter for drop-in use
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

  # Queue definitions (shared by scheduler and executors)
  scheduler:
    queues:
      - name: "fast"
        index: 0          # Lower index = higher priority when polling
        capacity: 1000
      - name: "bulk"
        index: 1
        capacity: 500

  # Multiple executors - each references a queue and defines its worker pool
  adapters:
    executors:
      - core-pool-size: 10
        max-pool-size: 50
        keep-alive-seconds: 60
        thread-name-prefix: "fast-worker-"
        allow-core-thread-timeout: true
        queue: "fast"

      - core-pool-size: 5
        max-pool-size: 20
        keep-alive-seconds: 120
        thread-name-prefix: "bulk-worker-"
        allow-core-thread-timeout: true
        queue: "bulk"

  priority-strategy:
    type: FIFO

  priority-tree:
    # Platinum customers → fast queue
    - name: "PLATINUM"
      condition:
        type: EQUALS
        field: $req.customerTier
        value: "PLATINUM"
      sort-by:
        field: $req.priority
        direction: DESC
      queue: "fast"           # Route to fast queue

    # High-value transactions → fast queue
    - name: "HIGH_VALUE"
      condition:
        type: GREATER_THAN
        field: $req.amount
        value: 100000
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      queue: "fast"

    # Everything else → bulk queue
    - name: "DEFAULT"
      condition:
        type: ALWAYS_TRUE
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      queue: "bulk"           # Route to bulk queue
```

### 2. Initialize the Priority Scheduler (Recommended)

For standalone use (without executor adapter), you can configure queues directly:

```yaml
# pool-scheduler.yaml - standalone scheduler config
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
      condition:
        type: GREATER_THAN
        field: $req.priority
        value: 80
      queue: "fast"
    - name: "DEFAULT"
      condition:
        type: ALWAYS_TRUE
      queue: "bulk"
```

```java
import com.pool.config.ConfigLoader;
import com.pool.config.PoolConfig;
import com.pool.scheduler.DefaultPriorityScheduler;
import com.pool.scheduler.PriorityScheduler;

// Load configuration
PoolConfig config = ConfigLoader.load("classpath:pool-scheduler.yaml");

// Create priority scheduler (supports multiple queues)
PriorityScheduler<MyPayload> scheduler = new DefaultPriorityScheduler<>(config);
```

### 3. Submit Payloads (Prioritization First)

```java
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;

TaskContext ctx = TaskContextFactory.create(jsonPayload, contextMap);

// submit() returns the queue name the task was routed to
String queueName = scheduler.submit(ctx, payload);

// Pull from first non-empty queue (by index order)
MyPayload next = scheduler.getNext();

// Or pull from a specific queue
MyPayload fastTask = scheduler.getNext("fast");
```

### 4. Initialize the In-Process Executor (Adapter)

```java
import com.pool.config.ConfigLoader;
import com.pool.config.PoolConfig;
import com.pool.adapter.executor.PoolExecutor;
import com.pool.adapter.executor.DefaultPoolExecutor;

// Load configuration
PoolConfig config = ConfigLoader.load("classpath:pool.yaml");
// Or from file path: ConfigLoader.load("/etc/myapp/pool.yaml");

// Create executor (adapter, uses PriorityScheduler internally)
// Workers are automatically created per queue based on config
PoolExecutor executor = new DefaultPoolExecutor(config);
```

### 5. Submit Tasks (Adapter)

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

### 6. Shutdown

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

### Multi-Queue Executor Configuration

Path: `pool.adapters.executors[]`

Each executor references a queue by name and defines its worker pool:

| Property | Default | Description |
|----------|---------|-------------|
| `core-pool-size` | 10 | Minimum number of worker threads for this queue |
| `max-pool-size` | 50 | Maximum number of worker threads for this queue |
| `keep-alive-seconds` | 60 | Idle thread timeout before excess threads terminate |
| `thread-name-prefix` | "queue-name-worker-" | Thread naming prefix |
| `allow-core-thread-timeout` | true | Allow core threads to time out when idle |
| `queue` | required | Target queue name (must exist in `scheduler.queues`) |

### Queue Configuration (Scheduler)

Queues are defined under `scheduler` and used by both the scheduler and executor adapter:

Path: `pool.scheduler`

**Option 1: Single queue with capacity**

```yaml
pool:
  name: "my-scheduler"
  
  scheduler:
    queue-capacity: 1000    # Single "default" queue with this capacity
  
  priority-tree:
    - name: "DEFAULT"
      condition:
        type: ALWAYS_TRUE
      queue: "default"
```

**Option 2: Multiple named queues**

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
      condition:
        type: EQUALS
        field: $req.priority
        value: "HIGH"
      queue: "fast"
    
    - name: "DEFAULT"
      condition:
        type: ALWAYS_TRUE
      queue: "bulk"
```

| Property | Default | Description |
|----------|---------|-------------|
| `queue-capacity` | 1000 | Capacity for single default queue |
| `queues[].name` | required | Queue name |
| `queues[].index` | auto | Priority index (lower = higher priority) |
| `queues[].capacity` | 1000 | Maximum queue capacity |

### Priority Strategy

Pool currently supports `FIFO` only. Other types (`TIME_BASED`, `BUCKET_BASED`) are reserved for future implementations.

### Priority Tree

The priority tree is evaluated top-to-bottom. First matching node wins. Leaf nodes specify the target `queue` for routing.

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
        queue: "fast"         # Route matching tasks to "fast" queue
```

---

## Multi-Queue Architecture

Pool supports **multiple queues**, each with its own worker pool. This enables:

- **Workload Isolation** - High-priority tasks get dedicated workers
- **Resource Control** - Different thread pool sizes per queue
- **Queue-Based Routing** - Policy tree routes tasks to specific queues

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
│   │                 │  Determines: priority key + target queue          │
│   └────────┬────────┘                                                   │
│            │                                                            │
│            │  returns queue name                                        │
│            ▼                                                            │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                   Priority Scheduler                            │   │
│   │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │   │
│   │   │ Queue: fast  │    │ Queue: bulk  │    │ Queue: ...   │      │   │
│   │   │ index: 0     │    │ index: 1     │    │ index: N     │      │   │
│   │   │ capacity:1000│    │ capacity:500 │    │              │      │   │
│   │   └──────┬───────┘    └──────┬───────┘    └──────┬───────┘      │   │
│   └──────────│───────────────────│───────────────────│──────────────┘   │
│              │                   │                   │                  │
│              ▼                   ▼                   ▼                  │
│   ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐        │
│   │ Workers (fast)   │ │ Workers (bulk)   │ │ Workers (...)    │        │
│   │ core: 10         │ │ core: 5          │ │                  │        │
│   │ max: 50          │ │ max: 20          │ │                  │        │
│   │                  │ │                  │ │                  │        │
│   │ getNext("fast")  │ │ getNext("bulk")  │ │                  │        │
│   └──────────────────┘ └──────────────────┘ └──────────────────┘        │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Concepts

1. **Queue Routing**: Leaf nodes in the priority tree specify a `queue` field. Tasks matching that path are routed to that queue.

2. **Per-Queue Workers**: Each queue has its own worker pool with independent `core-pool-size` and `max-pool-size`.

3. **Queue Priority**: Queues have an `index` field. When calling `getNext()` without a queue name, queues are checked in index order (lowest first).

4. **Bounded Queues**: Each queue has a `capacity`. When full, submissions are rejected with `TaskRejectedException`.

### Example: Fast vs Bulk Processing

```yaml
scheduler:
  queues:
    - name: "fast"
      index: 0
      capacity: 1000
    - name: "bulk"
      index: 1
      capacity: 500

adapters:
  executors:
    # Fast queue: High priority, more workers, quick turnaround
    - core-pool-size: 10
      max-pool-size: 50
      keep-alive-seconds: 60
      queue: "fast"

    # Bulk queue: Lower priority, fewer workers, batch processing
    - core-pool-size: 5
      max-pool-size: 20
      keep-alive-seconds: 120
      queue: "bulk"

priority-tree:
  - name: "PLATINUM_CUSTOMERS"
    condition:
      type: EQUALS
      field: $req.tier
      value: "PLATINUM"
    queue: "fast"           # VIPs go to fast queue

  - name: "HIGH_VALUE"
    condition:
      type: GREATER_THAN
      field: $req.amount
      value: 50000
    queue: "fast"           # High-value goes to fast queue

  - name: "DEFAULT"
    condition:
      type: ALWAYS_TRUE
    queue: "bulk"           # Everything else goes to bulk queue
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
            queue: "fast"
```

**Example**: A task with `{region: "NORTH_AMERICA", customerTier: "PLATINUM", amount: 500000}` would match:
```
Path: L1.NORTH_AMERICA → L2.PLATINUM → L3.HIGH_VALUE → Queue: fast
```

### The Path Vector

Each matched path is converted to a **Path Vector** - an array of indices representing which branch was taken at each level:

| Task | Matched Path | Path Vector | Queue |
|------|--------------|-------------|-------|
| NA + Platinum + High Value | L1.NORTH_AMERICA → L2.PLATINUM → L3.HIGH_VALUE | `[1, 1, 1]` | fast |
| NA + Platinum + Default | L1.NORTH_AMERICA → L2.PLATINUM → L3.DEFAULT | `[1, 1, 2]` | fast |
| NA + Gold + Default | L1.NORTH_AMERICA → L2.GOLD → L3.DEFAULT | `[1, 2, 1]` | fast |
| Europe + Default + Default | L1.EUROPE → L2.DEFAULT → L3.DEFAULT | `[2, 1, 1]` | fast |
| Asia + Default + Default | L1.DEFAULT → L2.DEFAULT → L3.DEFAULT | `[3, 1, 1]` | bulk |

### Vector Comparison (Lexicographic)

Within a queue, tasks are ordered by comparing their path vectors **lexicographically** (like dictionary sorting):

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
  condition:
    type: GREATER_THAN
    field: $req.amount
    value: 100000
  sort-by:
    field: $req.priority    # Sort by this field within this bucket
    direction: DESC         # Higher priority values first
  queue: "fast"
```

### Complete Priority Calculation

The final priority is determined by:

1. **Primary**: Queue routing (which queue)
2. **Secondary**: Path Vector (which bucket within queue)
3. **Tertiary**: Sort-by value (within bucket)

```
Task → Policy Evaluation → Queue + PathVector + SortValue
```

### Design Tips

1. **Put highest priority conditions first** - They get lower indices (higher priority)
2. **Always end with ALWAYS_TRUE** - Catch-all for unmatched tasks
3. **Use nested-levels for multi-dimensional priority** - Region → Tier → Amount
4. **Specify queue at leaf nodes** - Route tasks to appropriate worker pools
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
- `com.pool.policy` — policy evaluation (`DefaultPolicyEngine`)
- `com.pool.priority` — priority keys, vectors, calculators
- `com.pool.scheduler` — priority scheduler API and default implementation
- `com.pool.config` — configuration records (`PoolConfig`, `ExecutorSpec`, `QueueConfig`)
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

  scheduler:
    queues:
      - name: "fast"
        index: 0
        capacity: 1000
      - name: "standard"
        index: 1
        capacity: 2000
      - name: "bulk"
        index: 2
        capacity: 5000

  adapters:
    executors:
      # Fast lane: VIP and high-value orders
      - core-pool-size: 20
        max-pool-size: 100
        queue: "fast"

      # Standard lane: Regular orders
      - core-pool-size: 10
        max-pool-size: 50
        queue: "standard"

      # Bulk lane: Low priority batch processing
      - core-pool-size: 5
        max-pool-size: 20
        queue: "bulk"
    
  priority-tree:
    # VIP customers with large orders → fast
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
      queue: "fast"

    # Express shipping → fast
    - name: "EXPRESS"
      condition:
        type: EQUALS
        field: $req.shipping.type
        value: "EXPRESS"
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      queue: "fast"

    # Standard orders → standard
    - name: "STANDARD"
      condition:
        type: EQUALS
        field: $req.shipping.type
        value: "STANDARD"
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      queue: "standard"

    # Everything else → bulk
    - name: "DEFAULT"
      condition:
        type: ALWAYS_TRUE
      sort-by:
        field: $sys.submittedAt
        direction: ASC
      queue: "bulk"
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

## Requirements

- Java 21+
- Spring Boot 3.2+ (optional, for auto-configuration)

## License

MIT License - see [LICENSE](LICENSE)
