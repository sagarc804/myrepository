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
package org.jkiss.dbeaver.model.sql.semantics.model.ddl;


import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.sql.semantics.*;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryDataContext;
import org.jkiss.dbeaver.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import org.jkiss.dbeaver.model.sql.semantics.model.select.SQLQueryRowsSourceModel;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryRowsDataContext;
import org.jkiss.dbeaver.model.sql.semantics.context.SQLQueryRowsSourceContext;
import org.jkiss.dbeaver.model.stm.STMTreeNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectType;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Describes object reference
 * @apiNote
 * TODO remove objectType and treat this as non-table rows source like table-producing procedures, no matter builtin or not
 *      (see something like {@code SELECT * FROM proc()}  )
 */
public class SQLQueryObjectDataModel extends SQLQueryRowsSourceModel implements SQLQuerySymbolDefinition {

    private static final Log log = Log.getLog(SQLQueryObjectDataModel.class);
    @NotNull
    private final SQLQueryQualifiedName name;
    @NotNull
    private DBSObjectType objectType;
    @NotNull
    private Set<DBSObjectType> objectContainerTypes;
    @Nullable
    private DBSObject object = null;

    private SQLQuerySymbolOrigin objectNameOrigin = null;

    public SQLQueryObjectDataModel(
            @NotNull STMTreeNode syntaxNode,
            @NotNull SQLQueryQualifiedName name,
            @NotNull DBSObjectType objectTypes,
            @NotNull Set<DBSObjectType> objectContainerTypes
    ) {
        super(syntaxNode);
        this.name = name;
        this.objectType = objectTypes;
        this.objectContainerTypes = objectContainerTypes;
    }

    @NotNull
    public DBSObjectType getObjectType() {
        return objectType;
    }

    @NotNull
    public SQLQueryQualifiedName getName() {
        return this.name;
    }

    @Nullable
    public DBSObject getObject() {
        return object;
    }

    @Nullable
    public SQLQuerySymbolOrigin getObjectNameOrigin() {
        return this.objectNameOrigin;
    }

    @NotNull
    @Override
    public SQLQuerySymbolClass getSymbolClass() {
        return this.object instanceof DBSTable || this.object instanceof DBSView
            ? SQLQuerySymbolClass.TABLE
            : this.object != null ? SQLQuerySymbolClass.OBJECT : SQLQuerySymbolClass.ERROR;
    }

    @NotNull
    @Override
    protected SQLQueryDataContext propagateContextImpl(
        @NotNull SQLQueryDataContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        Set<DBSObjectType> scopeMemberTypes = new HashSet<>();
        scopeMemberTypes.addAll(this.objectContainerTypes);
        scopeMemberTypes.add(this.objectType);
        this.objectNameOrigin = new SQLQuerySymbolOrigin.DbObjectFromContext(context, scopeMemberTypes, false);

        if (this.name.isNotClassified()) {
            List<String> nameStrings = this.name.toListOfStrings();
            this.object = context.findRealObject(statistics.getMonitor(), objectType, nameStrings);

            if (this.object != null) {
                this.name.setDefinition(this.object, this.objectNameOrigin);
                if (!this.objectType.getTypeClass().isAssignableFrom(this.object.getClass())) {
                    statistics.appendError(
                        this.getSyntaxNode(),
                        DBUtils.getObjectTypeName(this.object) + " found while expecting " + this.objectType.getTypeName()
                    );
                }
            } else {
                SQLQueryQualifiedName.performPartialResolution(
                    context,
                    statistics,
                    this.name,
                    this.objectNameOrigin,
                    scopeMemberTypes,
                    SQLQuerySymbolClass.ERROR
                );
                statistics.appendError(this.getSyntaxNode(), "Object " + this.name.toIdentifierString() + " not found in the database");
            }
        }
        return context;
    }

    @Override
    protected SQLQueryRowsSourceContext resolveRowSourcesImpl(
        @NotNull SQLQueryRowsSourceContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        this.object = context.getConnectionInfo().findRealObject(statistics.getMonitor(), objectType, this.name.toListOfStrings());
        return context.reset();
    }

    @Override
    protected SQLQueryRowsDataContext resolveRowDataImpl(
        @NotNull SQLQueryRowsDataContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        return this.getRowsSources().makeEmptyTuple();
    }

    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, @NotNull T arg) {
        return visitor.visitObjectReference(this, arg);
    }
}