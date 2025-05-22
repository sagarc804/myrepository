/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

package org.jkiss.dbeaver.ext.gbase8s.model;

import java.util.ArrayList;
import java.util.List;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.generic.model.GenericStructContainer;
import org.jkiss.dbeaver.ext.generic.model.GenericTable;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintInfo;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;

/**
 * @author Chao Tian
 */
public class GBase8sTable extends GenericTable {

    public GBase8sTable(GenericStructContainer container, @Nullable String tableName, @Nullable String tableType,
            @Nullable JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
    }

    public GBase8sTable(GenericStructContainer container, String tableName, String tableCatalogName,
            String tableSchemaName) {
        super(container, tableName, tableCatalogName, tableSchemaName);
    }

    @NotNull
    @Override
    public List<DBSEntityConstraintInfo> getSupportedConstraints() {
        boolean isSupportCheckConstraint = getDataSource().getMetaModel().supportsCheckConstraints();
        List<DBSEntityConstraintInfo> result = new ArrayList<>();
        result.add(DBSEntityConstraintInfo.of(DBSEntityConstraintType.PRIMARY_KEY, GBase8sUniqueKey.class));
        if (getDataSource().getMetaModel().supportsUniqueKeys()) {
            result.add(DBSEntityConstraintInfo.of(DBSEntityConstraintType.UNIQUE_KEY, GBase8sUniqueKey.class));
        }
        if (isSupportCheckConstraint) {
            result.add(DBSEntityConstraintInfo.of(DBSEntityConstraintType.CHECK, GBase8sUniqueKey.class));
        }
        return result;
    }

    @Override
    protected boolean isCacheDDL() {
        return false;
    }

    @Override
    public boolean supportsObjectDefinitionOption(String option) {
        return DBPScriptObject.OPTION_INCLUDE_COMMENTS.equals(option);
    }
}
