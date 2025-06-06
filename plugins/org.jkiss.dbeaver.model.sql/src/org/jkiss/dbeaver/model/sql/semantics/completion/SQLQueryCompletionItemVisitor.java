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
import org.jkiss.dbeaver.model.sql.semantics.completion.SQLQueryCompletionItem.*;

public interface SQLQueryCompletionItemVisitor<R> {

    @Nullable
    R visitSubqueryAlias(@NotNull SQLRowsSourceAliasCompletionItem rowsSourceAlias);

    @Nullable
    R visitCompositeField(@NotNull SQLCompositeFieldCompletionItem compositeField);

    @Nullable
    R visitColumnName(@NotNull SQLColumnNameCompletionItem columnName);

    @Nullable
    R visitTableName(@NotNull SQLTableNameCompletionItem tableName);

    @Nullable
    R visitReservedWord(@NotNull SQLReservedWordCompletionItem reservedWord);

    @Nullable
    R visitNamedObject(@NotNull SQLDbNamedObjectCompletionItem namedObject);

    @Nullable
    R visitJoinCondition(@NotNull SQLJoinConditionCompletionItem joinCondition);

    /**
     * Visit method for user-defned procedures
     */
    @Nullable
    R visitProcedure(@NotNull SQLProcedureCompletionItem procedure);

    /**
     * Visit method for dialect specific builtin functions
     */
    @Nullable
    R visitBuiltinFunction(@NotNull SQLBuiltinFunctionCompletionItem function);

    /**
     * Visit method for columns expansion
     */
    @Nullable
    R visitSpecialText(@NotNull SQLSpecialTextCompletionItem specialText);
}
