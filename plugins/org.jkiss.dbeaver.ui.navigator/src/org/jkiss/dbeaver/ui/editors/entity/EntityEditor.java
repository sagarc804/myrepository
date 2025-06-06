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
package org.jkiss.dbeaver.ui.editors.entity;

import com.google.gson.reflect.TypeToken;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.*;
import org.eclipse.ui.views.properties.IPropertySheetPage;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.edit.DBECommand;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEObjectManager;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.impl.edit.DBECommandAdapter;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceListener;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.rm.RMConstants;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.ProxyProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.UIServiceSQL;
import org.jkiss.dbeaver.ui.*;
import org.jkiss.dbeaver.ui.actions.DataSourcePropertyTester;
import org.jkiss.dbeaver.ui.actions.datasource.DataSourceToolbarUtils;
import org.jkiss.dbeaver.ui.controls.CustomFormEditor;
import org.jkiss.dbeaver.ui.controls.ProgressPageControl;
import org.jkiss.dbeaver.ui.controls.PropertyPageStandard;
import org.jkiss.dbeaver.ui.controls.breadcrumb.BreadcrumbViewer;
import org.jkiss.dbeaver.ui.controls.folders.ITabbedFolder;
import org.jkiss.dbeaver.ui.controls.folders.ITabbedFolderContainer;
import org.jkiss.dbeaver.ui.controls.folders.ITabbedFolderListener;
import org.jkiss.dbeaver.ui.dialogs.ConfirmationDialog;
import org.jkiss.dbeaver.ui.editors.*;
import org.jkiss.dbeaver.ui.editors.DatabaseEditorPreferences.BreadcrumbLocation;
import org.jkiss.dbeaver.ui.editors.entity.properties.ObjectPropertiesEditor;
import org.jkiss.dbeaver.ui.internal.UINavigatorMessages;
import org.jkiss.dbeaver.ui.navigator.NavigatorPreferences;
import org.jkiss.dbeaver.ui.navigator.breadcrumb.NodeBreadcrumbViewer;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * EntityEditor
 */
