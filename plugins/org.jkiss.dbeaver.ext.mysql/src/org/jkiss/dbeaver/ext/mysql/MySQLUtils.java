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

package org.jkiss.dbeaver.ext.mysql;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.mysql.model.MySQLDataSource;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocation;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.tasks.nativetool.AbstractNativeToolSettings;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

/**
 * MySQL utils
 */
public class MySQLUtils {
    private static final Log log = Log.getLog(MySQLUtils.class);

    private static final String COLUMN_POSTFIX_PRIV = "_priv";
    private static final Map<String, Integer> typeMap = new HashMap<>();

    static {
        typeMap.put("bit", java.sql.Types.BIT);
        typeMap.put("bool", java.sql.Types.BOOLEAN);
        typeMap.put("boolean", java.sql.Types.BOOLEAN);
        typeMap.put("tinyint", java.sql.Types.TINYINT);
        typeMap.put("smallint", java.sql.Types.SMALLINT);
        typeMap.put("mediumint", java.sql.Types.INTEGER);
        typeMap.put("int", java.sql.Types.INTEGER);
        typeMap.put("integer", java.sql.Types.INTEGER);
        typeMap.put("int24", java.sql.Types.INTEGER);
        typeMap.put("bigint", java.sql.Types.BIGINT);
        typeMap.put("real", java.sql.Types.DOUBLE);
        typeMap.put("float", java.sql.Types.REAL);
        typeMap.put("decimal", java.sql.Types.DECIMAL);
        typeMap.put("dec", java.sql.Types.DECIMAL);
        typeMap.put("numeric", java.sql.Types.DECIMAL);
        typeMap.put("double", java.sql.Types.DOUBLE);
        typeMap.put("double precision", java.sql.Types.DOUBLE);
        typeMap.put("char", java.sql.Types.CHAR);
        typeMap.put("varchar", java.sql.Types.VARCHAR);
        typeMap.put("date", java.sql.Types.DATE);
        typeMap.put("time", java.sql.Types.TIME);
        typeMap.put("year", java.sql.Types.DATE);
        typeMap.put("timestamp", java.sql.Types.TIMESTAMP);
        typeMap.put("datetime", java.sql.Types.TIMESTAMP);

        typeMap.put("tinyblob", java.sql.Types.BINARY);
        typeMap.put("blob", java.sql.Types.LONGVARBINARY);
        typeMap.put("mediumblob", java.sql.Types.LONGVARBINARY);
        typeMap.put("longblob", java.sql.Types.LONGVARBINARY);

        typeMap.put("tinytext", java.sql.Types.VARCHAR);
        typeMap.put("text", java.sql.Types.VARCHAR);
        typeMap.put("mediumtext", java.sql.Types.VARCHAR);
        typeMap.put("longtext", java.sql.Types.VARCHAR);

        typeMap.put(MySQLConstants.TYPE_NAME_ENUM, java.sql.Types.CHAR);
        typeMap.put(MySQLConstants.TYPE_NAME_SET, java.sql.Types.CHAR);
        typeMap.put("geometry", java.sql.Types.BINARY);
        typeMap.put("binary", java.sql.Types.BINARY);
        typeMap.put("varbinary", java.sql.Types.VARBINARY);
        typeMap.put(MySQLConstants.TYPE_UUID, Types.CHAR);
    }

    public static int typeNameToValueType(String typeName)
    {
        Integer valueType = typeMap.get(typeName.toLowerCase(Locale.ENGLISH));
        return valueType == null ? java.sql.Types.OTHER : valueType;
    }

