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
package org.jkiss.dbeaver.ui.actions.datasource;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.runtime.jobs.DefaultInvalidationFeedbackHandler;
import org.jkiss.dbeaver.runtime.jobs.DisconnectJob;
import org.jkiss.dbeaver.runtime.jobs.InvalidateJob;
import org.jkiss.dbeaver.ui.IDataSourceContainerUpdate;
import org.jkiss.dbeaver.ui.UITask;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.actions.AbstractDataSourceHandler;
import org.jkiss.dbeaver.ui.dialogs.ConnectionLostDialog;
import org.jkiss.dbeaver.ui.dialogs.StandardErrorDialog;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.ArrayUtils;

import java.util.Set;

// TODO: invalidate ALL contexts
public class DataSourceInvalidateHandler extends AbstractDataSourceHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        DBCExecutionContext context = getActiveExecutionContext(event, false);
        if (context != null) {
            invalidateDataSource(context.getDataSource());
        } else {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            if (editor instanceof IDataSourceContainerUpdate) {
                // Try to set the same container.
                // It should trigger connection instantiation if for some reason it was lost (SQLEditor specific?)
                DBPDataSourceContainer dsContainer = ((IDataSourceContainerUpdate) editor).getDataSourceContainer();
                if (dsContainer != null) {
                    ((IDataSourceContainerUpdate) editor).setDataSourceContainer(null);
                    ((IDataSourceContainerUpdate) editor).setDataSourceContainer(dsContainer);
                }
            }

        }
        return null;
    }

    public static boolean invalidateDataSource(final DBPDataSource dataSource) {
        if (dataSource != null) {
            //final DataSourceDescriptor dataSourceDescriptor = (DataSourceDescriptor) context;
            DBPDataSourceContainer container = dataSource.getContainer();
            if (!ArrayUtils.isEmpty(Job.getJobManager().find(container))) {
                // Already connecting/disconnecting or cancelled - just return
                return false;
            }
            final InvalidateJob invalidateJob = new InvalidateJob(dataSource);
            invalidateJob.setFeedbackHandler(new DefaultInvalidationFeedbackHandler() {
                @Override
                public boolean confirmInvalidate(@NotNull Set<DBPDataSourceContainer> containersToInvalidate) {
                    for (DBPDataSourceContainer container : containersToInvalidate) {
                        if (!DataSourceHandler.checkAndCloseActiveTransaction(container, true)) {
                            return false;
                        }
                    }

                    return true;
                }
            });
            invalidateJob.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    StringBuilder message = new StringBuilder();
                    Throwable error = null;
                    int totalNum = 0;
                    int connectedNum = 0;
                    for (InvalidateJob.ContextInvalidateResult result : invalidateJob.getInvalidateResults()) {
                        totalNum++;
                        if (result.isError()) {
                            error = result.getException();
                        } else {
                            connectedNum++;
                        }
                    }
                    if (totalNum == 0) {
                        // no invalidation happened
                        return;
                    }
                    if (connectedNum > 0) {
                        message.insert(0, "Connections reopened: " + connectedNum + " (of " + totalNum + ")");
                    } else if (message.isEmpty()) {
                        message.insert(0, "All connections (" + totalNum + ") are alive!");
                    }
                    if (error != null) {
//                        UIUtils.showErrorDialog(
//                            shell,
//                            "Invalidate data source [" + context.getDataSource().getContainer().getName() + "]",
//                            "Error while connecting to the datasource",// + "\nTime spent: " + RuntimeUtils.formatExecutionTime(invalidateJob.getTimeSpent()),
//                            error);
                        final DBPDataSourceContainer container = dataSource.getContainer();
                        final Throwable dialogError = error;
                        final Integer result = UITask.run(() -> {
                                ConnectionLostDialog clDialog = new ConnectionLostDialog(null, container, dialogError, "Disconnect");
                                return clDialog.open();
                        });
                        if (result == null || result == IDialogConstants.STOP_ID) {
                            // Disconnect - to notify UI and reflect model changes
                            new DisconnectJob(container).schedule();
                        } else if (result == IDialogConstants.RETRY_ID) {
                            invalidateDataSource(dataSource);
                        }
                    } else {
                        log.debug(message);
                    }
                }
            });
            invalidateJob.schedule();
        }
        return true;
    }

    public static void showConnectionLostDialog(final Shell shell, final String message, final DBException error)
    {
        //log.debug(message);
        Runnable runnable = () -> {
            // Display the dialog
            DBPDataSource dataSource = error instanceof DBDatabaseException dbe ? dbe.getDataSource() : null;
            if (dataSource == null) {
                throw new IllegalStateException("No data source in error");
            }
            String title = "Connection with [" + dataSource.getContainer().getName() + "] lost";
            ConnectionRecoverDialog dialog = new ConnectionRecoverDialog(shell, title, message == null ? title : message, error);
            dialog.open();
        };
        UIUtils.syncExec(runnable);
    }

    private static class ConnectionRecoverDialog extends StandardErrorDialog {

        private final DBPDataSource dataSource;

        ConnectionRecoverDialog(Shell shell, String title, String message, DBException error)
        {
            super(
                shell == null ? UIUtils.getActiveWorkbenchShell() : shell,
                title,
                message,
                GeneralUtils.makeExceptionStatus(error),
                IStatus.ERROR);
            dataSource = error instanceof DBDatabaseException dbe ? dbe.getDataSource() : null;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, IDialogConstants.RETRY_ID, "&Reconnect", true);
            createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, false);
            createDetailsButton(parent);
        }

        @Override
        protected void buttonPressed(int id)
        {
            if (id == IDialogConstants.RETRY_ID) {
                invalidateDataSource(dataSource);
                super.buttonPressed(IDialogConstants.OK_ID);
            }
            super.buttonPressed(id);
        }
    }

}