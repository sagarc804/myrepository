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
package org.jkiss.dbeaver.ext.clickhouse.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.ext.clickhouse.ClickhouseConstants;
import org.jkiss.dbeaver.ext.clickhouse.ClickhouseDataSourceInfo;
import org.jkiss.dbeaver.ext.clickhouse.ClickhouseTypeParser;
import org.jkiss.dbeaver.ext.clickhouse.model.jdbc.ClickhouseJdbcFactory;
import org.jkiss.dbeaver.ext.generic.model.GenericDataSource;
import org.jkiss.dbeaver.ext.generic.model.meta.GenericMetaModel;
import org.jkiss.dbeaver.ext.generic.model.meta.GenericMetaObject;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.*;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectCache;
import org.jkiss.dbeaver.model.impl.net.SSLHandlerTrustStoreImpl;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataType;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectFilter;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;
import org.osgi.framework.Version;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.*;

public class ClickhouseDataSource extends GenericDataSource {

    private static final Log log = Log.getLog(ClickhouseDataSource.class);

    private static Map<String, String> dataTypeMap = new HashMap<>();
    private final TableEnginesCache engineCache = new TableEnginesCache();

    static {
        dataTypeMap.put(String.class.getName(), "String");
        dataTypeMap.put(Integer.class.getName(), "Int32");
        dataTypeMap.put(Long.class.getName(), "Int64");
        dataTypeMap.put(Short.class.getName(), "Int16");
        dataTypeMap.put(Byte.class.getName(), "Int8");
        dataTypeMap.put(Float.class.getName(), "Float32");
        dataTypeMap.put(Double.class.getName(), "Float64");
        dataTypeMap.put(Date.class.getName(), "DateTime");

    }

