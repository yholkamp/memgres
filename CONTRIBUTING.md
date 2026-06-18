# Contributing to Memgres

## Overview

Memgres is an in-memory PostgreSQL 18-compatible database for Java testing. Contributions should maintain PostgreSQL compatibility and follow the patterns established in the codebase.

## Branch Naming

Use descriptive, kebab-case branch names with a category prefix:

```
fix/excluded-pseudo-table
feature/advisory-locks
test/on-conflict-coverage
```

## Pull Request Guidelines

### Generalize Fixes

When you find a specific bug, investigate whether related or conceptually similar issues exist. For example:

- A parsing bug in `ALTER SEQUENCE RENAME TO` likely means other `ALTER ... RENAME TO` variants should be checked too.
- A type coercion bug for one date format probably means other date input formats have the same gap.
- An expression evaluation bug for one expression type (e.g., `CastExpr`) likely affects other expression types in the same code path.

Fix the class of problem, not just the single reported instance.

### Include Tests

Every change must include:

1. **Unit tests** in `memgres-core/src/test/java/com/memgres/` that verify the fix or feature through JDBC.
2. **SQL verification file** in `memgres-core/src/test/resources/feature-comparison/` (see format below) when the change affects SQL behavior visible to users.

### Dependencies

We are very conservative about adding Maven dependencies. The core module (`memgres-core`) intentionally has minimal dependencies (Netty for networking, SLF4J for logging). Do not add dependencies without discussion. If something can be implemented in a few dozen lines, prefer that over pulling in a library.

### Test Suite

Run the full test suite before submitting:

```bash
mvn clean verify
```

The suite has 13,000+ tests and should have 0 failures. Skipped tests are acceptable (they cover infrastructure-dependent features like `pg_stat_statements`).

## SQL Verification Files

SQL verification files compare Memgres behavior against real PostgreSQL 18. They live in:

```
memgres-core/src/test/resources/feature-comparison/
```

### Format

Annotations come **before** the SQL statement they apply to. The parser stores them as a pending expectation that attaches to the next SQL statement encountered.

**Expected result set:**

```sql
-- begin-expected
-- columns: name | age
-- row: Alice, 30
-- row: Bob, 25
-- end-expected
SELECT name, age FROM users ORDER BY name;
```

- `-- columns:` lists column names, separated by `|` or `,`
- `-- row:` lists values for one row, separated by `,`
- Column separator in `-- columns:` can be `|` (preferred) or `,`

**Expected error:**

```sql
-- begin-expected-error
-- sqlstate: 42P01
-- message-like: relation "nonexistent" does not exist
-- end-expected-error
SELECT * FROM nonexistent;
```

- `-- sqlstate:` is the PostgreSQL error code
- `-- message-like:` is a substring match against the error message

**Unannotated statements** (setup/teardown) have no expectation block. They run but aren't compared:

```sql
-- setup
CREATE TABLE t (id int PRIMARY KEY, v text);
INSERT INTO t VALUES (1, 'a');

-- stmt 1: description of what this tests
INSERT INTO t VALUES (1, 'b') ON CONFLICT (id) DO UPDATE SET v = EXCLUDED.v;

-- begin-expected
-- columns: v
-- row: b
-- end-expected
SELECT v FROM t WHERE id = 1;

-- cleanup
DROP TABLE t;
```

Section comments like `-- stmt 1:` and `-- setup` are purely for readability. The parser ignores all comment lines.

### Running Verification

```bash
# Run Memgres-side verification (no PG needed)
mvn test -pl memgres-core -Dtest="SqlVerifyTest"

# Generate full PG vs Memgres comparison report (requires PG 18 on localhost:5432)
mvn -pl memgres-core exec:java \
  -Dexec.mainClass="com.memgres.sqlverify.FeatureComparisonReport" \
  -Dexec.classpathScope=test
```

The report is written to `feature-comparison/differences.md`. The goal is 0 PG vs Memgres differences and 0 annotation mismatches.

## Unit Test Patterns

Tests use JUnit 5 with direct JDBC against a Memgres instance:

```java
package com.memgres.feature;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class MyFeatureTest {

    private static Memgres memgres;
    private static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(memgres.getJdbcUrl(), memgres.getUser(), memgres.getPassword());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (memgres != null) memgres.close();
    }

    @Test
    void descriptive_test_name() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE t (id int PRIMARY KEY, v text)");
            s.execute("INSERT INTO t VALUES (1, 'hello')");
            ResultSet rs = s.executeQuery("SELECT v FROM t WHERE id = 1");
            assertTrue(rs.next());
            assertEquals("hello", rs.getString(1));
            s.execute("DROP TABLE t");
        }
    }
}
```

Key conventions:
- `port(0)` for auto-assigned port
- `@BeforeAll`/`@AfterAll` with static Memgres + Connection (shared across tests in class)
- `try (Statement s = ...)` for auto-closing
- Each test creates and drops its own tables to avoid inter-test coupling
- Test method names use `snake_case` describing the scenario

## Code Architecture

### Package Structure

| Package | Purpose |
|---------|---------|
| `com.memgres.core` | Public API (`Memgres`, `Memgres.Builder`) |
| `com.memgres.engine` | SQL engine: execution, types, functions |
| `com.memgres.engine.parser` | SQL parser and DDL parsing |
| `com.memgres.engine.parser.ast` | AST node classes (immutable records) |
| `com.memgres.engine.plpgsql` | PL/pgSQL execution engine |
| `com.memgres.pgwire` | PostgreSQL wire protocol (v3) |

### Common Patterns

**Expression evaluation** (`ExprEvaluator`): uses `instanceof` dispatch over AST node types. When adding support for a new expression context (like EXCLUDED), prefer registering data in `RowContext` so the standard evaluator handles it, rather than writing a parallel evaluator.

**Function evaluation** (`FunctionEvaluator`): delegates to specialized classes (`StringFunctions`, `MathFunctions`, `DateTimeFunctions`, etc.). Returns `NOT_HANDLED` sentinel when a function isn't recognized, allowing fallback to other resolution paths.

**Parser** (`DdlParser`): delegates to specialized parsers (`DdlTableParser`, `DdlFunctionParser`, `DdlIndexParser`, etc.). Entry points follow the pattern `parse{StatementType}()` returning AST nodes.

**Type coercion** (`TypeCoercion`): static methods like `toLocalDate()`, `toOffsetDateTime()` with fallback chains that try multiple formats. New format support goes here.

**RowContext**: the execution context for column resolution. Supports multiple table bindings for JOINs, and virtual tables (OLD/NEW for triggers, EXCLUDED for ON CONFLICT). Use `setUsingColumns()` to suppress ambiguity when the same table appears under multiple aliases.

### Adding New SQL Features

Typical flow for a new SQL feature:

1. **Parser** — Add AST node in `parser/ast/`, add parsing in the appropriate parser class
2. **Executor** — Add execution logic in the appropriate executor (`DmlExecutor`, `DdlObjectExecutor`, `SelectExecutor`, etc.)
3. **Type system** — If new types are involved, update `TypeCoercion` and `CastEvaluator`
4. **Catalog** — If the feature should appear in `pg_catalog` or `information_schema`, update the relevant catalog builder
5. **Tests** — Unit tests + SQL verification file

## Java Version

Production code targets **Java 8**. Test code compiles with **Java 17+**. Do not use Java 9+ APIs in production code (`memgres-core/src/main/java`).
