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
package org.jkiss.dbeaver.model.impl.jdbc;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPTransactionIsolation;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCDatabaseMetaData;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceInfo;
import org.jkiss.dbeaver.model.impl.struct.RelationalObjectType;
import org.jkiss.dbeaver.model.messages.ModelMessages;
import org.jkiss.dbeaver.model.struct.DBSObjectType;
import org.jkiss.utils.CommonUtils;
import org.osgi.framework.Version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JDBCDataSourceInfo
 */
public class JDBCDataSourceInfo extends AbstractDataSourceInfo {
    private static final Log log = Log.getLog(JDBCDataSourceInfo.class);

    public static final String TERM_SCHEMA = ModelMessages.model_jdbc_Schema;
    public static final String TERM_PROCEDURE = ModelMessages.model_jdbc_Procedure;
    public static final String TERM_CATALOG = ModelMessages.model_jdbc_Database;

    private final JDBCDataSource dataSource;
    private boolean readOnly;
    private boolean readOnlyData;
    private boolean readOnlyMetaData;
    private String databaseProductName;
    private String databaseProductVersion;
    private String driverName;
    private String driverVersion;
    private String schemaTerm;
    private String procedureTerm;
    private String catalogTerm;

    private boolean supportsTransactions;
    private final List<DBPTransactionIsolation> supportedIsolations;

    private boolean supportsReferences = true;
    private boolean supportsIndexes = true;
    private boolean supportsStoredCode = true;
    private boolean supportsBatchUpdates = false;
    private boolean supportsScroll;
    private boolean supportsViews = true;

    public JDBCDataSourceInfo(DBPDataSourceContainer container) {
        this.dataSource = (JDBCDataSource) container.getDataSource();
        this.readOnly = false;
        this.databaseProductName = "?"; //$NON-NLS-1$
        this.databaseProductVersion = ""; //$NON-NLS-1$
        this.driverName = container.getDriver().getName(); //$NON-NLS-1$
        this.driverVersion = "?"; //$NON-NLS-1$
        this.schemaTerm = TERM_SCHEMA;
        this.procedureTerm = TERM_PROCEDURE;
        this.catalogTerm = TERM_CATALOG;
        this.supportsBatchUpdates = false;

        this.supportsTransactions = false;
        this.supportedIsolations = new ArrayList<>();
        this.supportedIsolations.add(0, JDBCTransactionIsolation.NONE);
        this.supportsScroll = true;
    }

    public JDBCDataSourceInfo(JDBCDatabaseMetaData metaData) {
        this.dataSource = metaData.getDataSource();

        if (!isIgnoreReadOnlyFlag()) {
            try {
                this.readOnly = metaData.isReadOnly();
            } catch (Throwable e) {
                debugError(e);
                this.readOnly = false;
            }
        } else {
            this.readOnly = false;
        }
        try {
            this.databaseProductName = metaData.getDatabaseProductName();
        } catch (Throwable e) {
            debugError(e);
            this.databaseProductName = "?"; //$NON-NLS-1$
        }
        try {
            this.databaseProductVersion = metaData.getDatabaseProductVersion();
        } catch (Throwable e) {
            debugError(e);
        }
        try {
            String name = metaData.getDriverName();
            if (name != null) {
                this.driverName = name;
            }
        } catch (Throwable e) {
            debugError(e);
            this.driverName = "?"; //$NON-NLS-1$
        }
        try {
            this.driverVersion = metaData.getDriverVersion();
        } catch (Throwable e) {
            debugError(e);
            this.driverVersion = "?"; //$NON-NLS-1$
        }
        try {
            this.schemaTerm = makeTermString(metaData.getSchemaTerm(), TERM_SCHEMA);
        } catch (Throwable e) {
            debugError(e);
            this.schemaTerm = TERM_SCHEMA;
        }
        try {
            this.procedureTerm = makeTermString(metaData.getProcedureTerm(), TERM_PROCEDURE);
        } catch (Throwable e) {
            debugError(e);
            this.procedureTerm = TERM_PROCEDURE;
        }
        try {
            this.catalogTerm = makeTermString(metaData.getCatalogTerm(), TERM_CATALOG);
        } catch (Throwable e) {
            debugError(e);
            this.catalogTerm = TERM_CATALOG;
        }
        try {
            supportsBatchUpdates = metaData.supportsBatchUpdates();
        } catch (Throwable e) {
            debugError(e);
        }

        try {
            supportsTransactions = metaData.supportsTransactions();
        } catch (Throwable e) {
            debugError(e);
            supportsTransactions = true;
        }

        supportedIsolations = new ArrayList<>();
        if (supportsTransactions) {
            try {
                for (JDBCTransactionIsolation txi : JDBCTransactionIsolation.values()) {
                    if (metaData.supportsTransactionIsolationLevel(txi.getCode())) {
                        supportedIsolations.add(txi);
                    }
                }
            } catch (Throwable e) {
                debugError(e);
            }
            if (!supportedIsolations.contains(JDBCTransactionIsolation.NONE)) {
                supportedIsolations.add(0, JDBCTransactionIsolation.NONE);
            }
            addCustomTransactionIsolationLevels(supportedIsolations);
        }

        supportsScroll = true;
    }

