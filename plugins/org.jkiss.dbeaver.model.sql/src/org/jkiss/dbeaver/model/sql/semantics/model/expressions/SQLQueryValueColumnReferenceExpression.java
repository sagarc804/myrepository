/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.sql.semantics.model.expressions;


import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.impl.struct.RelationalObjectType;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.semantics.*;
import org.jkiss.dbeaver.model.sql.semantics.context.*;
import org.jkiss.dbeaver.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import org.jkiss.dbeaver.model.sql.semantics.model.select.SQLQueryRowsTableDataModel;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryComplexName;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryRowsDataContext;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryRowsSourceContext;
import org.jkiss.dbeaver.model.stm.STMTreeNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.Pair;

import java.util.ArrayList;
import java.util.List;


/**
 * Describes column reference specified by column nae and optionally table name
 */
public class SQLQueryValueColumnReferenceExpression extends SQLQueryValueExpression {
    private final boolean rowRefAllowed;
    @Nullable
    private final SQLQueryQualifiedName tableName;
    @Nullable
    private final SQLQuerySymbolEntry columnName;
    @Nullable
    private SQLQueryResultColumn column = null;
    
    public SQLQueryValueColumnReferenceExpression(
        @NotNull STMTreeNode syntaxNode,
        boolean rowRefAllowed,
        @Nullable SQLQueryQualifiedName tableName,
        @Nullable SQLQuerySymbolEntry columnName
    ) {
        super(syntaxNode);
        this.rowRefAllowed = rowRefAllowed;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    @Nullable
    public SQLQueryQualifiedName getTableName() {
        return this.tableName;
    }

    @Nullable
    public SQLQuerySymbolEntry getColumnName() { return this.columnName; }

    @Nullable
    @Override
    public SQLQuerySymbol getColumnNameIfTrivialExpression() {
        return this.columnName == null ? null : this.columnName.getSymbol();
    }

    @Nullable
    @Override
    public SQLQueryResultColumn getColumnIfTrivialExpression() {
        return this.column;
    }

    /**
     * Propagate semantics context and establish relations through the query model for column definition
     */
    public static void propagateColumnDefinition(
        @NotNull SQLQuerySymbolEntry columnName,
        @Nullable SQLQueryResultColumn resultColumn,
        @NotNull SQLQueryRecognitionContext statistics,
        @Nullable SQLQuerySymbolOrigin columnNameOrigin
    ) {
        // TODO consider ambiguity
        if (resultColumn != null) {
            columnName.setDefinition(resultColumn.symbol.getDefinition());
        } else {
            columnName.getSymbol().setSymbolClass(SQLQuerySymbolClass.ERROR);
            statistics.appendError(columnName, "Column " + columnName.getName() + " not found");
        }
        columnName.setOrigin(columnNameOrigin);
    }

    @Override
    protected void propagateContextImpl(@NotNull SQLQueryDataContext context, @NotNull SQLQueryRecognitionContext statistics) {
        SQLQueryExprType type;
        SQLQueryResultColumn resultColumn;

        SQLQuerySymbolOrigin columnRefOrigin = new SQLQuerySymbolOrigin.ValueRefFromContext(context);
        if (this.tableName != null && this.tableName.isNotClassified()) {
            if (this.tableName.invalidPartsCount == 0) {
                SourceResolutionResult rr = context.resolveSource(statistics.getMonitor(), this.tableName.toListOfStrings());
                if (rr != null) {
                    this.tableName.setDefinition(rr, columnRefOrigin);
                    SQLQuerySymbolOrigin columnNameOrigin = new SQLQuerySymbolOrigin.ColumnRefFromReferencedContext(rr);
                    if (this.columnName != null) {
                        resultColumn = rr.source.getResultDataContext().resolveColumn(statistics.getMonitor(), this.columnName.getName());
                        if (resultColumn != null || !rr.source.getResultDataContext().hasUnresolvedSource()) {
                            propagateColumnDefinition(this.columnName, resultColumn, statistics, columnNameOrigin);
                        } else {
                            this.columnName.setOrigin(columnNameOrigin);
                        }
                        type = resultColumn != null ? resultColumn.type : SQLQueryExprType.UNKNOWN;
                    } else {
                        resultColumn = null;
                        type = SQLQueryExprType.UNKNOWN;
                        statistics.appendError(this.tableName.getSyntaxNode(), "Expected column name after the table reference");
                        if (this.tableName.endingPeriodNode != null) {
                            this.tableName.endingPeriodNode.setOrigin(columnNameOrigin);
                        }
                    }
                } else {
                    resultColumn = null; // try to treat it a member-access sequence starting with a tuple's column name
                    // TODO consider table-ref followed with a member-access sequence

                    List<SQLQuerySymbolEntry> fullName = new ArrayList<>(this.tableName.scopeName.size() + 2);
                    fullName.addAll(this.tableName.scopeName);
                    fullName.add(this.tableName.entityName);
                    fullName.add(this.columnName);

                    Pair<SQLQueryResultColumn, SQLQueryExprType> columnAndType = resolveColumn(context, statistics, fullName.get(0), true);
                    SQLQueryExprType memberType = columnAndType.getSecond();
                    if (memberType != null) {
                        SQLQuerySymbolOrigin memberOrigin = new SQLQuerySymbolOrigin.MemberOfType(memberType);
                        int i = 1;
                        for (; i < fullName.size() && memberType != null && fullName.get(i) != null; i++) {
                            memberType = SQLQueryValueMemberExpression.tryResolveMemberReference(statistics, memberType, fullName.get(i), memberOrigin);
                            memberOrigin = new SQLQuerySymbolOrigin.MemberOfType(memberType);
                        }
                        type = memberType != null ? memberType : SQLQueryExprType.UNKNOWN;
                    } else {
                        if (this.tableName.isNotClassified()) {
                            this.tableName.setSymbolClass(SQLQuerySymbolClass.ERROR);
                        }
                        type = SQLQueryExprType.UNKNOWN;
                        statistics.appendError(
                            this.tableName.entityName,
                            "Table or subquery " + this.tableName.toIdentifierString() + " not found"
                        );
                    }
                }
            } else {
                resultColumn = null;
                type = SQLQueryExprType.UNKNOWN;
                statistics.appendError(this.getSyntaxNode(), "Invalid column reference");
            }
        } else if (this.tableName == null && this.columnName != null && this.columnName.isNotClassified()) {
            Pair<SQLQueryResultColumn, SQLQueryExprType> columnAndType = resolveColumn(context, statistics, this.columnName, this.rowRefAllowed);
            resultColumn = columnAndType.getFirst();
            type = columnAndType.getSecond() != null ? columnAndType.getSecond() : SQLQueryExprType.UNKNOWN;
        } else {
            resultColumn = null;
            type = SQLQueryExprType.UNKNOWN;
        }
        this.column = resultColumn;
        this.type = type;
    }

    private static Pair<SQLQueryResultColumn, SQLQueryExprType> resolveColumn(@NotNull SQLQueryDataContext context, @NotNull SQLQueryRecognitionContext statistics, @NotNull SQLQuerySymbolEntry columnName, boolean rowRefAllowed) {
        SQLDialect dialect = context.getDialect();
        SQLQueryResultColumn resultColumn;
        SQLQueryExprType type;

        SQLQuerySymbolOrigin columnRefOrigin = new SQLQuerySymbolOrigin.ValueRefFromContext(context);
        // TODO consider resolution order ?
        SQLQueryResultPseudoColumn pseudoColumn = context.resolveGlobalPseudoColumn(statistics.getMonitor(), columnName.getName());
        if (pseudoColumn == null) {
            pseudoColumn = context.resolvePseudoColumn(statistics.getMonitor(), columnName.getName());
        }
        if (pseudoColumn != null) {
            resultColumn = null; // not a real column, so we don't need to propagate its source and don't have real entity attribute
            type = pseudoColumn.type;
            columnName.setDefinition(pseudoColumn);
            columnName.setOrigin(columnRefOrigin);
        } else {
            resultColumn = context.resolveColumn(statistics.getMonitor(), columnName.getName());

            SourceResolutionResult rowsSourceIfAllowed;
            SQLQuerySymbolDefinition rowsSourceDef;
            DBSObject dbObject;
            SQLQuerySymbolClass forcedClass = null;
            if (resultColumn == null) {
                rowsSourceIfAllowed = rowRefAllowed
                    ? context.resolveSource(statistics.getMonitor(), List.of(columnName.getName()))
                    : null;
                if (rowsSourceIfAllowed != null) {
                    rowsSourceDef = rowsSourceIfAllowed.aliasOrNull != null
                        ? rowsSourceIfAllowed.aliasOrNull.getDefinition()
                        : rowsSourceIfAllowed.source instanceof SQLQueryRowsTableDataModel tableModel && tableModel.getName() != null
                        ? tableModel.getName().entityName
                        : null;
                    dbObject = null;
                } else {
                    rowsSourceDef = null;
                    dbObject = context.findRealObject(
                        statistics.getMonitor(),
                        RelationalObjectType.TYPE_UNKNOWN,
                        List.of(columnName.getName())
                    );
                }

                if (rowsSourceDef == null && columnName.isNotClassified()) {
                    String rawString = columnName.getRawName();
                    if (dialect.isQuotedString(rawString)) {
                        forcedClass = SQLQuerySymbolClass.STRING;
                    } else {
                        forcedClass = SQLQueryModelRecognizer.tryFallbackSymbolForStringLiteral(dialect, columnName, false);
                    }
                }
            } else {
                rowsSourceDef = null; // TODO check actual priority between columnRef and tableRef
                dbObject = null;
                rowsSourceIfAllowed = null;
            }

            if (dbObject != null) {
                columnName.setDefinition(new SQLQuerySymbolByDbObjectDefinition(dbObject, SQLQuerySymbolClass.UNKNOWN));
                type = null;
            } else if (rowsSourceDef != null) {
                columnName.setDefinition(rowsSourceDef);
                type = SQLQueryExprType.forReferencedRow(columnName, rowsSourceIfAllowed);
            } else if (forcedClass != null) {
                columnName.getSymbol().setSymbolClass(forcedClass);
                type = forcedClass == SQLQuerySymbolClass.STRING ? SQLQueryExprType.STRING : null;
            } else {
                if (resultColumn != null || !context.hasUnresolvedSource()) {
                    propagateColumnDefinition(columnName, resultColumn, statistics, columnRefOrigin);
                }
                type = resultColumn != null ? resultColumn.type : null;
            }

            if (columnName.getOrigin() == null) {
                columnName.setOrigin(columnRefOrigin);
            }
        }

        return Pair.of(resultColumn, type);
    }

    @Override
    protected void resolveRowSourcesImpl(@NotNull SQLQueryRowsSourceContext context, @NotNull SQLQueryRecognitionContext statistics) {
    }

    @Override
    protected SQLQueryExprType resolveValueTypeImpl(
        @NotNull SQLQueryRowsDataContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        SQLQueryExprType type;
        SQLQueryResultColumn resultColumn;

        SQLQuerySymbolOrigin columnRefOrigin = new SQLQuerySymbolOrigin.RowsDataRef(context);
        if (this.tableName != null && this.tableName.isNotClassified()) {
            SQLQueryComplexName tableName = new SQLQueryComplexName(this.tableName);
            if (this.tableName.invalidPartsCount == 0) {
                SourceResolutionResult rr = context.getRowsSources().findReferencedSourceExact(tableName);
                if (rr != null) {
                    this.tableName.setDefinition(rr, columnRefOrigin);
                    SQLQuerySymbolOrigin columnNameOrigin = new SQLQuerySymbolOrigin.ColumnRefFromReferencedContext(rr);
                    if (this.columnName != null) {
                        resultColumn = rr.source.getRowsDataContext().resolveColumn(statistics.getMonitor(), this.columnName.getName());
                        if (resultColumn != null || !rr.source.getRowsDataContext().getRowsSources().hasUnresolvedSource()) {
                            propagateColumnDefinition(this.columnName, resultColumn, statistics, columnNameOrigin);
                        } else {
                            this.columnName.setOrigin(columnNameOrigin);
                        }
                        type = resultColumn != null ? resultColumn.type : SQLQueryExprType.UNKNOWN;
                    } else {
                        resultColumn = null;
                        type = SQLQueryExprType.UNKNOWN;
                        statistics.appendError(this.tableName.getSyntaxNode(), "Expected column name after the table reference");
                        if (this.tableName.endingPeriodNode != null) {
                            this.tableName.endingPeriodNode.setOrigin(columnNameOrigin);
                        }
                    }
                } else {
                    resultColumn = null; // try to treat it a member-access sequence starting with a tuple's column name
                    // TODO consider table-ref followed with a member-access sequence

                    List<SQLQuerySymbolEntry> fullName = new ArrayList<>(this.tableName.scopeName.size() + 2);
                    fullName.addAll(this.tableName.scopeName);
                    fullName.add(this.tableName.entityName);
                    fullName.add(this.columnName);

                    Pair<SQLQueryResultColumn, SQLQueryExprType> columnAndType = resolveColumn(context, statistics, fullName.get(0), true);
                    SQLQueryExprType memberType = columnAndType.getSecond();
                    if (memberType != null) {
                        SQLQuerySymbolOrigin memberOrigin = new SQLQuerySymbolOrigin.MemberOfType(memberType);
                        int i = 1;
                        for (; i < fullName.size() && memberType != null && fullName.get(i) != null; i++) {
                            memberType = SQLQueryValueMemberExpression.tryResolveMemberReference(statistics, memberType, fullName.get(i), memberOrigin);
                            memberOrigin = new SQLQuerySymbolOrigin.MemberOfType(memberType);
                        }
                        type = memberType != null ? memberType : SQLQueryExprType.UNKNOWN;
                    } else {
                        if (this.tableName.isNotClassified()) {
                            this.tableName.setSymbolClass(SQLQuerySymbolClass.ERROR);
                        }
                        type = SQLQueryExprType.UNKNOWN;
                        statistics.appendError(
                            this.tableName.entityName,
                            "Table or subquery " + this.tableName.toIdentifierString() + " not found"
                        );
                    }
                }
            } else {
                resultColumn = null;
                type = SQLQueryExprType.UNKNOWN;
                statistics.appendError(this.getSyntaxNode(), "Invalid column reference");
            }
        } else if (this.tableName == null && this.columnName != null && this.columnName.isNotClassified()) {
            Pair<SQLQueryResultColumn, SQLQueryExprType> columnAndType = resolveColumn(context, statistics, this.columnName, this.rowRefAllowed);
            resultColumn = columnAndType.getFirst();
            type = columnAndType.getSecond() != null ? columnAndType.getSecond() : SQLQueryExprType.UNKNOWN;
        } else {
            resultColumn = null;
            type = SQLQueryExprType.UNKNOWN;
        }
        this.column = resultColumn;
        return type;
    }

    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, @NotNull T arg) {
        return visitor.visitValueColumnRefExpr(this, arg);
    }
    