public class EntityEditor extends MultiPageDatabaseEditor
    implements IPropertyChangeReflector, IProgressControlProvider, ISaveablePart2, IRevertableEditor, ILazyEditor,
    ITabbedFolderContainer, DBPDataSourceContainerProvider, IEntityEditorContext
{
    public static final String ID = "org.jkiss.dbeaver.ui.editors.entity.EntityEditor"; //$NON-NLS-1$

    public static final String TABS_CONFIG_FILE = "entity-editor-tabs.json"; //$NON-NLS-1$

    // fired when editor is initialized with a database object (e.g. after lazy loading, navigation or history browsing).
    private static final int PROP_OBJECT_INIT = 0x212;
    
    private static final Log log = Log.getLog(EntityEditor.class);

    private static class EditorDefaults {
        String pageId;
        String folderId;

        private EditorDefaults(String pageId, String folderId)
        {
            this.pageId = pageId;
            this.folderId = folderId;
        }
    }

    private static Map<String, EditorDefaults> defaultPageMap;

    private final Map<String, IEditorPart> editorMap = new LinkedHashMap<>();
    private IEditorPart activeEditor;
    private DBECommandAdapter commandListener;
    private final ITabbedFolderListener folderListener;
    private boolean hasPropertiesEditor;
    private final Map<IEditorPart, IEditorActionBarContributor> actionContributors = new HashMap<>();
    private volatile boolean saveInProgress = false;

    public EntityEditor() {
        if (defaultPageMap == null) {
            defaultPageMap = loadTabsConfiguration();
        }
        folderListener = folderId -> {
            IEditorPart editor = getActiveEditor();
            if (editor != null) {
                String editorPageId = getEditorPageId(editor);
                if (editorPageId != null) {
                    updateEditorDefaults(editorPageId, folderId);
                }
            }
        };
    }

    @Override
    public void handlePropertyChange(int propId)
    {
        super.handlePropertyChange(propId);
    }

    @Nullable
    @Override
    public ProgressPageControl getProgressControl()
    {
        IEditorPart activeEditor = getActiveEditor();
        return activeEditor instanceof IProgressControlProvider ? ((IProgressControlProvider) activeEditor).getProgressControl() : null;
    }

    public DBSObject getDatabaseObject()
    {
        return getEditorInput().getDatabaseObject();
    }

    @Nullable
    public DBECommandContext getCommandContext()
    {
        return getEditorInput().getCommandContext();
    }

    @Override
    public void dispose() {
        saveTabsConfiguration();

        for (Map.Entry<IEditorPart, IEditorActionBarContributor> entry : actionContributors.entrySet()) {
            GlobalContributorManager.getInstance().removeContributor(entry.getValue(), entry.getKey());
        }
        actionContributors.clear();

        DBECommandContext commandContext = getCommandContext();
        if (commandListener != null && commandContext != null) {
            commandContext.removeCommandListener(commandListener);
            commandListener = null;
        }
        super.dispose();

        if (getDatabaseObject() != null && commandContext != null) {
            commandContext.resetChanges(true);
//            // Remove all non-persisted objects
//            for (DBPObject object : getCommandContext().getEditedObjects()) {
//                if (object instanceof DBPPersistedObject && !((DBPPersistedObject)object).isPersisted()) {
//                    dataSource.getContainer().fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_REMOVE, (DBSObject) object));
//                }
//            }
        }
        this.editorMap.clear();
        this.activeEditor = null;
    }

    @Override
    public boolean isDirty()
    {
        final DBECommandContext commandContext = getCommandContext();
        if (commandContext != null && commandContext.isDirty()) {
            return true;
        }

        for (IEditorPart editor : editorMap.values()) {
            if (editor.isDirty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSaveAsAllowed()
    {
        return this.activeEditor != null && this.activeEditor.isSaveAsAllowed();
    }

    @Override
    public void doSaveAs()
    {
        IEditorPart activeEditor = getActiveEditor();
        if (activeEditor != null && activeEditor.isSaveAsAllowed()) {
            activeEditor.doSaveAs();
        }
    }

    public boolean isSaveInProgress() {
        return saveInProgress;
    }

    /**
     * Saves data in all nested editors
     * @param monitor progress monitor
     */
    @Override
    public void doSave(IProgressMonitor monitor)
    {
        if (!isDirty()) {
            return;
        }
        if (EditorUtils.isInAutoSaveJob()) {
            // Do not save entity editors in auto-save job (#2408)
            return;
        }
        DBPProject ownerProject = getEditorInput().getNavigatorNode().getOwnerProject();

        if (
            DBUtils.isReadOnly(getDatabaseObject())
                || ownerProject == null
                || !DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR)
        ) {
            DBWorkbench.getPlatformUI().showNotification(
                "Read-only",
                "Object [" + DBUtils.getObjectFullName(getDatabaseObject(), DBPEvaluationContext.UI) + "] is read-only",
                true, null);
            return;
        }

        // Flush all nested object editors and result containers
        for (IEditorPart editor : editorMap.values()) {
            if (editor instanceof IEntityStructureEditor || editor instanceof IEntityDataEditor) {
                if (editor.isDirty()) {
                    editor.doSave(monitor);
                }
            }
            if (monitor.isCanceled()) {
                return;
            }
        }

        // Check read-only

        // Show preview
        int previewResult = IDialogConstants.PROCEED_ID;
        if (DBWorkbench.getPlatform().getPreferenceStore().getBoolean(NavigatorPreferences.NAVIGATOR_SHOW_SQL_PREVIEW)) {
            monitor.beginTask(UINavigatorMessages.editors_entity_monitor_preview_changes, 1);
            previewResult = showChanges(true);
            monitor.done();
        }

        if (previewResult == IDialogConstants.IGNORE_ID) {
            // There are no changes to save
            // Let's just refresh dirty status
            firePropertyChange(IEditorPart.PROP_DIRTY);
            return;
        }
        if (previewResult != IDialogConstants.PROCEED_ID) {
            monitor.setCanceled(true);
            return;
        }

        try {
            saveInProgress = true;

            monitor.beginTask("Save changes...", 1);
            try {
                monitor.subTask("Save '" + getPartName() + "' changes...");
                SaveJob saveJob = new SaveJob();
                saveJob.schedule();

                // Wait until job finished
                UIUtils.waitJobCompletion(saveJob, monitor);
                if (!saveJob.success) {
                    monitor.setCanceled(true);
                    return;
                }
            } finally {
                monitor.done();
            }

            firePropertyChange(IEditorPart.PROP_DIRTY);
        }
        finally {
            saveInProgress = false;
        }

        // Run post-save commands (e.g. compile)
        Map<String, Object> context = new LinkedHashMap<>();
        for (IEditorPart editor : editorMap.values()) {
            if (editor instanceof IDatabasePostSaveProcessor) {
                ((IDatabasePostSaveProcessor) editor).runPostSaveCommands(context);
            }
            if (monitor.isCanceled()) {
                return;
            }
        }
    }

    @Override
    public void doRevertToSaved() {
        for (IEditorPart editor : editorMap.values()) {
            if (editor instanceof IRevertableEditor) {
                ((IRevertableEditor) editor).doRevertToSaved();
            }
        }

        // Revert command context
        DBECommandContext commandContext = getCommandContext();
        if (commandContext != null) {
            commandContext.resetChanges(true);
        }
    }

    @Override
    public boolean loadEditorInput() {
        final IDatabaseEditorInput input = getEditorInput();
        if (input instanceof DatabaseLazyEditorInput && !((DatabaseLazyEditorInput) input).canLoadImmediately()) {
            return ((ProgressEditorPart) getActiveEditor()).scheduleEditorLoad();
        } else {
            return false;
        }
    }

    @Override
    public boolean unloadEditorInput() {
        if (getEditorInput() instanceof IUnloadableEditorInput) {
            final IEditorInput input = ((IUnloadableEditorInput) getEditorInput()).unloadInput();
            deactivateEditor();
            setInput(input);
            firePropertyChange(PROP_INPUT);
            recreateEditorControl();
            return true;
        } else {
            return false;
        }
    }

    private boolean saveCommandContext(final DBRProgressMonitor monitor, Map<String, Object> options) {
        monitor.beginTask("Save entity", 1);
        Throwable error = null;
        final DBECommandContext commandContext = getCommandContext();
        if (commandContext == null) {
            log.warn("Null command context");
            return true;
        }
        DBCExecutionContext executionContext = getExecutionContext();
        if (executionContext == null) {
            log.warn("Null execution context");
            return true;
        }
        boolean isNewObject = getDatabaseObject() == null || !getDatabaseObject().isPersisted();
        if (!isNewObject) {
            // Check for any new nested objects
            for (DBECommand cmd : commandContext.getFinalCommands()) {
                if (cmd.getObject() instanceof DBSObject && !((DBSObject) cmd.getObject()).isPersisted()) {
                    isNewObject = true;
                    break;
                }
            }
        }
        try {
            DBExecUtils.tryExecuteRecover(monitor, executionContext.getDataSource(), param -> {
                try {
                    commandContext.saveChanges(monitor, options);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (DBException e) {
            error = e;
        }
        if (getDatabaseObject() instanceof DBPStatefulObject) {
            try {
                ((DBPStatefulObject) getDatabaseObject()).refreshObjectState(monitor);
            } catch (DBCException e) {
                // Just report an error
                log.error(e);
            }
        }

        if (error == null) {
            // Refresh underlying node
            // It'll refresh database object and all it's descendants
            // So we'll get actual data from database
            final DBNDatabaseNode treeNode = getEditorInput().getNavigatorNode();
            boolean doRefresh = isNewObject;
            new AbstractJob("Database node refresh") { //$NON-NLS-1$
                @Override
                protected IStatus run(DBRProgressMonitor monitor) {
                    try {
                        treeNode.refreshNode(monitor, doRefresh ? DBNEvent.FORCE_REFRESH : DBNEvent.UPDATE_ON_SAVE);
                    } catch (DBException e) {
                        return GeneralUtils.makeExceptionStatus(e);
                    }
                    return Status.OK_STATUS;
                }
            }.schedule();
        }
        monitor.done();

        if (error == null) {
            return true;
        } else {
            // Try to handle error in nested editors
            final Throwable vError = error;
            UIUtils.syncExec(() -> {
                final IErrorVisualizer errorVisualizer = getAdapter(IErrorVisualizer.class);
                if (errorVisualizer != null) {
                    errorVisualizer.visualizeError(monitor, vError);
                }
            });

            // Show error dialog

            UIUtils.asyncExec(() ->
                DBWorkbench.getPlatformUI().showError("Can't save '" + getDatabaseObject().getName() + "'", null, vError));
            return false;
        }
    }

    public void revertChanges()
    {
        if (isDirty()) {
            if (ConfirmationDialog.confirmAction(
                null,
                NavigatorPreferences.CONFIRM_ENTITY_REVERT,
                ConfirmationDialog.QUESTION,
                getDatabaseObject().getName()) != IDialogConstants.YES_ID)
            {
                return;
            }
            DBECommandContext commandContext = getCommandContext();
            if (commandContext != null) {
                commandContext.resetChanges(true);
            }
            refreshPart(this, true, false);
            firePropertyChange(IEditorPart.PROP_DIRTY);
        }
    }

    public void undoChanges()
    {
        IUndoManager undoManager = getAdapter(IUndoManager.class);
        if (undoManager != null) {
            undoManager.undo();
        } else {
            DBECommandContext commandContext = getCommandContext();
            if (commandContext != null && commandContext.getUndoCommand() != null) {
                if (!getDatabaseObject().isPersisted() && commandContext.getUndoCommands().size() == 1) {
                    //getSite().getPage().closeEditor(this, true);
                    //return;
                    // Undo of last command in command context will close editor
                    // Let's ask user about it
                    if (ConfirmationDialog.confirmAction(
                            null,
                            NavigatorPreferences.CONFIRM_ENTITY_REJECT,
                            ConfirmationDialog.QUESTION,
                            getDatabaseObject().getName()) != IDialogConstants.YES_ID) {
                        return;
                    }
                }
                commandContext.undoCommand();
                firePropertyChange(IEditorPart.PROP_DIRTY);
            }
        }
    }

    public void redoChanges()
    {
        IUndoManager undoManager = getAdapter(IUndoManager.class);
        if (undoManager != null) {
            undoManager.redo();
        } else {
            DBECommandContext commandContext = getCommandContext();
            if (commandContext != null && commandContext.getRedoCommand() != null) {
                commandContext.redoCommand();
                firePropertyChange(IEditorPart.PROP_DIRTY);
            }
        }
    }

    public int showChanges(boolean allowSave)
    {
        DBECommandContext commandContext = getCommandContext();
        if (commandContext == null) {
            return IDialogConstants.CANCEL_ID;
        }
        Collection<? extends DBECommand> commands = commandContext.getFinalCommands();
        if (CommonUtils.isEmpty(commands)) {
            return IDialogConstants.IGNORE_ID;
        }
        StringBuilder script = new StringBuilder();

        try {
            saveInProgress = true;
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask("Generate SQL script", commands.size());
                Map<String, Object> validateOptions = new HashMap<>();
                for (DBECommand command : commands) {
                    monitor.subTask(command.getTitle());
                    try {
                        command.validateCommand(monitor, validateOptions);
                    } catch (final DBException e) {
                        throw new InvocationTargetException(e);
                    }
                    Map<String, Object> options = new HashMap<>();
                    options.put(DBPScriptObject.OPTION_OBJECT_SAVE, true);

                    DBPDataSource dataSource = getDatabaseObject().getDataSource();
                    try {
                        DBEPersistAction[] persistActions = command.getPersistActions(monitor, getExecutionContext(), options);
                        script.append(SQLUtils.generateScript(
                            dataSource,
                            persistActions,
                            false));
                    } catch (DBException e) {
                        throw new InvocationTargetException(e);
                    }
                    monitor.worked(1);
                }
                monitor.done();
            });
        } catch (InterruptedException e) {
            return IDialogConstants.CANCEL_ID;
        } catch (InvocationTargetException e) {
            log.error(e);
            DBWorkbench.getPlatformUI().showError("Script generate error", "Couldn't generate alter script", e.getTargetException());
            return IDialogConstants.CANCEL_ID;
        } finally {
            saveInProgress = false;
        }

        if (script.length() == 0) {
            return IDialogConstants.PROCEED_ID;
        }
        ChangesPreviewer changesPreviewer = new ChangesPreviewer(script, allowSave);
        UIUtils.syncExec(changesPreviewer);
        return changesPreviewer.getResult();
    }

    @Override
    protected void createPages()
    {
        super.createPages();

        final IDatabaseEditorInput editorInput = getEditorInput();
        if (createPageForInput(editorInput)) {
            return;
        }

        // Command listener
        commandListener = new DBECommandAdapter() {
            @Override
            public void onCommandChange(DBECommand<?> command)
            {
                UIUtils.syncExec(() -> firePropertyChange(IEditorPart.PROP_DIRTY));
            }
        };
        DBECommandContext commandContext = getCommandContext();
        if (commandContext != null) {
            commandContext.addCommandListener(commandListener);
        }

        // Property listener
        addPropertyListener((source, propId) -> {
            if (propId == IEditorPart.PROP_DIRTY) {
                EntityEditorPropertyTester.firePropertyChange(EntityEditorPropertyTester.PROP_DIRTY);
                EntityEditorPropertyTester.firePropertyChange(EntityEditorPropertyTester.PROP_CAN_UNDO);
                EntityEditorPropertyTester.firePropertyChange(EntityEditorPropertyTester.PROP_CAN_REDO);
            }
        });

        DBSObject databaseObject = editorInput.getDatabaseObject();
        EditorDefaults editorDefaults = null;
        if (databaseObject == null) {
            // Weird
            log.debug("Null database object in EntityEditor");
        } else {
            synchronized (defaultPageMap) {
                editorDefaults = defaultPageMap.get(databaseObject.getClass().getName());
            }

            EntityEditorsRegistry editorsRegistry = EntityEditorsRegistry.getInstance();

            // Add object editor page
            EntityEditorDescriptor defaultEditor = editorsRegistry.getMainEntityEditor(databaseObject, this);
            hasPropertiesEditor = false;
            if (defaultEditor != null) {
                hasPropertiesEditor = addEditorTab(defaultEditor);
            }
            if (hasPropertiesEditor) {
                DBNNode node = editorInput.getNavigatorNode();
                int propEditorIndex = getPageCount() - 1;
                setPageText(propEditorIndex, UINavigatorMessages.editors_entity_properties_text);
                setPageToolTip(propEditorIndex, node.getNodeTypeLabel() + UINavigatorMessages.editors_entity_properties_tooltip_suffix);
                setPageImage(propEditorIndex, DBeaverIcons.getImage(node.getNodeIconDefault()));
            }
        }

        // Add contributed pages
        addContributions(EntityEditorDescriptor.POSITION_PROPS);
        addContributions(EntityEditorDescriptor.POSITION_START);
        addContributions(EntityEditorDescriptor.POSITION_MIDDLE);

        // Add contributed pages
        addContributions(EntityEditorDescriptor.POSITION_END);

        if (databaseObject != null) {
            EntityEditorFeatures.ENTITY_EDITOR_OPEN.use(Map.of(
                "className", databaseObject.getClass().getSimpleName(),
                "driver", databaseObject.getDataSource() == null ? "" :
                    databaseObject.getDataSource().getContainer().getDriver().getPreconfiguredId()
            ));
        }

        String defPageId = editorInput.getDefaultPageId();
        String defFolderId = editorInput.getDefaultFolderId();
        if (defPageId == null && editorDefaults != null) {
            defPageId = editorDefaults.pageId;
        }
        if (defPageId != null) {
            IEditorPart defEditorPage = editorMap.get(defPageId);
            if (defEditorPage != null) {
                setActiveEditor(defEditorPage);
            }
        } else {
            setActiveEditor(getEditor(0));
        }
        this.activeEditor = getActiveEditor();
        if (activeEditor instanceof ITabbedFolderContainer) {
            if (defFolderId == null && editorDefaults != null) {
                defFolderId = editorDefaults.folderId;
            }
            if (defFolderId != null) {
                String folderId = defFolderId;
                UIUtils.asyncExec(() -> {
                    ((ITabbedFolderContainer)activeEditor).switchFolder(folderId);
                });
            }
        }

        UIUtils.setHelp(getContainer(), IHelpContextIds.CTX_ENTITY_EDITOR);
    }

    private boolean createPageForInput(@NotNull IEditorInput editorInput) {
        if (editorInput instanceof DatabaseLazyEditorInput input) {
            try {
                addPage(new ProgressEditorPart(this), input);
                setPageText(0, input.canLoadImmediately()
                    ? UINavigatorMessages.editors_entity_title_initializing
                    : UINavigatorMessages.editors_entity_title_uninitialized);
                setPageImage(0, DBeaverIcons.getImage(UIIcon.REFRESH));
                setActivePage(0);
            } catch (PartInitException e) {
                log.error(e);
            }

            return true;
        } else if (editorInput instanceof ErrorEditorInput input) {
            try {
                addPage(new ErrorEditorPartEx(input.getError()), input);
                setPageText(0, "Error");
                setPageImage(0, UIUtils.getShardImage(ISharedImages.IMG_OBJS_ERROR_TSK));
                setActivePage(0);
            } catch (PartInitException e) {
                log.error(e);
            }

            return true;
        }

        return false;
    }

    public IEditorPart getPageEditor(String pageId) {
        return editorMap.get(pageId);
    }

    @Override
    protected void pageChange(int newPageIndex) {
        try {
            super.pageChange(newPageIndex);
        } catch (Throwable e) {
            log.error(e);
        }

        activeEditor = getEditor(newPageIndex);

        for (Map.Entry<IEditorPart, IEditorActionBarContributor> entry : actionContributors.entrySet()) {
            if (entry.getKey() == activeEditor) {
                entry.getValue().setActiveEditor(activeEditor);
            } else {
                entry.getValue().setActiveEditor(null);
            }
        }

        String editorPageId = getEditorPageId(activeEditor);
        if (editorPageId != null) {
            updateEditorDefaults(editorPageId, null);
        }
        // Fire dirty flag refresh to re-enable Save-As command (which is enabled only for certain pages)
        firePropertyChange(IEditorPart.PROP_DIRTY);
        DataSourcePropertyTester.firePropertyChange(DataSourcePropertyTester.PROP_TRANSACTION_ACTIVE);
    }

    @Nullable
    private String getEditorPageId(IEditorPart editorPart)
    {
        synchronized (editorMap) {
            for (Map.Entry<String,IEditorPart> entry : editorMap.entrySet()) {
                if (entry.getValue() == editorPart) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private void updateEditorDefaults(String pageId, @Nullable String folderId)
    {
        IDatabaseEditorInput editorInput = getEditorInput();
        if (editorInput instanceof DatabaseEditorInput<?> dei) {
            dei.setDefaultPageId(pageId);
            dei.setDefaultFolderId(folderId);
        }
        DBSObject object = editorInput.getDatabaseObject();
        if (object != null) {
            {
                EditorDefaults editorDefaults = defaultPageMap.get(object.getClass().getName());
                if (editorDefaults == null) {
                    editorDefaults = new EditorDefaults(pageId, folderId);
                    defaultPageMap.put(object.getClass().getName(), editorDefaults);
                } else {
                    if (pageId != null) {
                        editorDefaults.pageId = pageId;
                    }
                    if (folderId != null) {
                        editorDefaults.folderId = folderId;
                    }
                }
            }
        }
    }

    private static Map<String, EditorDefaults> loadTabsConfiguration() {
        Map<String, EditorDefaults> pageMap = null;
        try {
            // Save
            Path configFile = DBWorkbench.getPlatform().getLocalConfigurationFile(TABS_CONFIG_FILE);
            if (Files.exists(configFile)) {
                pageMap = JSONUtils.GSON.fromJson(
                    Files.newBufferedReader(configFile),
                    new TypeToken<Map<String, EditorDefaults>>(){}.getType());
            }
        } catch (Exception e) {
            log.error("Error loading tabs configuration", e);
        }
        if (pageMap == null) {
            pageMap = new HashMap<>();
        }
        return pageMap;
    }

    private static void saveTabsConfiguration() {
        try {
            // Save
            Path configPath = DBWorkbench.getPlatform().getLocalConfigurationFile(TABS_CONFIG_FILE);
            if (!Files.exists(configPath.getParent())) {
                Files.createDirectories(configPath.getParent());
            }
            Files.writeString(
                configPath,
                JSONUtils.GSON.toJson(defaultPageMap),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.error("Error saving tabs configuration", e);
        }
    }

    @Override
    public int promptToSaveOnClose()
    {
        List<String> changedSubEditors = new ArrayList<>();
        final DBECommandContext commandContext = getCommandContext();
        if (commandContext != null && commandContext.isDirty()) {
            changedSubEditors.add(UINavigatorMessages.registry_entity_editor_descriptor_name);
        }

        for (IEditorPart editor : editorMap.values()) {
            if (editor.isDirty()) {

                EntityEditorDescriptor editorDescriptor = EntityEditorsRegistry.getInstance().getEntityEditor(editor);
                if (editorDescriptor != null) {
                    changedSubEditors.add(editorDescriptor.getName());
                }
            }
        }

        String subEditorsString = changedSubEditors.isEmpty() ? "" : "(" + String.join(", ", changedSubEditors) + ")";
        final int result = ConfirmationDialog.confirmAction(
            getSite().getShell(),
            NavigatorPreferences.CONFIRM_ENTITY_EDIT_CLOSE,
            ConfirmationDialog.QUESTION_WITH_CANCEL,
            getEditorInput().getNavigatorNode().getNodeDisplayName(),
            subEditorsString);
        if (result == IDialogConstants.YES_ID) {
//            getWorkbenchPart().getSite().getPage().saveEditor(this, false);
            return ISaveablePart2.YES;
        } else if (result == IDialogConstants.NO_ID) {
            return ISaveablePart2.NO;
        } else {
            return ISaveablePart2.CANCEL;
        }
    }

    @Nullable
    @Override
    public ITabbedFolder getActiveFolder()
    {
        if (getActiveEditor() instanceof ITabbedFolderContainer) {
            ((ITabbedFolderContainer)getActiveEditor()).getActiveFolder();
        }
        return null;
    }

    @Override
    public boolean switchFolder(String folderId)
    {
        boolean changed = false;
        for (IEditorPart editor : editorMap.values()) {
            if (editor instanceof ITabbedFolderContainer tfc) {
                if (getActiveEditor() != editor) {
                    setActiveEditor(editor);
                }
                if (tfc.switchFolder(folderId)) {
                    changed = true;
                }
            }
        }
//        if (getActiveEditor() instanceof IFolderedPart) {
//            ((IFolderedPart)getActiveEditor()).switchFolder(folderId);
//        }
        return changed;
    }

    public void setActiveEditor(Class<?> editorInterface) {
        for (int i = 0; i < getPageCount(); i++) {
            if (editorInterface.isAssignableFrom(getEditor(i).getClass())) {
                setActiveEditor(getEditor(i));
                break;
            }
        }
    }

    @Override
    public void addFolderListener(ITabbedFolderListener listener)
    {
    }

    @Override
    public void removeFolderListener(ITabbedFolderListener listener)
    {
    }

    private void addContributions(String position)
    {
        EntityEditorsRegistry editorsRegistry = EntityEditorsRegistry.getInstance();
        final DBSObject databaseObject = getEditorInput().getDatabaseObject();
        DBPObject object;
        if (databaseObject instanceof DBPDataSourceContainer && databaseObject.getDataSource() != null) {
            object = databaseObject.getDataSource();
        } else {
            object = databaseObject;
        }
        List<EntityEditorDescriptor> descriptors = editorsRegistry.getEntityEditors(
            object,
            this,
            position);
        for (EntityEditorDescriptor descriptor : descriptors) {
            if (descriptor.getType() == EntityEditorDescriptor.Type.editor) {
                addEditorTab(descriptor);
            }
        }
    }

    private boolean addEditorTab(EntityEditorDescriptor descriptor)
    {
        try {
            IEditorPart editor = descriptor.createEditor();
            if (editor == null) {
                return false;
            }
            IEditorInput nestedInput = descriptor.getNestedEditorInput(getEditorInput());
            final Class<? extends IEditorActionBarContributor> contributorClass = descriptor.getContributorClass();
            if (contributorClass != null) {
                addActionsContributor(editor, contributorClass);
            }
            int index = addPage(editor, nestedInput);
            setPageText(index, descriptor.getName());
            if (descriptor.getIcon() != null) {
                setPageImage(index, DBeaverIcons.getImage(descriptor.getIcon()));
            }
            if (!CommonUtils.isEmpty(descriptor.getDescription())) {
                setPageToolTip(index, descriptor.getDescription());
            }
            editorMap.put(descriptor.getId(), editor);

            if (editor instanceof ITabbedFolderContainer) {
                ((ITabbedFolderContainer) editor).addFolderListener(folderListener);
            }

            return true;
        } catch (Exception ex) {
            log.error("Error adding nested editor", ex); //$NON-NLS-1$
            return false;
        }
    }

    private void addActionsContributor(IEditorPart editor, Class<? extends IEditorActionBarContributor> contributorClass) throws Exception {
        GlobalContributorManager contributorManager = GlobalContributorManager.getInstance();
        IEditorActionBarContributor contributor = contributorManager.getContributor(contributorClass);
        if (contributor == null) {
            contributor = contributorClass.getDeclaredConstructor().newInstance();
        }
        contributorManager.addContributor(contributor, editor);
        actionContributors.put(editor, contributor);
    }

    @Override
    public RefreshResult refreshPart(final Object source, boolean force) {
        return refreshPart(source, force, true);
    }

    private RefreshResult refreshPart(final Object source, boolean force, boolean showConfirmation) {
        if (getContainer() == null || getContainer().isDisposed() || saveInProgress) {
            return RefreshResult.IGNORED;
        }

        DBSObject databaseObject = getEditorInput().getDatabaseObject();
        boolean isPersistedObject = databaseObject != null && databaseObject.isPersisted();

        if (force && isPersistedObject && isDirty() && showConfirmation) {
            if (ConfirmationDialog.confirmAction(
                null,
                NavigatorPreferences.CONFIRM_ENTITY_REVERT,
                ConfirmationDialog.QUESTION,
                getTitle()) != IDialogConstants.YES_ID)
            {
                return RefreshResult.CANCELED;
            }
        }

        boolean isRename = false;
        if (source instanceof DBNEvent) {
            if (((DBNEvent) source).getNodeChange() == DBNEvent.NodeChange.REFRESH) {
                // This may happen if editor was refreshed indirectly (it is a child of refreshed node)
                //force = true;
            }
            Object source2 = ((DBNEvent) source).getSource();
            if (source2 instanceof DBPEvent) {
                if (((DBPEvent) source2).getData() == DBPEvent.RENAME) {
                    Map<String, Object> options = ((DBPEvent) source2).getOptions();
                    Object uiSource = options.get(DBEObjectManager.OPTION_UI_SOURCE);
                    if (uiSource != null && !(uiSource instanceof CustomFormEditor)) {
                        isRename = true;
                    }
                }
            }
        }

        if (force) {
            if (isPersistedObject) {
                // Lists and commands should be refreshed only if we make real refresh from remote storage
                // Otherwise just update object's properties
                DBECommandContext commandContext = getCommandContext();
                if (commandContext != null && commandContext.isDirty()) {
                    // Just clear command context. Do not undo because object state was already refreshed
                    commandContext.resetChanges(true);
                }
            }
        }

        if (databaseObject != null) {
            // Refresh visual content in parts
            for (IEditorPart editor : editorMap.values()) {
                if (editor instanceof IRefreshablePart) {
                    // If it is a rename event then force refresh
                    boolean refreshNestedPart = force;
                    if (!refreshNestedPart && editor instanceof ObjectPropertiesEditor && isRename) {
                        refreshNestedPart = true;
                    }
                    ((IRefreshablePart)editor).refreshPart(source, refreshNestedPart);
                }
            }
        }

        setPartName(getEditorInput().getName());
        setTitleImage(getEditorInput().getImageDescriptor());

        if (hasPropertiesEditor) {
            // Update main editor image
            DBNDatabaseNode navigatorNode = getEditorInput().getNavigatorNode();
            if (navigatorNode != null) {
                setPageImage(0, DBeaverIcons.getImage(navigatorNode.getNodeIconDefault()));
            }
        }

        firePropertyChange(IWorkbenchPartConstants.PROP_DIRTY);

        return RefreshResult.REFRESHED;
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        T activeAdapter = getNestedAdapter(adapter);
        if (activeAdapter != null) {
            return activeAdapter;
        }
        if (adapter == IPropertySheetPage.class) {
            return adapter.cast(new PropertyPageStandard());
        }
        if (adapter == DBSObject.class) {
            IDatabaseEditorInput editorInput = getEditorInput();
            DBSObject databaseObject = editorInput.getDatabaseObject();
            return adapter.cast(databaseObject);
        }
        return super.getAdapter(adapter);
    }

    public <T> T getNestedAdapter(Class<T> adapter) {
        // restrict delegating to the UI thread for bug 144851
        if (Display.getCurrent() != null) {
            IEditorPart activeEditor = getActiveEditor();
            if (activeEditor != null) {
                Object result = activeEditor.getAdapter(adapter);
                if (result != null) {
                    return adapter.cast(result);
                }
                if (adapter.isAssignableFrom(activeEditor.getClass())) {
                    return adapter.cast(activeEditor);
                }
            }
        }
        return null;
    }

    @Override
    protected Control createTopRightControl(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayoutData(GridDataFactory.swtDefaults().grab(false, true).create());
        composite.setLayout(GridLayoutFactory.fillDefaults().create());

        NodeBreadcrumbViewer viewer = new NodeBreadcrumbViewer(composite, SWT.TOP);
        viewer.setInput(getEditorInput().getNavigatorNode());

        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        DBPPreferenceListener listener = event -> {
            if (event.getProperty().equals(DatabaseEditorPreferences.UI_STATUS_BAR_SHOW_BREADCRUMBS)) {
                composite.setVisible(BreadcrumbLocation.get(store) == BreadcrumbLocation.IN_EDITORS);
                updateTopRightControl();
            }
        };

        store.addPropertyChangeListener(listener);
        composite.addDisposeListener(e -> store.removePropertyChangeListener(listener));
        composite.setVisible(BreadcrumbLocation.get(store) == BreadcrumbLocation.IN_EDITORS);

        return composite;
    }

    @Override
    public DBPDataSourceContainer getDataSourceContainer() {
        return DBUtils.getContainer(getDatabaseObject());
    }

    @Override
    public void recreateEditorControl() {
        if (getContainer() == null || getContainer().isDisposed()) {
            // Disposed during editor opening
            return;
        }
        if (getContainer() instanceof CTabFolder) {
            final Control control = ((CTabFolder) getContainer()).getTopRight();
            if (control != null) {
                control.dispose();
            }
        }
        recreatePages();
        firePropertyChange(PROP_OBJECT_INIT);
        DataSourceToolbarUtils.refreshSelectorToolbar(getSite().getWorkbenchWindow());
    }

    private class ChangesPreviewer implements Runnable {

        private final StringBuilder script;
        private final boolean allowSave;
        private int result;

        ChangesPreviewer(StringBuilder script, boolean allowSave)
        {
            this.script = script;
            this.allowSave = allowSave;
        }

        @Override
        public void run()
        {
            UIServiceSQL serviceSQL = DBWorkbench.getService(UIServiceSQL.class);
            if (serviceSQL != null) {
//                result = serviceSQL.openGeneratedScriptViewer(
//                    getExecutionContext(),
//                    allowSave ? UINavigatorMessages.editors_entity_dialog_persist_title : UINavigatorMessages.editors_entity_dialog_preview_title,
//                    UIIcon.SQL_PREVIEW,
//                    scriptGenerator,
//                    props,
//                    allowSave);
                result = serviceSQL.openSQLViewer(
                    getExecutionContext(),
                    getDatabaseObject().getName() + " - " + (allowSave ? UINavigatorMessages.editors_entity_dialog_persist_title : UINavigatorMessages.editors_entity_dialog_preview_title),
                    UIIcon.SQL_PREVIEW,
                    script.toString(),
                    allowSave,
                    true);
            } else {
                result = IDialogConstants.OK_ID;
            }
        }

        public int getResult()
        {
            return result;
        }
    }

    private class SaveJob extends AbstractJob {
        private transient Boolean success = null;

        SaveJob() {
            super("Save '" + getPartName() + "' changes...");
            setUser(true);
        }

        @Override
        protected IStatus run(DBRProgressMonitor monitor) {
            try {
                final DBECommandContext commandContext = getCommandContext();
                if (commandContext != null && commandContext.isDirty()) {
                    Map<String, Object> options = new HashMap<>();
                    options.put(DBPScriptObject.OPTION_OBJECT_SAVE, true);
                    success = saveCommandContext(monitor, options);
                } else {
                    success = true;
                }

                if (success) {
                    // Save nested editors
                    ProxyProgressMonitor proxyMonitor = new ProxyProgressMonitor(monitor);
                    for (IEditorPart editor : editorMap.values()) {
                        if (editor.isDirty()) {
                            editor.doSave(proxyMonitor);
                        }
                        if (monitor.isCanceled()) {
                            success = false;
                            return Status.CANCEL_STATUS;
                        }
                    }
                    if (proxyMonitor.isCanceled()) {
                        success = false;
                        return Status.CANCEL_STATUS;
                    }
                }

                return success ? Status.OK_STATUS : Status.CANCEL_STATUS;
            } catch (Throwable e) {
                success = false;
                log.error(e);
                return GeneralUtils.makeExceptionStatus(e);
            } finally {
                if (success == null) {
                    success = true;
                }
            }
        }
    }

    // This is used by extensions to determine whether this entity is another entity container (e.g. for ERD)
    @Override
    public boolean isEntityContainer(DBSObjectContainer object) {
        try {
            Class<? extends DBSObject> childType = object.getPrimaryChildType(null);
            return childType != null && DBSTable.class.isAssignableFrom(childType);
        } catch (DBException e) {
            log.error(e);
            return false;
        }
    }

    @Override
    public boolean isRelationalObject(DBSObject object) {
        DBPDataSource dataSource = object.getDataSource();
        return dataSource != null && dataSource.getInfo().supportsReferentialIntegrity();
    }

    @Override
    public String toString() {
        DBSObject databaseObject = getDatabaseObject();
        return databaseObject == null ? super.toString() : DBUtils.getObjectFullName(databaseObject, DBPEvaluationContext.UI);
    }
}
