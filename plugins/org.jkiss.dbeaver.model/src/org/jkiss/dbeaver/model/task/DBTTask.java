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
package org.jkiss.dbeaver.model.task;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBPObjectWithDescription;
import org.jkiss.dbeaver.model.app.DBPProject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;

/**
 * Task configuration
 */
public interface DBTTask extends DBPNamedObject, DBPObjectWithDescription {
    @NotNull
    String getId();

    @NotNull
    DBPProject getProject();

    @NotNull
    Date getCreateTime();

    @NotNull
    Date getUpdateTime();

    @NotNull
    DBTTaskType getType();

    @Nullable
    DBTTaskFolder getTaskFolder();

    @NotNull
    Map<String, Object> getProperties();

    void setProperties(@NotNull Map<String, Object> properties);

    boolean isTemporary();

    @Nullable
    DBTTaskRun getLastRun();

    @NotNull
    DBTTaskRun[] getAllRuns();

    @Nullable
    Path getRunLog(@NotNull DBTTaskRun run);

    @NotNull
    InputStream getRunLogInputStream(@NotNull DBTTaskRun run) throws DBException, IOException;

    void removeRun(DBTTaskRun taskRun);

    void cleanRunStatistics();

    /**
     * Refreshes run statistics. This is a <b>thread blocking operation</b>.
     */
    void refreshRunStatistics();
}
