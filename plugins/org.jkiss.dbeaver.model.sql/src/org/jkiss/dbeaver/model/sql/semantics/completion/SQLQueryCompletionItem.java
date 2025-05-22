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
package org.jkiss.dbeaver.model.sql.semantics.completion;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.sql.semantics.SQLQuerySymbol;
import org.jkiss.dbeaver.model.sql.semantics.SQLQuerySymbolClass;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryExprType;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryResultColumn;
import org.jkiss.dbeaver.model.sql.semantics.context.SourceResolutionResult;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSStructContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedure;

import java.util.LinkedList;
import java.util.List;

public abstract class SQLQueryCompletionItem {

    private final int score;

    @NotNull
    private final SQLQueryWordEntry filterKey;
    
    private SQLQueryCompletionItem(int score, @NotNull SQLQueryWordEntry filterKey) {
        this.score = score;
        this.filterKey = filterKey;
    }

    public int getScore() {
        return this.score;
    }

    @NotNull
    public SQLQueryWordEntry getFilterInfo() {
        return this.filterKey;
    }

    @NotNull
    public abstract SQLQueryCompletionItemKind getKind();

    @Nullable
    public DBSObject getObject() {
        return null;
    }

    public final <R> R apply(SQLQueryCompletionItemVisitor<R> visitor) {
        return this.applyImpl(visitor);
    }

