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
package org.jkiss.dbeaver.ext.oracle.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.oracle.model.OracleDDLFormat;
import org.jkiss.dbeaver.ext.oracle.model.OracleMaterializedView;
import org.jkiss.dbeaver.ext.oracle.model.OracleSchema;
import org.jkiss.dbeaver.ext.oracle.model.OracleTableColumn;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.edit.prop.DBECommandComposite;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistAction;
import org.jkiss.dbeaver.model.impl.sql.edit.SQLObjectEditor;
import org.jkiss.dbeaver.model.impl.sql.edit.struct.SQLTableManager;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.cache.DBSObjectCache;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * OracleMaterializedViewManager
 */
public class OracleMaterializedViewManager extends SQLObjectEditor<OracleMaterializedView, OracleSchema> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource)
    {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    protected void validateObjectProperties(DBRProgressMonitor monitor, ObjectChangeCommand command, Map<String, Object> options)
        throws DBException
    {
        if (CommonUtils.isEmpty(command.getObject().getName())) {
            throw new DBException("View name cannot be empty"); //$NON-NLS-1$
        }
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, OracleMaterializedView> getObjectsCache(OracleMaterializedView object)
    {
        return (DBSObjectCache) object.getSchema().tableCache;
    }

    @Override
    protected OracleMaterializedView createDatabaseObject(@NotNull DBRProgressMonitor monitor, @NotNull DBECommandContext context, Object container, Object copyFrom, @NotNull Map<String, Object> options)
    {
        OracleSchema schema = (OracleSchema) container;
        OracleMaterializedView newView = new OracleMaterializedView(schema, "NEW_MVIEW"); //$NON-NLS-1$
        setNewObjectName(monitor, schema, newView);
        newView.setObjectDefinitionText("SELECT 1 FROM DUAL");
        newView.setCurrentDDLFormat(OracleDDLFormat.COMPACT);
        return newView;
    }

    @Override
    protected String getBaseObjectName() {
        return SQLTableManager.BASE_MATERIALIZED_VIEW_NAME;
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException {
        createOrReplaceViewQuery(monitor, actions, command, options);
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        createOrReplaceViewQuery(monitor, actionList, command, options);
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options)
    {
        actions.add(
            new SQLDatabasePersistAction("Drop view", "DROP MATERIALIZED VIEW " + command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL)) //$NON-NLS-2$
        );
    }

    private void createOrReplaceViewQuery(DBRProgressMonitor monitor, List<DBEPersistAction> actions, DBECommandComposite<OracleMaterializedView, PropertyHandler> command, Map<String, Object> options) throws DBException {
        OracleMaterializedView view = command.getObject();

        StringBuilder decl = new StringBuilder(200);
        final String lineSeparator = GeneralUtils.getDefaultLineSeparator();
        boolean hasComment = command.hasProperty("comment");
        if (!hasComment || command.getProperties().size() > 1) {
            String mViewDefinition = view.getMViewText().trim();
            if (mViewDefinition.contains("CREATE MATERIALIZED VIEW")) {
                if (mViewDefinition.endsWith(";")) mViewDefinition = mViewDefinition.substring(0, mViewDefinition.length() - 1);
                decl.append(mViewDefinition);
            } else {
                decl.append("CREATE MATERIALIZED VIEW ").append(view.getFullyQualifiedName(DBPEvaluationContext.DDL)).append(lineSeparator) //$NON-NLS-1$
                    .append("AS ").append(mViewDefinition); //$NON-NLS-1$
            }
            if (view.isPersisted()) {
                actions.add(
                    new SQLDatabasePersistAction("Drop view", "DROP MATERIALIZED VIEW " + view.getFullyQualifiedName(DBPEvaluationContext.DDL))); //$NON-NLS-2$
            }
            List<SQLScriptElement> sqlScriptElements = SQLScriptParser.parseScript(view.getDataSource(), mViewDefinition);
            if (sqlScriptElements.size() > 1) {
                // In this case we already have and view definition, and view/columns comments
                for (SQLScriptElement scriptElement : sqlScriptElements) {
                    actions.add(new SQLDatabasePersistAction("Create view part", scriptElement.getText()));
                }
                return;
            }
            actions.add(
                new SQLDatabasePersistAction("Create view", decl.toString()));
        }

        if (hasComment || (CommonUtils.getOption(options, DBPScriptObject.OPTION_OBJECT_SAVE) && CommonUtils.isNotEmpty(view.getComment()))) {
            actions.add(new SQLDatabasePersistAction(
                "Comment view",
                "COMMENT ON MATERIALIZED VIEW " + view.getFullyQualifiedName(DBPEvaluationContext.DDL) +
                    " IS " + SQLUtils.quoteString(view.getDataSource(), CommonUtils.notEmpty(view.getComment()))));
        }

        if (!(hasComment && command.getProperties().size() == 1) && CommonUtils.getOption(options, DBPScriptObject.OPTION_OBJECT_SAVE)) { // Add column comments only in save case
            // Column comments for the newly created table
            for (OracleTableColumn column : CommonUtils.safeCollection(view.getAttributes(monitor))) {
                if (!CommonUtils.isEmpty(column.getComment(monitor))) {
                    OracleTableColumnManager.addColumnCommentAction(actions, column, view);
                }
            }
        }
    }

}

