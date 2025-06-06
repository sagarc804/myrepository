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
package org.jkiss.dbeaver.model.exec;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPCloseableObject;
import org.jkiss.dbeaver.model.DBPContextWithAttributes;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPObject;
import org.jkiss.dbeaver.model.dpi.DPIContainer;
import org.jkiss.dbeaver.model.dpi.DPIElement;
import org.jkiss.dbeaver.model.dpi.DPIObject;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSInstance;

/**
 * Execution context.
 * Provides access to execution sessions.
 * Usually contains some kind of physical database connection inside
 */
@DPIObject
public interface DBCExecutionContext extends DBPObject, DBPCloseableObject, DBPContextWithAttributes {

    /**
     * Unique context ID. Generated in the moment of context creation and never changes during context lifetime.
     */
    @DPIElement
    long getContextId();

    /**
     * Context name. Like MAin, Metadata, Script X, etc.
     */
    @DPIElement
    @NotNull
    String getContextName();

    /**
     * Owner datasource
     */
    @DPIContainer
    @NotNull
    DBPDataSource getDataSource();

    @DPIContainer
    DBSInstance getOwnerInstance();

    /**
     * Checks this context is really connected to remote database.
     * Usually DBPDataSourceContainer.getDataSource() returns datasource only if datasource is connected.
     * But in some cases (e.g. connection invalidation) datasource remains disconnected for some period of time.
     */
    @DPIElement
    boolean isConnected();

    /**
     * Opens new session
     * @param monitor progress monitor
     * @param purpose context purpose
     * @param task task description
     * @return execution context
     */
    @NotNull
    DBCSession openSession(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionPurpose purpose, @NotNull String task);

    /**
     * Checks whether this context is alive and underlying network connection isn't broken.
     * Implementation should perform server round-trip.
     * This function is also used for keep-alive function.
     * @param monitor    monitor
     * @throws DBException on any network errors
     */
    void checkContextAlive(DBRProgressMonitor monitor)
        throws DBException;

    /**
     * Invalidates the context in a span of several phases.
     * <p>
     * Each phase represents a different stage of the invalidation process:
     * <ul>
     *     <li>{@code BEFORE_INVALIDATE} is called before network handlers are invalidated.
     *     In most cases, it will <b>terminate</b> the underlying connection</li>
     *     <li>{@code INVALIDATE} is called after network handlers are invalidated.
     *     In most cases, it will <b>establish</b> the underlying connection</li>
     *     <li>{@code AFTER_INVALIDATE} is called after the context is invalidated.</li>
     * </ul>
     * <p>
     * The implementation may choose to ignore some of the phases if they are not applicable.
     *
     * @param monitor progress monitor
     * @param phase   invalidation phase
     * @throws DBException on any error to signal the invalidation was not successful
     */
    void invalidateContext(@NotNull DBRProgressMonitor monitor, @NotNull DBCInvalidatePhase phase) throws DBException;

    /**
     * Invalidates the context by processing all phases. This method will invalidate just the context. For a "complete"
     * invalidation involving network handlers invalidation, see {@link org.jkiss.dbeaver.runtime.jobs.InvalidateJob}.
     *
     * @see #invalidateContext(DBRProgressMonitor, DBCInvalidatePhase)
     */
    default void invalidateContext(@NotNull DBRProgressMonitor monitor) throws DBException {
        invalidateContext(monitor, DBCInvalidatePhase.BEFORE_INVALIDATE);
        invalidateContext(monitor, DBCInvalidatePhase.INVALIDATE);
        invalidateContext(monitor, DBCInvalidatePhase.AFTER_INVALIDATE);
    }

    /**
     * Defaults reader/writer.
     * @return null if defaults are not supported
     */
    @DPIElement
    @Nullable
    DBCExecutionContextDefaults getContextDefaults();
}
