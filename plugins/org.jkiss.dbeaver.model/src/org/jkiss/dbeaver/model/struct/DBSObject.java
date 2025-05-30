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

package org.jkiss.dbeaver.model.struct;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBPObjectWithDescription;
import org.jkiss.dbeaver.model.DBPPersistedObject;
import org.jkiss.dbeaver.model.dpi.DPIContainer;
import org.jkiss.dbeaver.model.dpi.DPIObject;

/**
 * Meta object
 */
@DPIObject
public interface DBSObject extends DBPNamedObject, DBPObjectWithDescription, DBPPersistedObject {

    /**
     * Parent object
     *
     * @return parent object or null
     */
    @DPIContainer
	DBSObject getParentObject();

    /**
     * Datasource which this object belongs.
     * It can be null if object was detached from data source.
     * @return datasource reference or null
     */
    @DPIContainer
    DBPDataSource getDataSource();

}