    protected abstract <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor);

    /**
     * Prepare completion item for reserved word
     */
    @NotNull
    public static SQLQueryCompletionItem forReservedWord(int score, @NotNull SQLQueryWordEntry filterKey, @NotNull String text) {
        return new SQLReservedWordCompletionItem(score, filterKey, text);
    }

    /**
     * Build completion item for columns expansion
     */
    @NotNull
    public static SQLQueryCompletionItem forSpecialText(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @NotNull String text,
        @Nullable String description
    ) {
        return new SQLSpecialTextCompletionItem(score, filterKey, text, description);
    }

    @NotNull
    public static SQLQueryCompletionItem forRowsSourceAlias(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @NotNull SQLQuerySymbol aliasSymbol,
        @NotNull SourceResolutionResult source
    ) {
        return new SQLRowsSourceAliasCompletionItem(score, filterKey, aliasSymbol, source);
    }

    @NotNull
    public static SQLQueryCompletionItem forRealTable(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @Nullable ContextObjectInfo resolvedContext,
        @NotNull DBSEntity table, boolean isUsed
    ) {
        return new SQLTableNameCompletionItem(score, filterKey, resolvedContext, table, isUsed);
    }

    @NotNull
    public static SQLColumnNameCompletionItem forSubsetColumn(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @NotNull SQLQueryResultColumn columnInfo,
        @Nullable SourceResolutionResult sourceInfo,
        boolean absolute
    ) {
        return new SQLColumnNameCompletionItem(score, filterKey, columnInfo, sourceInfo, absolute);
    }

    @NotNull
    public static SQLQueryCompletionItem forDbObject(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @Nullable ContextObjectInfo resolvedContext,
        @NotNull DBSObject object
    ) {
        return new SQLDbNamedObjectCompletionItem(score, filterKey, resolvedContext, object, SQLQueryCompletionItemKind.UNKNOWN);
    }


    @NotNull
    public static SQLQueryCompletionItem forDbCatalogObject(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @Nullable ContextObjectInfo resolvedContext,
        @NotNull DBSObject object
    ) {
        return new SQLDbNamedObjectCompletionItem(score, filterKey, resolvedContext, object, SQLQueryCompletionItemKind.CATALOG);
    }

    @NotNull
    public static SQLQueryCompletionItem forDbSchemaObject(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @Nullable ContextObjectInfo resolvedContext,
        @NotNull DBSObject object
    ) {
        return new SQLDbNamedObjectCompletionItem(score, filterKey, resolvedContext, object, SQLQueryCompletionItemKind.SCHEMA);
    }

    @NotNull
    public static SQLQueryCompletionItem forCompositeField(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @NotNull DBSEntityAttribute attribute,
        @NotNull SQLQueryExprType.SQLQueryExprTypeMemberInfo memberInfo
    ) {
        return new SQLCompositeFieldCompletionItem(score, filterKey, attribute, memberInfo);
    }

    public static SQLQueryCompletionItem forJoinCondition(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @NotNull SQLColumnNameCompletionItem first,
        @NotNull SQLColumnNameCompletionItem second) {
        return new SQLJoinConditionCompletionItem(score, filterKey, first, second);
    }

    /**
     * Returns completion item that describes functions that comes from the dialect
     */
    public static SQLQueryCompletionItem forBuiltinFunction(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @NotNull String name
    ) {
        return new SQLBuiltinFunctionCompletionItem(score, filterKey, name);
    }

    /**
     * Returns completion item that describes database user-created functions
     */
    public static SQLQueryCompletionItem forProcedureObject(
        int score,
        @NotNull SQLQueryWordEntry filterKey,
        @Nullable ContextObjectInfo resolvedContext,
        @NotNull DBSProcedure object
    ) {
        return new SQLProcedureCompletionItem(score, filterKey, resolvedContext, object);
    }

    public static class SQLRowsSourceAliasCompletionItem extends SQLQueryCompletionItem {
        @NotNull
        public final SQLQuerySymbol symbol;
        @NotNull
        public final SourceResolutionResult sourceInfo;

        SQLRowsSourceAliasCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @NotNull SQLQuerySymbol symbol,
            @NotNull SourceResolutionResult sourceInfo
        ) {
            super(score, filterKey);
            this.symbol = symbol;
            this.sourceInfo = sourceInfo;
        }
        
        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return SQLQueryCompletionItemKind.SUBQUERY_ALIAS;
        }

        @Override
        protected <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitSubqueryAlias(this);
        }
    }
    
    public static class SQLColumnNameCompletionItem extends SQLQueryCompletionItem {
        @NotNull
        public final SQLQueryResultColumn columnInfo;
        // should be null only for columns provided by the root projection (SELECT clause), because it doesn't serve as a source
        @Nullable
        public final SourceResolutionResult sourceInfo;
        // TODO consider removing this flag in favor of refactoring for explicit formatting mechanism
        public final boolean absolute;

        SQLColumnNameCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @NotNull SQLQueryResultColumn columnInfo,
            @Nullable SourceResolutionResult sourceInfo,
            boolean absolute
        ) {
            super(score, filterKey);

            if (columnInfo == null) {
                throw new IllegalArgumentException("columnInfo should not be null");
            }

            this.columnInfo = columnInfo;
            this.sourceInfo = sourceInfo;
            this.absolute = absolute;
        }
        
        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return this.columnInfo.symbol.getSymbolClass() == SQLQuerySymbolClass.COLUMN_DERIVED 
                ? SQLQueryCompletionItemKind.DERIVED_COLUMN_NAME
                : SQLQueryCompletionItemKind.TABLE_COLUMN_NAME;
        }

        @Nullable
        @Override
        public DBSObject getObject() {
            return this.columnInfo.realAttr;
        }

        @Override
        protected <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitColumnName(this);
        }
    }

    public abstract static class SQLDbObjectCompletionItem<T extends DBSObject> extends SQLQueryCompletionItem {
        @Nullable
        public final ContextObjectInfo resolvedContext;
        @NotNull
        public final T object;

        SQLDbObjectCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @Nullable ContextObjectInfo resolvedContext,
            @NotNull T object) {
            super(score, filterKey);
            this.resolvedContext = resolvedContext;
            this.object = object;
        }

        @NotNull
        @Override
        public T getObject() {
            return this.object;
        }
    }

    public static class SQLTableNameCompletionItem extends SQLDbObjectCompletionItem<DBSEntity> {
        public final boolean isUsed;

        SQLTableNameCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @Nullable ContextObjectInfo resolvedContext,
            @NotNull DBSEntity table,
            boolean isUsed
        ) {
            super(score, filterKey, resolvedContext, table);
            this.isUsed = isUsed;
        }

        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return this.isUsed ? SQLQueryCompletionItemKind.USED_TABLE_NAME : SQLQueryCompletionItemKind.NEW_TABLE_NAME;
        }

        @Override
        protected <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitTableName(this);
        }
    }
    
    public static class SQLReservedWordCompletionItem extends SQLQueryCompletionItem {
        public final String text;

        SQLReservedWordCompletionItem(int score, @NotNull SQLQueryWordEntry filterKey, @NotNull String text) {
            super(score, filterKey);
            this.text = text;
        }
    
        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return SQLQueryCompletionItemKind.RESERVED;
        }

        @Override
        protected <R> R applyImpl(@NotNull SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitReservedWord(this);
        }
    }

    public static class SQLSpecialTextCompletionItem extends SQLQueryCompletionItem {
        public final String text;
        public final String description;

        SQLSpecialTextCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @NotNull String text,
            @Nullable String description
        ) {
            super(score, filterKey);
            this.text = text;
            this.description = description;
        }

        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return SQLQueryCompletionItemKind.UNKNOWN;
        }

        @Override
        protected <R> R applyImpl(@NotNull SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitSpecialText(this);
        }
    }

    public static class SQLDbNamedObjectCompletionItem extends SQLDbObjectCompletionItem<DBSObject>  {

        private final SQLQueryCompletionItemKind itemKind;

        SQLDbNamedObjectCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @Nullable ContextObjectInfo resolvedContext,
            @NotNull DBSObject object,
            @NotNull SQLQueryCompletionItemKind itemKind
        ) {
            super(score, filterKey, resolvedContext, object);
            this.itemKind = itemKind;
        }

        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return this.itemKind;
        }

        @Override
        protected <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitNamedObject(this);
        }
    }

    public static class SQLCompositeFieldCompletionItem extends SQLDbObjectCompletionItem<DBSEntityAttribute>  {
        @NotNull
        public final SQLQueryExprType.SQLQueryExprTypeMemberInfo memberInfo;

        SQLCompositeFieldCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @NotNull DBSEntityAttribute attribute,
            @NotNull SQLQueryExprType.SQLQueryExprTypeMemberInfo memberInfo
        ) {
            super(score, filterKey, null, attribute);
            this.memberInfo = memberInfo;
        }

        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return SQLQueryCompletionItemKind.COMPOSITE_FIELD_NAME;
        }

        @Override
        protected <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitCompositeField(this);
        }
    }

    public static class SQLJoinConditionCompletionItem extends SQLQueryCompletionItem {
        @NotNull
        public final SQLColumnNameCompletionItem left;
        @NotNull
        public final SQLColumnNameCompletionItem right;

        SQLJoinConditionCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @NotNull SQLColumnNameCompletionItem left,
            @NotNull SQLColumnNameCompletionItem right
        ) {
            super(score, filterKey);
            this.left = left;
            this.right = right;
        }

        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return SQLQueryCompletionItemKind.JOIN_CONDITION;
        }

        @Override
        protected <R> R applyImpl(@NotNull SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitJoinCondition(this);
        }
    }

    public static List<String> prepareQualifiedNameParts(@NotNull DBSObject object, @Nullable DBSObject knownSubroot) {
        LinkedList<String> parts = new LinkedList<>();
        parts.addFirst(DBUtils.getQuotedIdentifier(object));
        for (DBSObject o = object.getParentObject(); o != knownSubroot; o = o.getParentObject()) {
            if (o instanceof DBSStructContainer) {
                parts.addFirst(DBUtils.getQuotedIdentifier(o));
            }
        }
        return parts;
    }

    public record ContextObjectInfo(@NotNull String string, @NotNull DBSObject object, boolean preventFullName) {
    }

    public static class SQLBuiltinFunctionCompletionItem extends SQLQueryCompletionItem {

        @NotNull
        public final String name;

        private SQLBuiltinFunctionCompletionItem(int score, @NotNull SQLQueryWordEntry filterKey, @NotNull String name) {
            super(score, filterKey);
            this.name = name;
        }

        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return SQLQueryCompletionItemKind.PROCEDURE;
        }

        @Override
        protected <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitBuiltinFunction(this);
        }
    }

    public static class SQLProcedureCompletionItem extends SQLDbObjectCompletionItem<DBSProcedure> {

        public SQLProcedureCompletionItem(
            int score,
            @NotNull SQLQueryWordEntry filterKey,
            @Nullable ContextObjectInfo resolvedContext,
            @NotNull DBSProcedure object
        ) {
            super(score, filterKey, resolvedContext, object);
        }

        @NotNull
        @Override
        public SQLQueryCompletionItemKind getKind() {
            return SQLQueryCompletionItemKind.PROCEDURE;
        }

        @Override
        protected <R> R applyImpl(SQLQueryCompletionItemVisitor<R> visitor) {
            return visitor.visitProcedure(this);
        }
    }
}