    @Override
    public String toString() {
        String columnName = this.columnName == null ? "<NULL>" : this.columnName.getName();
        String name = this.tableName == null
            ? columnName
            : this.tableName.toIdentifierString() + "." + columnName;
        String type = this.type == null ? "<NULL>" : this.type.toString();
        return "ColumnReference[" + name + ":" + type + "]";
    }

    private static Pair<SQLQueryResultColumn, SQLQueryExprType> resolveColumn(
        @NotNull SQLQueryRowsDataContext context,
        @NotNull SQLQueryRecognitionContext statistics,
        @NotNull SQLQuerySymbolEntry columnName,
        boolean rowRefAllowed
    ) {
        SQLDialect dialect = context.getConnection().dialect;
        SQLQueryResultColumn resultColumn;
        SQLQueryExprType type;

        SQLQuerySymbolOrigin columnRefOrigin = new SQLQuerySymbolOrigin.RowsDataRef(context);
        // TODO consider resolution order ?
        SQLQueryResultPseudoColumn pseudoColumn = context.getConnection().resolveGlobalPseudoColumn(columnName.getName());
        if (pseudoColumn == null) {
            pseudoColumn = context.resolvePseudoColumn(columnName.getName());
        }
        if (pseudoColumn != null) {
            resultColumn = null; // not a real column, so we don't need to propagate its source and don't have real entity attribute
            type = pseudoColumn.type;
            columnName.setDefinition(pseudoColumn);
            columnName.setOrigin(columnRefOrigin);
        } else {
            resultColumn = context.resolveColumn(statistics.getMonitor(), columnName.getName());

            SourceResolutionResult rowsSourceIfAllowed;
            SQLQuerySymbolDefinition rowsSourceDef;
            DBSObject dbObject;
            SQLQuerySymbolClass forcedClass = null;
            if (resultColumn == null) {
                rowsSourceIfAllowed = rowRefAllowed
                    ? context.getRowsSources().findReferencedSource(new SQLQueryComplexName(columnName.getName()))
                    : null;
                if (rowsSourceIfAllowed != null) {
                    rowsSourceDef = rowsSourceIfAllowed.aliasOrNull != null
                        ? rowsSourceIfAllowed.aliasOrNull.getDefinition()
                        : rowsSourceIfAllowed.source instanceof SQLQueryRowsTableDataModel tableModel && tableModel.getName() != null
                            ? tableModel.getName().entityName
                            : null;
                    dbObject = null;
                } else {
                    rowsSourceDef = null;
                    dbObject = context.getConnection().findRealObject(
                        statistics.getMonitor(),
                        RelationalObjectType.TYPE_UNKNOWN,
                        List.of(columnName.getName())
                    );
                }

                if (rowsSourceDef == null && columnName.isNotClassified()) {
                    String rawString = columnName.getRawName();
                    if (dialect.isQuotedString(rawString)) {
                        forcedClass = SQLQuerySymbolClass.STRING;
                    } else {
                        forcedClass = SQLQueryModelRecognizer.tryFallbackSymbolForStringLiteral(dialect, columnName, false);
                    }
                }
            } else {
                rowsSourceDef = null; // TODO check actual priority between columnRef and tableRef
                dbObject = null;
                rowsSourceIfAllowed = null;
            }

            if (dbObject != null) {
                columnName.setDefinition(new SQLQuerySymbolByDbObjectDefinition(dbObject, SQLQuerySymbolClass.UNKNOWN));
                type = null;
            } else if (rowsSourceDef != null) {
                columnName.setDefinition(rowsSourceDef);
                type = SQLQueryExprType.forReferencedRow(columnName, rowsSourceIfAllowed);
            } else if (forcedClass != null) {
                columnName.getSymbol().setSymbolClass(forcedClass);
                type = forcedClass == SQLQuerySymbolClass.STRING ? SQLQueryExprType.STRING : null;
            } else {
                if (resultColumn != null || !context.getRowsSources().hasUnresolvedSource()) {
                    propagateColumnDefinition(columnName, resultColumn, statistics, columnRefOrigin);
                }
                type = resultColumn != null ? resultColumn.type : null;
            }

            if (columnName.getOrigin() == null) {
                columnName.setOrigin(columnRefOrigin);
            }
        }

        return Pair.of(resultColumn, type);
    }
}