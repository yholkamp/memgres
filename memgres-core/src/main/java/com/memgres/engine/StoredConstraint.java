package com.memgres.engine;

import com.memgres.engine.util.Cols;

import com.memgres.engine.parser.ast.Expression;

import java.util.List;

/**
 * Runtime representation of a table constraint (PK, UNIQUE, CHECK, FK).
 */
public class StoredConstraint {

    public enum Type { PRIMARY_KEY, UNIQUE, CHECK, FOREIGN_KEY, EXCLUDE }
    public enum FkAction { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT }

    /** An element of an EXCLUDE constraint: column + operator. */
        public static final class ExcludeElement {
        public final String column;
        public final String operator;

        public ExcludeElement(String column, String operator) {
            this.column = column;
            this.operator = operator;
        }

        public String column() { return column; }
        public String operator() { return operator; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ExcludeElement that = (ExcludeElement) o;
            return java.util.Objects.equals(column, that.column)
                && java.util.Objects.equals(operator, that.operator);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(column, operator);
        }

        @Override
        public String toString() {
            return "ExcludeElement[column=" + column + ", " + "operator=" + operator + "]";
        }
    }
    private List<ExcludeElement> excludeElements;

    private String name;
    private final Type type;
    private final List<String> columns;
    private final Expression checkExpr;
    private final String referencesTable;
    private String referencesSchema; // schema of the referenced table (null = resolve via search_path)
    private final List<String> referencesColumns;
    private final FkAction onDelete;
    private final FkAction onUpdate;
    private boolean nullsNotDistinct;
    private boolean deferrable;
    private boolean initiallyDeferred;
    private boolean notEnforced; // PG 18: NOT ENFORCED constraints are stored but not validated
    private boolean noInherit; // CHECK ... NO INHERIT: constraint not inherited by child tables
    private boolean convalidated = true; // pg_constraint.convalidated: false when added with NOT VALID
    private boolean fromIndex; // true if this constraint was created via CREATE UNIQUE INDEX (not ADD CONSTRAINT)
    private boolean promotedFromIndex; // true if created via ADD CONSTRAINT ... UNIQUE USING INDEX
    private String matchType; // FK match type: null/"SIMPLE"/"FULL"/"PARTIAL"
    private Expression whereExpr; // partial index predicate
    private List<Expression> expressionColumns; // parsed expressions for expression-based index columns
    private List<String> onDeleteSetNullColumns; // FK SET NULL column list (subset of FK columns to null)
    private List<String> onUpdateSetNullColumns; // FK SET NULL column list for ON UPDATE

    public StoredConstraint(String name, Type type, List<String> columns,
                            Expression checkExpr,
                            String referencesTable, List<String> referencesColumns,
                            FkAction onDelete, FkAction onUpdate) {
        this.name = name;
        this.type = type;
        this.columns = columns != null ? Cols.listCopyOf(columns) : Cols.listOf();
        this.checkExpr = checkExpr;
        this.referencesTable = referencesTable;
        this.referencesColumns = referencesColumns != null ? Cols.listCopyOf(referencesColumns) : Cols.listOf();
        this.onDelete = onDelete != null ? onDelete : FkAction.NO_ACTION;
        this.onUpdate = onUpdate != null ? onUpdate : FkAction.NO_ACTION;
    }

    public static StoredConstraint primaryKey(String name, List<String> columns) {
        return new StoredConstraint(name, Type.PRIMARY_KEY, columns, null, null, null, null, null);
    }

    public static StoredConstraint unique(String name, List<String> columns) {
        return new StoredConstraint(name, Type.UNIQUE, columns, null, null, null, null, null);
    }

    public static StoredConstraint check(String name, Expression checkExpr) {
        return new StoredConstraint(name, Type.CHECK, null, checkExpr, null, null, null, null);
    }