    public static List<String> collectPrivilegeNames(ResultSet resultSet)
    {
        // Now collect all privileges columns
        try {
            List<String> privs = new ArrayList<>();
            ResultSetMetaData rsMetaData = resultSet.getMetaData();
            int colCount = rsMetaData.getColumnCount();
            for (int i = 0; i < colCount; i++) {
                String colName = rsMetaData.getColumnName(i + 1);
                if (colName.toLowerCase(Locale.ENGLISH).endsWith(COLUMN_POSTFIX_PRIV)) {
                    privs.add(colName.substring(0, colName.length() - COLUMN_POSTFIX_PRIV.length()));
                }
            }
            return privs;
        } catch (SQLException e) {
            log.debug(e);
            return Collections.emptyList();
        }
    }

    public static Map<String, Boolean> collectPrivileges(List<String> privNames, ResultSet resultSet)
    {
        // Now collect all privileges columns
        Map<String, Boolean> privs = new TreeMap<>();
        for (String privName : privNames) {
            privs.put(privName, "Y".equals(JDBCUtils.safeGetString(resultSet, privName + COLUMN_POSTFIX_PRIV)));
        }
        return privs;
    }


    public static String getMySQLConsoleBinaryName()
    {
        return RuntimeUtils.getNativeBinaryName("mysql");
    }

    @NotNull
    public static String getMariaDBConsoleBinaryName() {
        return RuntimeUtils.getNativeBinaryName("mariadb");
    }

    public static String determineCurrentDatabase(JDBCSession session) throws DBCException {
        // Get active schema
        try {
            try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT DATABASE()")) {
                try (JDBCResultSet resultSet = dbStat.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString(1);
                    }
                    return null;
                }
            }
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }

    public static boolean isMariaDB(DBPDriver driver) {
        return MySQLConstants.DRIVER_CLASS_MARIA_DB.equals(
                driver.getDriverClassName());
    }

    public static boolean isAlterUSerSupported(MySQLDataSource dataSource) {
        return dataSource.isMariaDB() ? dataSource.isServerVersionAtLeast(10, 2) : dataSource.isServerVersionAtLeast(5, 7);
    }

    /**
     * Check if column SRID ({@code SRID <srid>} attribute) is supported
     */
    public static boolean isColumnSridSupported(@NotNull MySQLDataSource dataSource) {
        // There's no any documentation in which version this feature was added
        return !dataSource.isMariaDB() && dataSource.isServerVersionAtLeast(8, 0);
    }

    /**
     * Check if given type name is a spatial data type
     */
    public static boolean isSpatialDataType(@NotNull String name) {
        // Switch expression looks ugly here, sorry
        switch (name.toLowerCase(Locale.ROOT)) {
            case MySQLConstants.TYPE_GEOMETRY:
            case MySQLConstants.TYPE_POINT:
            case MySQLConstants.TYPE_LINESTRING:
            case MySQLConstants.TYPE_POLYGON:
            case MySQLConstants.TYPE_MULTIPOINT:
            case MySQLConstants.TYPE_MULTILINESTRING:
            case MySQLConstants.TYPE_MULTIPOLYGON:
            case MySQLConstants.TYPE_GEOMETRYCOLLECTION:
                return true;
            default:
                return false;
        }
    }

    @NotNull
    public static File getClientExecutablePath(@NotNull AbstractNativeToolSettings<?> settings) throws IOException {
        return getExecutablePath(settings, "mysql", "mariadb"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    public static File getDumpExecutablePath(@NotNull AbstractNativeToolSettings<?> settings) throws IOException {
        return getExecutablePath(settings, "mysqldump", "mariadb-dump"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static File getExecutablePath(
        @NotNull AbstractNativeToolSettings<?> settings,
        @NotNull String mysqlName,
        @NotNull String mariaName
    ) throws IOException {
        DBPNativeClientLocation location = settings.getClientHome();
        if (location == null) {
            throw new IOException("MySQL client location is not specified");
        }
        try {
            return RuntimeUtils.getNativeClientBinary(location, MySQLConstants.BIN_FOLDER, mysqlName);
        } catch (IOException ignored) {
            return RuntimeUtils.getNativeClientBinary(location, MySQLConstants.BIN_FOLDER, mariaName);
        }
    }
}
