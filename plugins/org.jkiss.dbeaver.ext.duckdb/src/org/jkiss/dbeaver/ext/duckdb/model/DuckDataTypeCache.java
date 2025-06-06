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
package org.jkiss.dbeaver.ext.duckdb.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.generic.model.GenericDataType;
import org.jkiss.dbeaver.ext.generic.model.GenericStructContainer;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCBasicDataTypeCache;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

public class DuckDataTypeCache extends JDBCBasicDataTypeCache<GenericStructContainer, GenericDataType> {
    public DuckDataTypeCache(@NotNull GenericStructContainer container) {
        super(container);
    }

    @NotNull
    @Override
    protected JDBCStatement prepareObjectsStatement(
        @NotNull JDBCSession session,
        @NotNull GenericStructContainer container
    ) throws SQLException {
        return session.prepareStatement(
            "select distinct(type_name), type_name, type_category " +
            "from duckdb_types() " +
            "where schema_name = 'main'"
        );
    }

    @Override
    protected GenericDataType fetchObject(
        @NotNull JDBCSession session,
        @NotNull GenericStructContainer container,
        @NotNull JDBCResultSet dbResult
    ) {
        final String name = JDBCUtils.safeGetString(dbResult, "type_name");
        final int kind = getTypeKind(JDBCUtils.safeGetString(dbResult, "type_category"));
        return new GenericDataType(container, kind, name, null, false, false, -1, -1, -1);
    }

    private static int getTypeKind(@Nullable String category) {
        if (category == null) {
            return Types.OTHER;
        }

        switch (category.toLowerCase(Locale.ROOT)) {
            case "boolean":
            case "bool":
            case "logical":
                return Types.BOOLEAN;
            case "composite":
            case "point_2d":
            case "point_3d":
            case "point_4d":
            case "linestring_2d":
            case "polygon_2d":
            case "box_2d":
                return Types.STRUCT;
            case "wkb_blob":
            case "blob":
            case "bytea":
            case "varbinary":
            case "binary":
                return Types.BINARY;
            case "date":
                return Types.DATE;
            case "datetime":
            case "timestamp_us":
                return Types.TIMESTAMP;
            case "timestamptz":
                return Types.TIMESTAMP_WITH_TIMEZONE;
            case "time":
                return Types.TIME;
            case "timetz":
                return Types.TIME_WITH_TIMEZONE;
            case "numeric":
                return Types.NUMERIC;
            case "string":
            case "varchar":
            case "bpchar":
            case "nvarchar":
            case "text":
                return Types.VARCHAR;
            default:
                return Types.OTHER;
        }
    }
}