    public ClickhouseDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, GenericMetaModel metaModel)
        throws DBException
    {
        super(monitor, container, metaModel, new ClickhouseSQLDialect());
        engineCache.getAllObjects(monitor, this);
    }

    List<ClickhouseTableEngine> getTableEngines() {
        return engineCache.getCachedObjects();
    }

    ClickhouseTableEngine getEngineByName(@NotNull String engineName) {
        return engineCache.getCachedObject(engineName);
    }

    @NotNull
    @Override
    protected Properties getAllConnectionProperties(@NotNull DBRProgressMonitor monitor, JDBCExecutionContext context, String purpose, DBPConnectionConfiguration connectionInfo) throws DBCException {
        Properties properties = super.getAllConnectionProperties(monitor, context, purpose, connectionInfo);
        if (!CommonUtils.toBoolean(properties.getProperty(ClickhouseConstants.PROP_USE_SERVER_TIME_ZONE)) &&
            !CommonUtils.toBoolean(properties.getProperty(ClickhouseConstants.PROP_USE_TIME_ZONE))
        ) {
            DBPPreferenceStore preferenceStore = DBWorkbench.getPlatform().getPreferenceStore();
            String customTimeZone = preferenceStore.getString(ModelPreferences.CLIENT_TIMEZONE);
            if (customTimeZone.equals(DBConstants.DEFAULT_TIMEZONE)) {
                customTimeZone = TimeZone.getDefault().getID();
            }
            properties.put(ClickhouseConstants.PROP_USE_TIME_ZONE, customTimeZone);
        }


        final DBWHandlerConfiguration sslConfig = getContainer().getActualConnectionConfiguration().getHandler("clickhouse-ssl");

        if (sslConfig != null && sslConfig.isEnabled()) {
            try {
                initSSL(monitor, properties, sslConfig);
            } catch (Exception e) {
                throw new DBCException("Error configuring SSL certificates", e);
            }
        }
        return properties;
    }

    private void initSSL(DBRProgressMonitor monitor, Properties properties, DBWHandlerConfiguration sslConfig) throws DBException {
        monitor.subTask("Initialising SSL configuration");
        properties.put(ClickhouseConstants.SSL_PARAM, "true");
        try {
            if ("com_clickhouse".equals(getContainer().getDriver().getId())) {
                if (DBWorkbench.isDistributed() || DBWorkbench.getPlatform().getApplication().isMultiuser()) {
                    String clientCertProp =
                        sslConfig.getSecureProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT_VALUE);
                    if (!CommonUtils.isEmpty(clientCertProp)) {
                        properties.put(ClickhouseConstants.SSL_PATH, saveCertificateToFile(clientCertProp));
                    }
                    String clientKeyProp = sslConfig.getSecureProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY_VALUE);
                    if (!CommonUtils.isEmpty(clientKeyProp)) {
                        properties.put(ClickhouseConstants.SSL_KEY_PASSWORD, saveCertificateToFile(clientKeyProp));
                    }
                } else {
                    properties.put(ClickhouseConstants.SSL_PATH,
                        sslConfig.getStringProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT)
                    );
                    properties.put(ClickhouseConstants.SSL_KEY_PASSWORD,
                        sslConfig.getStringProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY)
                    );
                }
                properties.put(ClickhouseConstants.SSL_MODE,
                    sslConfig.getStringProperty(ClickhouseConstants.SSL_MODE_CONF)
                );
            } else {
                // Old clickhouse used lowercase for sslmode, we should send it in the lowercase
                String mode = sslConfig.getStringProperty(ClickhouseConstants.SSL_MODE_CONF);
                if (mode != null) {
                    properties.put(ClickhouseConstants.SSL_MODE, mode.toLowerCase());
                }
            }
            if (DBWorkbench.isDistributed() || DBWorkbench.getPlatform().getApplication().isMultiuser()) {
                String caCertProp = sslConfig.getSecureProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT_VALUE);
                if (!CommonUtils.isEmpty(caCertProp)) {
                    properties.put(ClickhouseConstants.SSL_ROOT_CERTIFICATE, saveCertificateToFile(caCertProp));
                }
            } else {
                properties.put(ClickhouseConstants.SSL_ROOT_CERTIFICATE,
                    sslConfig.getStringProperty(SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT)
                );
            }
        } catch (IOException e) {
            throw new DBException("Can not configure SSL", e);
        }
    }

    @Override
    protected synchronized void readDatabaseServerVersion(Connection session, DatabaseMetaData metaData) {
        if (databaseVersion == null) {
            try {
                String version = JDBCUtils.executeQuery(session, "SELECT VERSION()");
                if (version != null) {
                    databaseVersion = new Version(version);
                }
            } catch (Throwable e) {
                log.error("Error determining server version", e);
            }
            if (databaseVersion == null) {
                super.readDatabaseServerVersion(session, metaData);
            }
        }
    }

    @Nullable
    @Override
    public DBSDataType resolveDataType(@NotNull DBRProgressMonitor monitor, @NotNull String typeFullName) throws DBException {
        String shortName = dataTypeMap.get(typeFullName);
        if (shortName != null) {
            typeFullName = shortName;
        }
        if (ClickhouseTypeParser.isComplexType(typeFullName)) {
            final DBSDataType type = ClickhouseTypeParser.getType(monitor, this, typeFullName);
            if (type != null) {
                return type;
            }
        }
        return super.resolveDataType(monitor, typeFullName);
    }

    @Override
    protected DBPDataSourceInfo createDataSourceInfo(DBRProgressMonitor monitor, @NotNull JDBCDatabaseMetaData metaData) {
        return new ClickhouseDataSourceInfo(metaData);
    }

    @Override
    public List<String> getCatalogsNames(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCDatabaseMetaData metaData,
        GenericMetaObject catalogObject,
        @Nullable DBSObjectFilter catalogFilters
    ) throws DBException {
        // We use custom catalog read because of https://github.com/ClickHouse/clickhouse-java/issues/1921
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read Clickhouse databases")) {
            try (JDBCStatement dbStat = session.createStatement()) {
                try (JDBCResultSet dbResults = dbStat.executeQuery("SHOW DATABASES")) {
                    List<String> catalogNames = new ArrayList<>();
                    while (dbResults.next()) {
                        String catalogName = dbResults.getString(1);
                        if (catalogFilters == null || catalogFilters.matches(catalogName)) {
                            catalogNames.add(catalogName);
                            monitor.subTask("Extract catalogs - " + catalogName);
                        } else {
                            catalogsFiltered = true;
                        }
                    }
                    return catalogNames;
                }
            }
        } catch (SQLException e) {
            log.debug(e);
            return super.getCatalogsNames(monitor, metaData, catalogObject, catalogFilters);
        }
    }

    @NotNull
    @Override
    public JDBCFactory getJdbcFactory() {
        return new ClickhouseJdbcFactory();
    }

    boolean isSupportTableComments() {
        return isServerVersionAtLeast(21, 6);
    }

    static class TableEnginesCache extends JDBCObjectCache<ClickhouseDataSource, ClickhouseTableEngine> {

        TableEnginesCache() {
            setListOrderComparator(DBUtils.nameComparator());
        }

        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(
            @NotNull JDBCSession session,
            @NotNull ClickhouseDataSource clickhouseDataSource) throws SQLException {
            return session.prepareStatement("SELECT name FROM system.table_engines");
        }
        
        @Override
        protected void detectCaseSensitivity(DBSObject object) {
            this.setCaseSensitive(true);
        }

        @Nullable
        @Override
        protected ClickhouseTableEngine fetchObject(
            @NotNull JDBCSession session,
            @NotNull ClickhouseDataSource clickhouseDataSource,
            @NotNull JDBCResultSet dbResult) {

            final String engineName = JDBCUtils.safeGetString(dbResult, 1);
            if (CommonUtils.isNotEmpty(engineName)) {
                return new ClickhouseTableEngine(engineName, clickhouseDataSource);
            }
            return null;
        }
    }
}
