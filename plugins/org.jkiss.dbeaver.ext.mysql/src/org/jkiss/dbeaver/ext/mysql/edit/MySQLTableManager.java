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
package org.jkiss.dbeaver.ext.mysql.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.mysql.model.*;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEObjectRenamer;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistAction;
import org.jkiss.dbeaver.model.impl.sql.edit.struct.SQLTableManager;
import org.jkiss.dbeaver.model.messages.ModelMessages;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.cache.DBSObjectCache;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * MySQL table manager
 */
public class MySQLTableManager extends SQLTableManager<MySQLTableBase, MySQLCatalog> implements DBEObjectRenamer<MySQLTableBase> {

    private static final Class<? extends DBSObject>[] CHILD_TYPES = CommonUtils.array(
        MySQLTableColumn.class,
        MySQLTableConstraint.class,
        MySQLTableForeignKey.class,
        MySQLTableIndex.class
    );

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return super.getMakerOptions(dataSource) | FEATURE_SUPPORTS_COPY;
    }

    @Nullable
    @Override
    public DBSObjectCache<MySQLCatalog, MySQLTableBase> getObjectsCache(MySQLTableBase object) {
        return object.getContainer().getTableCache();
    }

    @Override
    protected MySQLTableBase createDatabaseObject(@NotNull DBRProgressMonitor monitor, @NotNull DBECommandContext context, Object container, Object copyFrom, @NotNull Map<String, Object> options) throws DBException {
        final MySQLTable table;
        MySQLCatalog catalog = (MySQLCatalog) container;
        if (copyFrom instanceof DBSEntity) {
            table = new MySQLTable(monitor, catalog, (DBSEntity) copyFrom);
            table.setName(getNewChildName(monitor, catalog, ((DBSEntity) copyFrom).getName()));
        } else if (copyFrom == null) {
            table = new MySQLTable(catalog);
            setNewObjectName(monitor, catalog, table);

            final MySQLTable.AdditionalInfo additionalInfo = table.getAdditionalInfo(monitor);
            additionalInfo.setEngine(catalog.getDataSource().getDefaultEngine());
            additionalInfo.setCharset(catalog.getAdditionalInfo(monitor).getDefaultCharset());
            additionalInfo.setCollation(catalog.getAdditionalInfo(monitor).getDefaultCollation());
        } else {
            throw new DBException("Can't create MySQL table from '" + copyFrom + "'");
        }

        return table;
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) {
        StringBuilder query = new StringBuilder("ALTER TABLE "); //$NON-NLS-1$
        query.append(command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL)).append(" "); //$NON-NLS-1$
        appendTableModifiers(monitor, command.getObject(), command, query, true);

        actionList.add(
            new SQLDatabasePersistAction(query.toString())
        );
    }

    @Override
    protected void addStructObjectCreateActions(DBRProgressMonitor monitor, DBCExecutionContext executionContext, List<DBEPersistAction> actions, StructCreateCommand command, Map<String, Object> options) throws DBException {

        if (CommonUtils.getOption(options, DBPScriptObject.OPTION_INCLUDE_OBJECT_DROP)) {
            final MySQLTableBase table = command.getObject();
            final String tableName = DBUtils.getEntityScriptName(table, options);
            actions.add(0, new SQLDatabasePersistAction(ModelMessages.model_jdbc_create_new_table, "DROP TABLE IF EXISTS " + tableName));
        }

        super.addStructObjectCreateActions(monitor, executionContext, actions, command, options);
    }

    @Override
    protected void appendTableModifiers(DBRProgressMonitor monitor, MySQLTableBase tableBase, NestedObjectCommand tableProps, StringBuilder ddl, boolean alter) {
        if (tableBase instanceof MySQLTable table) {
            try {
                final MySQLDataSource dataSource = table.getDataSource();
                final MySQLTable.AdditionalInfo additionalInfo = table.getAdditionalInfo(monitor);
                if ((!table.isPersisted() || tableProps.getProperty("engine") != null) && additionalInfo.getEngine() != null) { //$NON-NLS-1$
                    ddl.append("\nENGINE=").append(additionalInfo.getEngine().getName()); //$NON-NLS-1$
                }
                if (dataSource.supportsCharsets() &&
                    (!table.isPersisted() || tableProps.getProperty("charset") != null) && //$NON-NLS-1$
                    additionalInfo.getCharset() != null
                ) {
                    ddl.append("\nDEFAULT CHARSET=").append(additionalInfo.getCharset().getName()); //$NON-NLS-1$
                }
                if (dataSource.supportsCollations() &&
                    (!table.isPersisted() || tableProps.getProperty("collation") != null) && //$NON-NLS-1$
                    additionalInfo.getCollation() != null
                ) {
                    ddl.append("\nCOLLATE=").append(additionalInfo.getCollation().getName()); //$NON-NLS-1$
                }
                if ((!table.isPersisted() && table.getDescription() != null) || tableProps.hasProperty(DBConstants.PROP_ID_DESCRIPTION)) {
                    ddl.append("\nCOMMENT=").append(SQLUtils.quoteString(table, CommonUtils.notEmpty(table.getDescription())));//$NON-NLS-1$
                }
                if ((!table.isPersisted() || tableProps.getProperty("autoIncrement") != null) && additionalInfo.getAutoIncrement() > 0) { //$NON-NLS-1$
                    ddl.append("\nAUTO_INCREMENT=").append(additionalInfo.getAutoIncrement()); //$NON-NLS-1$
                }
            } catch (DBCException e) {
                log.error(e);
            }
        }
    }

    @Override
    protected boolean isIncludeIndexInDDL(DBRProgressMonitor monitor, DBSTableIndex index) throws DBException {
        return !((MySQLTableIndex) index).isUniqueKeyIndex(monitor) && super.isIncludeIndexInDDL(monitor, index);
    }

    @Override
    protected void addObjectRenameActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectRenameCommand command, @NotNull Map<String, Object> options) {
        final MySQLDataSource dataSource = command.getObject().getDataSource();
        boolean alterTable = dataSource.supportsAlterTableRenameSyntax();
        actions.add(
            new SQLDatabasePersistAction(
                "Rename table",
                (alterTable ? "ALTER" : "RENAME") + " TABLE " + //$NON-NLS-3$
                    DBUtils.getQuotedIdentifier(command.getObject().getContainer()) + "." + DBUtils.getQuotedIdentifier(dataSource, command.getOldName()) +
                    (alterTable ? " RENAME" : "") + " TO " + DBUtils.getQuotedIdentifier(command.getObject().getContainer()) //$NON-NLS-2$
                    + "." + DBUtils.getQuotedIdentifier(dataSource, command.getNewName()))
        );
    }

    @NotNull
    @Override
    public Class<? extends DBSObject>[] getChildTypes() {
        return CHILD_TYPES;
    }

    @Override
    public Collection<? extends DBSObject> getChildObjects(DBRProgressMonitor monitor, MySQLTableBase object, Class<? extends DBSObject> childType) throws DBException {
        if (childType == MySQLTableColumn.class) {
            return object.getAttributes(monitor);
        } else if (childType == MySQLTableConstraint.class) {
            return object.getConstraints(monitor);
        } else if (childType == MySQLTableForeignKey.class) {
            return object.getAssociations(monitor);
        } else if (childType == MySQLTableIndex.class) {
            return object.getIndexes(monitor);
        }
        return null;
    }

    @Override
    public void renameObject(@NotNull DBECommandContext commandContext, @NotNull MySQLTableBase object, @NotNull Map<String, Object> options, @NotNull String newName) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }

}
