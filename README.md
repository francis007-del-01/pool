# Pool

A policy-driven executor framework with intelligent task prioritization for Java applications.

## Overview

Pool is a flexible task execution framework that allows you to define complex prioritization rules through a YAML-based configuration. Instead of simple FIFO queues, Pool uses a **priority tree** to dynamically route and order tasks based on request attributes, context, and system state.

## Features

- **Policy-Driven Prioritization** - Define multi-level priority rules in YAML
- **Dynamic Variable Resolution** - Access request payload, context, and system variables
- **Flexible Conditions** - Rich condition types for routing (equals, ranges, regex, logical operators)
- **Configurable Sorting** - Sort tasks within priority buckets by any field
- **Spring Boot Integration** - Auto-configuration support for Spring applications
- **Zero Code Changes** - Modify prioritization logic without redeploying

## Installation

### Maven

```xml
<dependency>
    <groupId>com.pool</groupId>
    <artifactId>pool</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

### 1. Create a configuration file (`pool.yaml`)

```yaml
pool:
  name: "my-pool"
  version: "1.0"

  executor:
    core-pool-size: 10
    max-pool-size: 50
    queue-capacity: 1000

  priority-strategy:
    type: FIFO

  priority-tree:
    - name: "HIGH_PRIORITY"
      condition:
        type: EQUALS
        field: $req.customerTier
        value: "PLATINUM"
      sort-by:
        field: $req.priority
        direction: DESC

    - name: "DEFAULT"
      condition:
        type: ALWAYS_TRUE
      sort-by:
        field: $sys.submittedAt
        direction: ASC
```

### 2. Submit tasks

```java
// Create task context from JSON payload and context map
String jsonPayload = """
    {
        "customerTier": "PLATINUM",
        "priority": 95,
        "orderId": "ORD-12345"
    }
    """;

Map<String, String> context = Map.of(
    "clientId", "mobile-app",
    "traceId", "abc-123"
);

TaskContext taskContext = TaskContextFactory.create(jsonPayload, context);

// Submit to executor
poolExecutor.submit(taskContext, () -> {
    // Your task logic here
    processOrder(taskContext);
});
```

## Configuration Reference

### Executor Settings

```yaml
executor:
  core-pool-size: 10          # Minimum threads
  max-pool-size: 50           # Maximum threads
  queue-capacity: 1000        # Task queue size
  keep-alive-seconds: 60      # Idle thread timeout
  thread-name-prefix: "pool-" # Thread naming
  allow-core-thread-timeout: true
```

### Priority Tree

The priority tree is a hierarchical structure where tasks are matched against conditions at each level. The first matching path determines the task's priority bucket.

```yaml
priority-tree:
  - name: "LEVEL_1_NODE"
    condition:
      type: EQUALS
      field: $req.region
      value: "US"
    nested-levels:
      - name: "LEVEL_2_NODE"
        condition:
          type: GREATER_THAN
          field: $req.amount
          value: 10000
        sort-by:
          field: $req.priority
          direction: DESC
```

## Variables

Pool supports three variable sources:

| Prefix | Source | Description |
|--------|--------|-------------|
| `$req.*` | Request Payload | Parsed from JSON (supports nested: `$req.customer.tier`) |
| `$ctx.*` | Context Map | Headers, metadata, client info |
| `$sys.*` | System | Auto-computed at submission time |

### System Variables

| Variable | Type | Description |
|----------|------|-------------|
| `$sys.taskId` | String | Unique task identifier |
| `$sys.submittedAt` | Long | Submission timestamp (epoch ms) |
| `$sys.time.now` | Long | Same as submittedAt |
| `$sys.correlationId` | String | Correlation ID (if provided) |

## Condition Types

### Comparison
- `EQUALS` - Exact match
- `NOT_EQUALS` - Not equal
- `GREATER_THAN` - Greater than value
- `GREATER_THAN_OR_EQUALS` - Greater than or equal
- `LESS_THAN` - Less than value
- `LESS_THAN_OR_EQUALS` - Less than or equal
- `BETWEEN` - Within range (use `value` and `value2`)

### Collection
- `IN` - Value in list (use `values: [...]`)
- `NOT_IN` - Value not in list
- `CONTAINS` - Collection contains value

### String
- `REGEX` - Matches pattern (use `pattern`)
- `STARTS_WITH` - String prefix match
- `ENDS_WITH` - String suffix match

### Existence
- `EXISTS` - Field is present
- `IS_NULL` - Field is null/missing

### Logical
- `AND` - All conditions must match
- `OR` - Any condition must match
- `NOT` - Negates a condition

### Special
- `ALWAYS_TRUE` - Always matches (catch-all)

## Examples

### Complex Condition with AND/OR

```yaml
condition:
  type: AND
  conditions:
    - type: EQUALS
      field: $req.region
      value: "US"
    - type: OR
      conditions:
        - type: EQUALS
          field: $req.tier
          value: "PLATINUM"
        - type: GREATER_THAN
          field: $req.amount
          value: 100000
```

### Range Condition

```yaml
condition:
  type: BETWEEN
  field: $req.amount
  value: 1000
  value2: 50000
```

### List Membership

```yaml
condition:
  type: IN
  field: $req.region
  values:
    - "US"
    - "CA"
    - "MX"
```

### Nested JSON Access

For a payload like:
```json
{
  "customer": {
    "tier": "PLATINUM",
    "account": {
      "balance": 50000
    }
  }
}
```

Access nested fields with dot notation:
```yaml
condition:
  type: EQUALS
  field: $req.customer.tier
  value: "PLATINUM"
```

## Spring Boot Integration

```java
@SpringBootApplication
@EnablePool
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

Configure in `application.properties`:
```properties
pool.config-path=classpath:pool.yaml
```

## Requirements

- Java 21+
- Spring Boot 3.2+ (optional, for auto-configuration)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
