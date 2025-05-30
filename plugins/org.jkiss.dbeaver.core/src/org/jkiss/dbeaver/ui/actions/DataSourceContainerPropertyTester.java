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
package org.jkiss.dbeaver.ui.actions;

import org.eclipse.core.expressions.PropertyTester;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.ui.actions.datasource.DataSourceToolbarUtils;

import java.util.Objects;

/**
 * DatabaseEditorPropertyTester
 */
public class DataSourceContainerPropertyTester extends PropertyTester
{
    static protected final Log log = Log.getLog(DataSourceContainerPropertyTester.class);

    public static final String PROP_DRIVER_ID = "driverId";
    public static final String PROP_DRIVER_CLASS = "driverClass";
    public static final String PROP_CONNECTED = "connected";
    public static final String PROP_CONNECTING = "connecting";
    public static final String PROP_SUPPORTS_SCHEMAS = "supportsSchemas";

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if (!(receiver instanceof DBPDataSourceContainer container)) {
            return false;
        }
        return switch (property) {
            case PROP_DRIVER_ID -> container.getDriver().getId().equals(expectedValue);
            case PROP_DRIVER_CLASS -> Objects.equals(container.getDriver().getDriverClassName(), expectedValue);
            case PROP_CONNECTED -> container.isConnected();
            case PROP_CONNECTING -> container.isConnecting();
            case PROP_SUPPORTS_SCHEMAS -> DataSourceToolbarUtils.isSchemasSupported(container);
            default -> false;
        };
    }

}