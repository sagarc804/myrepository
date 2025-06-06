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
package org.jkiss.dbeaver.model.ai.format;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPObjectWithDescription;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.List;

public interface IAIFormatter {
    String postProcessGeneratedQuery(
        DBRProgressMonitor monitor,
        DBSObjectContainer mainObject,
        DBCExecutionContext executionContext,
        String completionText
    );

    @NotNull
    List<String> getExtraInstructions();

    void addExtraDescription(
        DBRProgressMonitor monitor,
        DBSEntity object,
        StringBuilder description,
        DBPObjectWithDescription lastAttr
    ) throws DBException;

    void addObjectDescriptionIfNeeded(
        @NotNull StringBuilder description,
        @NotNull DBPObjectWithDescription object,
        @NotNull DBRProgressMonitor monitor
    );

    void addColumnTypeIfNeeded(
        @NotNull StringBuilder description,
        @NotNull DBSEntityAttribute attribute,
        @NotNull DBRProgressMonitor monitor
    );

    /**
     * Add data sample of the object to the description.
     */
    void addDataSample(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSDataContainer dataContainer,
        @NotNull StringBuilder description
    ) throws DBException;
}