    protected void addCustomTransactionIsolationLevels(List<DBPTransactionIsolation> isolations) {
        // to be overrided in implementors
    }

    // Says to ignore DatabaseMetaData.isReadonly() results. It is broken in some drivers (always true), e.g. in Redshift.
    protected boolean isIgnoreReadOnlyFlag() {
        return true;
    }

    private String makeTermString(String term, String defTerm) {
        return CommonUtils.isEmpty(term) ? defTerm : CommonUtils.capitalizeWord(term.toLowerCase());
    }

    @Override
    public boolean isReadOnlyData() {
        return readOnly || readOnlyData;
    }

    protected void setReadOnlyData(boolean readOnly) {
        this.readOnlyData = readOnly;
    }

    @Override
    public boolean isReadOnlyMetaData() {
        return readOnly || readOnlyMetaData;
    }

    protected void setReadOnlyMetaData(boolean readOnlyMetaData) {
        this.readOnlyMetaData = readOnlyMetaData;
    }

    @Override
    public String getDatabaseProductName() {
        return databaseProductName;
    }

    @Override
    public String getDatabaseProductVersion() {
        if (CommonUtils.isEmpty(databaseProductVersion)) {
            Version databaseVersion = getDatabaseVersion();
            if (databaseVersion != null) {
                return databaseVersion.toString();
            }
        }
        return databaseProductVersion;
    }

    @Override
    public Version getDatabaseVersion() {
        return dataSource.getDatabaseServerVersion();
    }

    @Override
    public String getDriverName() {
        return driverName;
    }

    @Override
    public String getDriverVersion() {
        return driverVersion;
    }

    @Override
    public String getSchemaTerm() {
        return schemaTerm;
    }

    @Override
    public String getProcedureTerm() {
        return procedureTerm;
    }

    @Override
    public String getCatalogTerm() {
        return catalogTerm;
    }

    @Override
    public boolean supportsTransactions() {
        return supportsTransactions;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsReferentialIntegrity() {
        return supportsReferences;
    }

    public void setSupportsReferences(boolean supportsReferences) {
        this.supportsReferences = supportsReferences;
    }

    @Override
    public boolean supportsIndexes() {
        return supportsIndexes;
    }

    public void setSupportsIndexes(boolean supportsIndexes) {
        this.supportsIndexes = supportsIndexes;
    }


    public boolean supportsViews() {
        return supportsViews;
    }

    public void setSupportsViews(boolean supportsViews) {
        this.supportsViews = supportsViews;
    }

    @Override
    public boolean supportsStoredCode() {
        return supportsStoredCode;
    }

    public void setSupportsStoredCode(boolean supportsStoredCode) {
        this.supportsStoredCode = supportsStoredCode;
    }

    @Override
    public Collection<DBPTransactionIsolation> getSupportedTransactionsIsolation() {
        return supportedIsolations;
    }

    @Override
    public boolean supportsResultSetLimit() {
        return true;
    }

    @Override
    public boolean supportsResultSetScroll() {
        return supportsScroll;
    }

    @Override
    public boolean isDynamicMetadata() {
        return false;
    }

    @Override
    public boolean supportsMultipleResults() {
        return false;
    }

    @Override
    public boolean isMultipleResultsFetchBroken() {
        return false;
    }

    @Override
    public DBSObjectType[] getSupportedObjectTypes() {
        return new DBSObjectType[] {
            RelationalObjectType.TYPE_TABLE,
            RelationalObjectType.TYPE_VIEW,
            RelationalObjectType.TYPE_TABLE_COLUMN,
            RelationalObjectType.TYPE_VIEW_COLUMN,
            RelationalObjectType.TYPE_INDEX,
            RelationalObjectType.TYPE_CONSTRAINT,
            RelationalObjectType.TYPE_PROCEDURE,
            RelationalObjectType.TYPE_SEQUENCE,
            RelationalObjectType.TYPE_TRIGGER,
            RelationalObjectType.TYPE_DATA_TYPE
        };
    }

    @Override
    public boolean supportsStatementBinding() {
        return true;
    }

    public void setSupportsResultSetScroll(boolean supportsScroll) {
        this.supportsScroll = supportsScroll;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return supportsBatchUpdates;
    }

    private static void debugError(Throwable e) {
        if (e.getMessage() == null) {
            log.debug(e.getClass().getName());
        } else {
            log.debug(e.getClass().getName() + ": " + e.getMessage());
        }
    }

}