    public static StoredConstraint foreignKey(String name, List<String> columns,
                                              String refTable, List<String> refColumns,
                                              FkAction onDelete, FkAction onUpdate) {
        return new StoredConstraint(name, Type.FOREIGN_KEY, columns, null, refTable, refColumns, onDelete, onUpdate);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Type getType() { return type; }
    public List<String> getColumns() { return columns; }
    public Expression getCheckExpr() { return checkExpr; }
    public String getReferencesTable() { return referencesTable; }
    public String getReferencesSchema() { return referencesSchema; }
    public void setReferencesSchema(String schema) { this.referencesSchema = schema; }
    public List<String> getReferencesColumns() { return referencesColumns; }
    public FkAction getOnDelete() { return onDelete; }
    public FkAction getOnUpdate() { return onUpdate; }
    public boolean isNullsNotDistinct() { return nullsNotDistinct; }
    public void setNullsNotDistinct(boolean nullsNotDistinct) { this.nullsNotDistinct = nullsNotDistinct; }
    public List<ExcludeElement> getExcludeElements() { return excludeElements; }
    public void setExcludeElements(List<ExcludeElement> elements) { this.excludeElements = elements; }
    public Expression getWhereExpr() { return whereExpr; }
    public void setWhereExpr(Expression whereExpr) { this.whereExpr = whereExpr; }
    public List<Expression> getExpressionColumns() { return expressionColumns; }
    public void setExpressionColumns(List<Expression> expressionColumns) { this.expressionColumns = expressionColumns; }
    public boolean isDeferrable() { return deferrable; }
    public void setDeferrable(boolean deferrable) { this.deferrable = deferrable; }
    public boolean isInitiallyDeferred() { return initiallyDeferred; }
    public void setInitiallyDeferred(boolean initiallyDeferred) { this.initiallyDeferred = initiallyDeferred; }
    public boolean isNotEnforced() { return notEnforced; }
    public void setNotEnforced(boolean notEnforced) { this.notEnforced = notEnforced; }
    public boolean isNoInherit() { return noInherit; }
    public void setNoInherit(boolean noInherit) { this.noInherit = noInherit; }
    public boolean isFromIndex() { return fromIndex; }
    public void setFromIndex(boolean fromIndex) { this.fromIndex = fromIndex; }
    public boolean isPromotedFromIndex() { return promotedFromIndex; }
    public void setPromotedFromIndex(boolean promotedFromIndex) { this.promotedFromIndex = promotedFromIndex; }
    public boolean isConvalidated() { return convalidated; }
    public void setConvalidated(boolean convalidated) { this.convalidated = convalidated; }
    public String getMatchType() { return matchType; }
    public void setMatchType(String matchType) { this.matchType = matchType; }
    public List<String> getOnDeleteSetNullColumns() { return onDeleteSetNullColumns; }
    public void setOnDeleteSetNullColumns(List<String> cols) { this.onDeleteSetNullColumns = cols; }
    public List<String> getOnUpdateSetNullColumns() { return onUpdateSetNullColumns; }
    public void setOnUpdateSetNullColumns(List<String> cols) { this.onUpdateSetNullColumns = cols; }

    /** Returns true if this constraint should be deferred (checked at commit time). */
    public boolean isCurrentlyDeferred() {
        return deferrable && initiallyDeferred;
    }

    /**
     * Creates an independent copy of this constraint for a partition that inherits it from
     * a parent (or ancestor) table. Row storage for a partitioned table lives entirely on the
     * leaf partitions, so each partition must enforce its own PK/UNIQUE constraints via its own
     * {@link TableIndex} rather than sharing the parent's {@code StoredConstraint} instance:
     * that instance is mutable (see {@code setConvalidated}, {@code setNotEnforced}, etc.), and
     * operations like {@code ALTER TABLE ... VALIDATE CONSTRAINT} mutate whichever instance they
     * find by name — sharing it would let a change made through one table silently leak to
     * every sibling that happens to reference the same object.
     * <p>
     * Mirrors PostgreSQL, which gives each partition's inherited constraint its own
     * auto-generated, partition-scoped name (e.g. {@code <partition>_pkey}) rather than reusing
     * the parent's constraint name — even though memgres additionally namespaces constraints
     * and their backing indexes per-{@link Table} instance, so a bare name collision across
     * tables would not by itself cause incorrect lookups.
     *
     * @param partitionTableName the name of the partition the copy will be attached to
     */
    public StoredConstraint copyForPartition(String partitionTableName) {
        String newName = name;
        if (type == Type.PRIMARY_KEY) {
            newName = partitionTableName + "_pkey";
        } else if (type == Type.UNIQUE) {
            newName = partitionTableName + "_" + String.join("_", columns) + "_key";
        }
        StoredConstraint copy = new StoredConstraint(newName, type, columns, checkExpr,
                referencesTable, referencesColumns, onDelete, onUpdate);
        copy.referencesSchema = referencesSchema;
        copy.excludeElements = excludeElements;
        copy.nullsNotDistinct = nullsNotDistinct;
        copy.deferrable = deferrable;
        copy.initiallyDeferred = initiallyDeferred;
        copy.notEnforced = notEnforced;
        copy.noInherit = noInherit;
        copy.convalidated = convalidated;
        copy.fromIndex = fromIndex;
        copy.promotedFromIndex = promotedFromIndex;
        copy.matchType = matchType;
        copy.whereExpr = whereExpr;
        copy.expressionColumns = expressionColumns;
        copy.onDeleteSetNullColumns = onDeleteSetNullColumns;
        copy.onUpdateSetNullColumns = onUpdateSetNullColumns;
        return copy;
    }

    public static FkAction parseFkAction(String action) {
        if (action == null) return FkAction.NO_ACTION;
        // Strip column list suffix (e.g., "SET NULL:a,b" -> "SET NULL")
        String base = action.contains(":") ? action.substring(0, action.indexOf(':')) : action;
        switch (base.toUpperCase().replace(" ", "_")) {
            case "CASCADE":
                return FkAction.CASCADE;
            case "SET_NULL":
                return FkAction.SET_NULL;
            case "SET_DEFAULT":
                return FkAction.SET_DEFAULT;
            case "RESTRICT":
                return FkAction.RESTRICT;
            default:
                return FkAction.NO_ACTION;
        }
    }

    /** Extract SET NULL column list from action string like "SET NULL:a,b". Returns null if no list. */
    public static List<String> parseSetNullColumns(String action) {
        if (action == null || !action.contains(":")) return null;
        String colPart = action.substring(action.indexOf(':') + 1);
        if (colPart.isEmpty()) return null;
        return java.util.Arrays.asList(colPart.split(","));
    }
}
