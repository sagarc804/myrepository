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
package org.jkiss.dbeaver.model.navigator;

import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.access.DBAObject;
import org.jkiss.dbeaver.model.dpi.DPIClientObject;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.messages.ModelMessages;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeFolder;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeItem;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeNode;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeObject;
import org.jkiss.dbeaver.model.runtime.DBRProgressListener;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableParametrized;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSPackage;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSSequence;
import org.jkiss.dbeaver.runtime.DBInterruptedException;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.BeanUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

/**
 * DBNDatabaseNode
 */
public abstract class DBNDatabaseNode extends DBNNode implements DBNLazyNode, DBSWrapper, DBPContextProvider, DBPDataSourceContainerProvider {

    private static final DBNDatabaseNode[] EMPTY_NODES = new DBNDatabaseNode[0];

    private volatile boolean locked;
    protected volatile DBNDatabaseNode[] childNodes;
    private boolean filtered;

    protected DBNDatabaseNode(DBNNode parentNode) {
        super(parentNode);
    }

    void registerNode() {
        DBNModel model = getModel();
        if (model != null) {
            model.addNode(this, false);
        }
    }

    void unregisterNode(boolean reflect) {
        DBNModel model = getModel();
        if (model != null) {
            model.removeNode(this, reflect);
        }
    }

    @Override
    protected void dispose(boolean reflect) {
        clearChildren(reflect);
        super.dispose(reflect);
    }

    @Override
    public String getNodeType() {
        if (getObject() == null) {
            return "";
        }
        DBXTreeNode meta = getMeta();
        return meta == null ? "" : meta.getNodeTypeLabel(getObject().getDataSource(), null); //$NON-NLS-1$
    }

    @Override
    public String getNodeDisplayName() {
        return getPlainNodeName(false, true);
    }

    /**
     * Get name with parameters
     *
     * @param useSimpleName do not append any qualifiers to the name. Usually sued for functions like rename
     * @param showDefaults  return some default value if actual name is empty. otherwise returns null
     */
    public String getPlainNodeName(boolean useSimpleName, boolean showDefaults) {
        DBSObject object = getObject();
        if (object == null) {
            return showDefaults ? DBConstants.NULL_VALUE_LABEL : null;
        }
        String objectName;
        if (!useSimpleName) {
            if (object instanceof DBPOverloadedObject) {
                objectName = ((DBPOverloadedObject) object).getOverloadedName();
            } else if (isVirtual() &&
                getParentNode() instanceof DBNDatabaseNode &&
                object.getParentObject() != null &&
                object.getParentObject() != ((DBNDatabaseNode) getParentNode()).getValueObject())
            {
                objectName = object.getParentObject().getName() + "." + object.getName();
            } else {
                if (object instanceof DBSEntity && object.getDataSource().getContainer().getNavigatorSettings().isMergeEntities()) {
                    objectName = DBUtils.getObjectFullName(object, DBPEvaluationContext.UI);
                } else {
                    objectName = object.getName();
                }
            }
        } else {
            objectName = object.getName();
        }

        if (showDefaults && CommonUtils.isEmpty(objectName)) {
            objectName = object.toString();
            if (CommonUtils.isEmpty(objectName)) {
                objectName = object.getClass().getName() + "@" + object.hashCode(); //$NON-NLS-1$
            }
        }
/*
        if (object instanceof DBPUniqueObject) {
            String uniqueName = ((DBPUniqueObject) object).getUniqueName();
            if (!uniqueName.equals(objectName)) {
                if (uniqueName.startsWith(objectName)) {
                    uniqueName = uniqueName.substring(objectName.length());
                }
                objectName += " (" + uniqueName + ")";
            }
        }
*/
        return objectName;
    }

    @Override
    public String getNodeBriefInfo() {
        if (getObject() instanceof DBPToolTipObject) {
            return ((DBPToolTipObject) getObject()).getObjectToolTip();
        } else {
            return super.getNodeBriefInfo();
        }
    }

    @Override
    public String getNodeFullName() {
        if (getObject() instanceof DBPQualifiedObject) {
            return ((DBPQualifiedObject) getObject()).getFullyQualifiedName(DBPEvaluationContext.UI);
        } else {
            return super.getNodeFullName();
        }
    }

    @Override
    public String getNodeDescription() {
        return getObject() == null ? null : getObject().getDescription();
    }

    @Override
    public DBPImage getNodeIcon() {
        final DBSObject object = getObject();
        DBPImage image = DBValueFormatting.getObjectImage(object, false);
        if (image == null) {
            DBXTreeNode meta = getMeta();
            if (meta != null) {
                image = meta.getIcon(this);
            }
        }
        if (image != null && object instanceof DBPStatefulObject) {
            image = DBNModel.getStateOverlayImage(image, ((DBPStatefulObject) object).getObjectState());
        }
        return image;
    }

    @Override
    public boolean allowsChildren() {
        return !isDisposed() && (this.getMeta().hasChildren(this) || hasDynamicStructChildren());
    }

    @Override
    public boolean allowsNavigableChildren() {
        return !isDisposed() && this.getMeta().hasChildren(this, true);
    }

    public boolean hasChildren(DBRProgressMonitor monitor, DBXTreeNode childType)
        throws DBException {
        if (isDisposed()) {
            return false;
        }
        DBNDatabaseNode[] children = getChildren(monitor);
        if (!ArrayUtils.isEmpty(children)) {
            for (DBNDatabaseNode child : children) {
                if (child.getMeta() == childType) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public DBNDatabaseNode[] getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        boolean needsLoad;
        synchronized (this) {
            needsLoad = childNodes == null && hasChildren(false);
        }
        if (needsLoad && !monitor.isForceCacheUsage()) {
            if (this.initializeNode(monitor, null)) {
                final List<DBNDatabaseNode> tmpList = new ArrayList<>();
                this.filtered = false;
                loadChildren(monitor, getMeta(), null, tmpList, this, true);
                if (!monitor.isCanceled()) {
                    synchronized (this) {
                        if (tmpList.isEmpty()) {
                            this.childNodes = EMPTY_NODES;
                        } else {
                            this.childNodes = tmpList.toArray(new DBNDatabaseNode[0]);
                        }
                    }
                    this.afterChildRead();
                }
            } else {
                throw new DBInterruptedException("Connection was canceled");
            }
        }
        return childNodes;
    }

    protected void afterChildRead() {
        // Do nothing
    }

    DBNDatabaseNode[] getChildNodes() {
        return childNodes;
    }

    boolean hasChildItem(DBSObject object) {
        if (childNodes != null) {
            for (DBNDatabaseNode child : childNodes) {
                if (child.getObject() == object) {
                    return true;
                }
            }
        }
        return false;
    }

    void addChildItem(DBSObject object) {
        DBXTreeNode metaChildren = getItemsMeta();
        if (metaChildren == null) {
            // There is no item meta. Maybe we are under some folder structure
            // Let's find a folder with right type
            metaChildren = getFolderMeta(object.getClass());
        }
        if (metaChildren != null) {
            final DBNDatabaseItem newChild = new DBNDatabaseItem(this, metaChildren, object, false);
            synchronized (this) {
                childNodes = ArrayUtils.add(DBNDatabaseNode.class, childNodes, newChild);
            }
            getModel().fireNodeEvent(new DBNEvent(this, DBNEvent.Action.ADD, DBNEvent.NodeChange.LOAD, newChild));
        } else {
            log.error("Cannot add child item to " + getNodeDisplayName() + ". Conditions doesn't met"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    void removeChildItem(DBSObject object) {
        DBNNode childNode = null;
        synchronized (this) {
            if (!ArrayUtils.isEmpty(childNodes)) {
                for (int i = 0; i < childNodes.length; i++) {
                    final DBNDatabaseNode child = childNodes[i];
                    if (child.getObject() == object) {
                        childNode = child;
                        childNodes = ArrayUtils.remove(DBNDatabaseNode.class, childNodes, i);
                        break;
                    }
                }
            }
        }
        if (childNode != null) {
            DBNUtils.disposeNode(childNode, true);
        }
    }

    @Override
    void clearNode(boolean reflect) {
        clearChildren(reflect);
    }

    /**
     * Reorder children nodes
     */
    public void updateChildrenOrder(boolean reflect) {
        try {
            refreshNodeContent(new VoidProgressMonitor(), getObject(), this, reflect);
        } catch (DBException e) {
            log.error("Error reordering node children", e);
        }

    }

    @Override
    public boolean needsInitialization() {
        return childNodes == null && hasChildren(false);
    }

    @Override
    public boolean isLocked() {
        return locked || super.isLocked();
    }

    public boolean initializeNode(DBRProgressMonitor monitor, DBRProgressListener onFinish) throws DBException {
        if (onFinish != null) {
            onFinish.onTaskFinished(Status.OK_STATUS);
        }
        return true;
    }

    /**
     * Refreshes node.
     * If refresh cannot be done in this level then refreshes parent node.
     * Do not actually changes navigation tree. If some underlying object is refreshed it must fire DB model
     * event which will cause actual tree nodes refresh. Underlying object could present multiple times in
     * navigation model - each occurrence will be refreshed then.
     *
     * @param monitor progress monitor
     * @param source  source object
     * @return real refreshed node or null if nothing was refreshed
     * @throws DBException on any internal exception
     */
    @Override
    public DBNNode refreshNode(DBRProgressMonitor monitor, Object source) throws DBException {
        if (isLocked()) {
            log.warn("Attempt to refresh locked node '" + getNodeDisplayName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        DBSObject object = getObject();
        if (object instanceof DBPRefreshableObject) {
            DBPDataSource dataSource = object.getDataSource();
            if (object.isPersisted() && dataSource != null) {
                DBSObject[] newObject = new DBSObject[1];
                DBExecUtils.tryExecuteRecover(monitor, dataSource, param -> {
                    try {
                        newObject[0] = ((DBPRefreshableObject) object).refreshObject(monitor);
                    } catch (DBException e) {
                        throw new InvocationTargetException(e);
                    }
                });
                if (newObject[0] == null) {
                    if (parentNode instanceof DBNDatabaseNode) {
                        ((DBNDatabaseNode) parentNode).removeChildItem(object);
                    }
                    return null;
                } else {
                    refreshNodeContent(monitor, newObject[0], source, true);
                    return this;
                }
            } else {
                // Not persisted node - nothing to refresh
                getModel().fireNodeUpdate(source, this, DBNEvent.NodeChange.REFRESH);
                return this;
            }
        } else {
            return super.refreshNode(monitor, source);
        }
    }

    private void refreshNodeContent(final DBRProgressMonitor monitor, DBSObject newObject, Object source, boolean reflect)
        throws DBException {
        if (isDisposed()) {
            return;
        }
        this.locked = true;
        DBNModel model = getModel();
        try {
            if (newObject != getObject()) {
                reloadObject(monitor, newObject);
            }

            this.reloadChildren(monitor, source, reflect);

            if (reflect) model.fireNodeUpdate(source, this, DBNEvent.NodeChange.REFRESH);
        } finally {
            this.locked = false;
        }
    }

    private void clearChildren(boolean reflect) {
        DBNDatabaseNode[] childrenCopy;
        synchronized (this) {
            childrenCopy = childNodes == null ? null : Arrays.copyOf(childNodes, childNodes.length);
            childNodes = null;
        }
        if (childrenCopy != null) {
            for (DBNNode child : childrenCopy) {
                DBNUtils.disposeNode(child, reflect);
            }
        }
    }

    private void loadChildren(
        DBRProgressMonitor monitor,
        final DBXTreeNode meta,
        final DBNDatabaseNode[] oldList,
        final List<DBNDatabaseNode> toList,
        Object source,
        boolean reflect
    ) throws DBException {
        if (monitor.isCanceled()) {
            return;
        }

        List<DBXTreeNode> childMetas = meta.getChildren(this);
        if (CommonUtils.isEmpty(childMetas)) {
            // Get top-most parent node
            if (!isDynamicStructObject()) {
                return;
            }
            DBNDatabaseDynamicItem[] dsc = getDynamicStructChildren();
            if (dsc == null) {
                return;
            }
            Collections.addAll(toList, dsc);
            return;
        }
        DBSObject object = getObject();
        if (object == null) {
            // disposed?
            return;
        }
        monitor.beginTask(ModelMessages.model_navigator_load_items_, childMetas.size());
        DBPDataSourceContainer container = getDataSourceContainer();
        DBNBrowseSettings navSettings = container.getNavigatorSettings();
        final boolean showSystem = navSettings.isShowSystemObjects();
        final boolean showOnlyEntities = navSettings.isShowOnlyEntities();
        final boolean hideFolders = navSettings.isHideFolders();
        boolean mergeEntities = navSettings.isMergeEntities();
        boolean supportsOptionalFolders = false;
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource instanceof DBPDataSourceWithOptionalElements) {
            supportsOptionalFolders = ((DBPDataSourceWithOptionalElements) dataSource).hasOptionalFolders();
        }

        for (DBXTreeNode child : childMetas) {
            if (monitor.isCanceled()) {
                break;
            }
            monitor.subTask(ModelMessages.model_navigator_load_ + " " + child.getChildrenTypeLabel(object.getDataSource(), null));
            if (showOnlyEntities && !isEntityMeta(child)) {
                continue;
            }

            if (child instanceof DBXTreeItem item) {
                /*if (hideSchemas && isSchemaItem(item)) {
                    // Merge
                } else */{
                    boolean isLoaded = loadTreeItems(monitor, item, oldList, toList, source, showSystem, hideFolders, mergeEntities, reflect);
                    if (!isLoaded && item.isOptional() && item.getRecursiveLink() == null) {
                        // This may occur only if no child nodes was read
                        // Then we try to go on next DBX level
                        loadChildren(monitor, item, oldList, toList, source, reflect);
                    }
                }
            } else if (child instanceof DBXTreeFolder treeFolder) {
                if (hideFolders || ((mergeEntities || supportsOptionalFolders) && treeFolder.isOptional())) {
                    if (child.isVirtual() || treeFolder.isAdminFolder()) {
                        continue;
                    }
                    // Fall down
                    loadChildren(monitor, child, oldList, toList, source, reflect);
                } else {
                    String optionalPath = treeFolder.getOptionalItem();
                    if (optionalPath != null) {
                        DBXTreeItem optionalItem = treeFolder.getChildByPath(optionalPath);
                        if (optionalItem == null) {
                            log.error("Optional item '" + optionalPath + "' not found in folder " + child.getId());
                        } else {
                            Object optionalValue = extractPropertyValue(monitor, getValueObject(), optionalItem);
                            if (optionalValue == null || (optionalValue instanceof Collection && ((Collection<?>) optionalValue).isEmpty())) {
                                // Go on next DBX level
                                loadChildren(monitor, optionalItem, oldList, toList, source, reflect);
                                continue;
                            }
                        }
                    }

                    if (oldList == null) {
                        // Load new folders only if there are no old ones
                        toList.add(
                            new DBNDatabaseFolder(this, (DBXTreeFolder) child));
                    } else {
                        for (DBNDatabaseNode oldFolder : oldList) {
                            if (oldFolder.getMeta() == child) {
                                oldFolder.reloadChildren(monitor, source, reflect);
                                toList.add(oldFolder);
                                break;
                            }
                        }
                    }
                }
            } else if (child instanceof DBXTreeObject) {
                if (hideFolders) {
                    continue;
                }
                if (oldList == null) {
                    // Load new objects only if there are no old ones
                    toList.add(
                        new DBNDatabaseObject(this, (DBXTreeObject) child));
                } else {
                    for (DBNDatabaseNode oldObject : oldList) {
                        if (oldObject.getMeta().equals(child)) {
                            oldObject.reloadChildren(monitor, source, reflect);
                            toList.add(oldObject);
                            break;
                        }
                    }
                }
            } else {
                log.warn("Unsupported meta node type: " + child); //$NON-NLS-1$
            }
            monitor.worked(1);
        }
        monitor.done();

        if (reflect && filtered) {
            getModel().fireNodeUpdate(this, this, DBNEvent.NodeChange.REFRESH);
        }
    }

    public boolean isDynamicStructObject() {
        DBXTreeNode meta = getMeta();
        if (meta instanceof DBXTreeFolder folder) {
            List<DBXTreeNode> children = folder.getChildren(this);
            if (children.size() == 1) {
                meta = children.get(0);
            }
        }
        if (meta instanceof DBXTreeItem item) {
            Class<?> childrenClass = getChildrenClass(item);
            return childrenClass != null && DBSTypedObject.class.isAssignableFrom(childrenClass);
        }
        return false;
    }

    protected DBNDatabaseDynamicItem[] getDynamicStructChildren() {
        if (getObject() instanceof DBSTypedObject typedObject) {
            DBSDataType dataType = DBUtils.getDataType(getDataSource(), typedObject);
            if (dataType instanceof DBSEntity dtEntity) {
                try {
                    List<? extends DBSEntityAttribute> attributes = dtEntity.getAttributes(new VoidProgressMonitor());
                    if (attributes == null) {
                        return null;
                    }
                    return attributes.stream().map(o -> new DBNDatabaseDynamicItem(this, o))
                        .toArray(DBNDatabaseDynamicItem[]::new);
                } catch (DBException e) {
                    log.error(e);
                }
            }
        }
        return new DBNDatabaseDynamicItem[0];
    }

    protected boolean hasDynamicStructChildren() {
        if (getObject() instanceof DBSTypedObject typedObject) {
            DBSDataType dataType = DBUtils.getDataType(getDataSource(), typedObject);
            return isStructDataType(dataType);
        }
        return false;
    }

    private static boolean isStructDataType(DBSDataType dataType) {
        if (dataType == null) {
            return false;
        }
        try {
            return switch (dataType.getDataKind()) {
                case ARRAY -> isStructDataType(dataType.getComponentType(new VoidProgressMonitor()));
                case STRUCT -> dataType instanceof DBSEntity;
                default -> false;
            };
        } catch (Exception e) {
            log.debug(e);
            return false;
        }
    }

    private boolean isEntityMeta(DBXTreeNode node) {
        Class<?> nodeChildClass = null;
        if (node instanceof DBXTreeItem) {
            nodeChildClass = getChildrenClass((DBXTreeItem) node);
        } else if (node instanceof DBXTreeFolder) {
            nodeChildClass = getFolderChildrenClass((DBXTreeFolder) node);
        }
        if (nodeChildClass == null) {
            return false;
        }
        // Extra check for DBSDataType, DBSSequence, DBSPackage - in some databases they are entities but we don't wont them (PG, Oracle)
        return
            (DBSObjectContainer.class.isAssignableFrom(nodeChildClass) &&
                !DBSPackage.class.isAssignableFrom(nodeChildClass)) ||
            (DBSEntity.class.isAssignableFrom(nodeChildClass) &&
                !DBSDataType.class.isAssignableFrom(nodeChildClass) &&
                !DBSSequence.class.isAssignableFrom(nodeChildClass) &&
                !DBSPackage.class.isAssignableFrom(nodeChildClass)) ||
            DBSEntityAttribute.class.isAssignableFrom(nodeChildClass) ||
            DBSInstance.class.isAssignableFrom(nodeChildClass);
    }

    /**
     * Extract items using reflect api
     *
     * @param monitor progress monitor
     * @param meta    items meta info
     * @param toList previous child items
     * @param toList  list ot add new items   @return true on success
     * @param showSystem include system objects
     * @param reflect @return true on success
     * @throws DBException on any DB error
     */
    private boolean loadTreeItems(
        DBRProgressMonitor monitor,
        DBXTreeItem meta,
        final DBNDatabaseNode[] oldListCmp,
        final List<DBNDatabaseNode> toList,
        Object source,
        boolean showSystem,
        boolean hideFolders,
        boolean mergeEntities,
        boolean reflect)
        throws DBException {
        if (this.isDisposed())
        {
            // Property reading can take really long time so this node can be disposed at this moment -
            // check it
            return false;
        }
        // Read property using reflection
        final Object valueObject = getValueObject();
        if (valueObject == null) {
            return false;
        }
        final PropertyValueReader valueReader = new PropertyValueReader(monitor, meta, valueObject);
        DBPDataSource dataSource = getDataSource();
        if (dataSource != null) {
            DBExecUtils.tryExecuteRecover(monitor, dataSource, valueReader);
        } else {
            try {
                valueReader.run(monitor);
            } catch (InvocationTargetException e) {
                throw new DBCException("Error reading child elements", e.getTargetException());
            } catch (InterruptedException e) {
                return false;
            }
        }
        final Object propertyValue = valueReader.propertyValue;
        if (propertyValue == null) {
            return false;
        }
        if (!(propertyValue instanceof Collection<?>)) {
            log.warn("Bad property '" + meta.getPropertyName() + "' value: " + propertyValue.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        final DBSObjectFilter filter = getNodeFilter(meta, false);
        this.filtered = filter != null && !filter.isNotApplicable();
        if (filter != null && dataSource != null) {
            filter.setCaseSensitive(dataSource.getSQLDialect().hasCaseSensitiveFiltration());
        }
        final Collection<?> itemList = (Collection<?>) propertyValue;
        if (itemList.isEmpty()) {
            return false;
        }
        if (this.isDisposed()) {
            // Property reading can take really long time so this node can be disposed at this moment -
            // check it
            return false;
        }
        List<DBNDatabaseNode> oldList = new LinkedList<>();
        if (oldListCmp != null) {
            Collections.addAll(oldList, oldListCmp);
        }
        for (Object childItem : itemList) {
            if (childItem == null) {
                continue;
            }
            if (!(childItem instanceof DBSObject)) {
                log.warn("Bad item type: " + childItem.getClass().getName()); //$NON-NLS-1$
                continue;
            }
            if (DBUtils.isHiddenObject(childItem)) {
                // Skip hidden objects
                continue;
            }
            if ((!showSystem && DBUtils.isSystemObject(childItem)) &&
                !(itemList.size() == 1 && (childItem instanceof DBSSchema || childItem instanceof DBSCatalog))) { // Show system catalog/schema in case when only one object in the itemList
                // Skip system objects
                continue;
            }
            if (hideFolders && (childItem instanceof DBAObject || childItem instanceof DBPSystemInfoObject)) {
                // Skip all DBA objects
                continue;
            }
            if (mergeEntities && childItem instanceof DBSSchema) {
                // Skip schemas in merge entities mode
                continue;
            }
            if (filter != null && !filter.matches(((DBSObject) childItem).getName())) {
                // Doesn't match filter
                continue;
            }
            DBSObject object = (DBSObject) childItem;
            boolean added = false;
            if (!oldList.isEmpty()) {
                // Check that new object is a replacement of old one
                for (Iterator<DBNDatabaseNode> iterator = oldList.iterator(); iterator.hasNext(); ) {
                    DBNDatabaseNode oldChild = iterator.next();
                    if (oldChild.getMeta() == meta && equalObjects(oldChild.getObject(), object)) {
                        boolean updated = oldChild.reloadObject(monitor, object);

                        if (oldChild.hasChildren(false) && !oldChild.needsInitialization()) {
                            // Refresh children recursive
                            oldChild.reloadChildren(monitor, source, reflect);
                        }
                        if (updated && reflect) {
                            // FIXME: do not update all refreshed items in (it is too expensive)
                            //getModel().fireNodeUpdate(source, oldChild, DBNEvent.NodeChange.REFRESH);
                        }

                        toList.add(oldChild);
                        added = true;
                        iterator.remove();
                        break;
                    }
                }
            }
            if (!added) {
                // Simply add new item
                DBNDatabaseItem treeItem = new DBNDatabaseItem(this, meta, object, oldList != null);
                toList.add(treeItem);
            }
        }

        {
            // Now remove all non-existing items
            for (DBNDatabaseNode oldChild : oldList) {
                if (oldChild.getMeta() != meta) {
                    // Wrong type
                    continue;
                }
                boolean found = false;
                for (Object childItem : itemList) {
                    if (childItem instanceof DBSObject && equalObjects(oldChild.getObject(), (DBSObject) childItem)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // Remove old child object
                    DBNUtils.disposeNode(oldChild, true);
                }
            }
        }
        return true;
    }

    @Nullable
    @Override
    public DBCExecutionContext getExecutionContext() {
        if (!getDataSourceContainer().isConnected()) {
            return null;
        }
        return DBUtils.getDefaultContext(getObject(), true);
    }

    @NotNull
    public DBPDataSourceContainer getDataSourceContainer() {
        for (DBNNode p = getParentNode(); p != null; p = p.getParentNode()) {
            if (p instanceof DBNDataSource) {
                return ((DBNDataSource) p).getDataSourceContainer();
            }
        }
        throw new IllegalStateException("No parent datasource node");
    }

    @Nullable
    public DBPDataSource getDataSource() {
        DBSObject object = getObject();
        if (object != null) {
            return object.getDataSource();
        }
        if (parentNode instanceof DBNDatabaseNode) {
            return ((DBNDatabaseNode) parentNode).getDataSource();
        }
        throw new IllegalStateException("No datasource is associated with database node " + this);
    }

    public DBSObjectFilter getNodeFilter(DBXTreeItem meta, boolean firstMatch) {
        DBPDataSourceContainer dataSource = getDataSourceContainer();
        Class<?> childrenClass = this.getChildrenOrFolderClass(meta);
        if (childrenClass != null) {
            Object valueObject = getValueObject();
            DBSObject parentObject = null;
            if (valueObject instanceof DBSObject && !(valueObject instanceof DBPDataSource)) {
                parentObject = (DBSObject) valueObject;
            }
            return dataSource.getObjectFilter(childrenClass, parentObject, firstMatch);
        }
        return null;
    }

    public boolean setNodeFilter(DBXTreeItem meta, DBSObjectFilter filter, boolean saveConfiguration) {
        DBPDataSourceContainer dataSource = getDataSourceContainer();
        Class<?> childrenClass = this.getChildrenOrFolderClass(meta);
        if (childrenClass != null) {
            Object parentObject = getValueObject();
            if (parentObject instanceof DBPDataSource) {
                parentObject = null;
            }
            dataSource.setObjectFilter(
                childrenClass,
                (DBSObject) parentObject,
                filter);
            if (saveConfiguration) {
                dataSource.persistConfiguration();
            }
            return true;
        } else {
            log.error("Cannot detect child node type - can't save filter configuration");
            return false;
        }
    }

    @Override
    public boolean isFiltered() {
        return filtered;
    }

    @NotNull
    @Override
    public String getNodeId() {
        String nodeId = super.getNodeId();
        if (getObject() instanceof DBPObjectWithLongId longObject) {
            nodeId += "_" + longObject.getObjectId();
        }
        return nodeId;
    }

    @Deprecated
    @Override
    public String getNodeItemPath() {
        StringBuilder pathName = new StringBuilder(100);

        for (DBNNode node = this; node instanceof DBNDatabaseNode; node = node.getParentNode()) {
            if (node instanceof DBNDataSource) {
                if (pathName.length() > 0) {
                    pathName.insert(0, '/');
                }
                pathName.insert(0, node.getNodeItemPath());
            } else if (node instanceof DBNDatabaseFolder) {
                if (pathName.length() > 0) {
                    pathName.insert(0, '/');
                }
                DBXTreeFolder folderMeta = ((DBNDatabaseFolder) node).getMeta();
                String type = folderMeta.getIdOrType();
                if (CommonUtils.isEmpty(type)) {
                    type = node.getName();
                }
                pathName.insert(0, type);
            }
            if (!(node instanceof DBNDatabaseItem) && !(node instanceof DBNDatabaseObject)) {
                // skip folders
                continue;
            }

            if (pathName.length() > 0) {
                pathName.insert(0, '/');
            }
            pathName.insert(0, DBNUtils.encodeNodePath(node.getNodeDisplayName()));
        }
        return pathName.toString();
    }

    private void reloadChildren(DBRProgressMonitor monitor, Object source, boolean reflect)
        throws DBException {
        DBNDatabaseNode[] oldChildren;
        synchronized (this) {
            if (childNodes == null) {
                // Nothing to reload
                return;
            }
            oldChildren = Arrays.copyOf(childNodes, childNodes.length);
        }
        List<DBNDatabaseNode> newChildren = new ArrayList<>();
        this.filtered = false;
        loadChildren(monitor, getMeta(), oldChildren, newChildren, source, reflect);
        synchronized (this) {
            childNodes = newChildren.toArray(new DBNDatabaseNode[0]);
        }
    }

    protected static boolean equalObjects(DBSObject object1, DBSObject object2) {
        if (object1 == object2) {
            return true;
        }
        if (object1 == null || object2 == null) {
            return false;
        }
        while (object1 != null && object2 != null) {
            if (object1.getClass() != object2.getClass() ||
                !CommonUtils.equalObjects(DBUtils.getObjectUniqueName(object1), DBUtils.getObjectUniqueName(object2))) {
                return false;
            }
            object1 = object1.getParentObject();
            object2 = object2.getParentObject();
        }
        return true;
    }

    public abstract Object getValueObject();

    @NotNull
    public abstract DBXTreeNode getMeta();

    public DBXTreeItem getItemsMeta() {
        List<DBXTreeNode> metaChildren = getMeta().getChildren(this);
        if (metaChildren != null) {
            for (DBXTreeNode cn : metaChildren) {
                if (cn instanceof DBXTreeItem) {
                    return (DBXTreeItem) cn;
                }
            }
        }
        return null;
    }

    public DBXTreeFolder getFolderMeta(Class<?> childType) {
        List<DBXTreeNode> metaChildren = getMeta().getChildren(this);
        if (metaChildren != null) {
            for (DBXTreeNode cn : metaChildren) {
                if (cn instanceof DBXTreeFolder && childType.getName().equals(((DBXTreeFolder) cn).getType())) {
                    return (DBXTreeFolder) cn;
                }
            }
        }
        return null;
    }

    protected abstract boolean reloadObject(DBRProgressMonitor monitor, DBSObject object);

    public List<Class<?>> getChildrenTypes(DBXTreeNode useMeta) {
        List<DBXTreeNode> childMetas = useMeta == null ? getMeta().getChildren(this) : Collections.singletonList(useMeta);
        if (CommonUtils.isEmpty(childMetas)) {
            return Collections.emptyList();
        } else {
            List<Class<?>> result = new ArrayList<>();
            for (DBXTreeNode childMeta : childMetas) {
                if (childMeta instanceof DBXTreeItem) {
                    Class<?> childrenType = getChildrenClass((DBXTreeItem) childMeta);
                    if (childrenType != null) {
                        result.add(childrenType);
                    }
                }
            }
            return result;
        }
    }

    public Class<?> getChildrenClass(DBXTreeItem childMeta) {
        if (childMeta == null) {
            log.debug("Null child meta specified");
            return null;
        }
        Object valueObject = getValueObject();
        if (valueObject == null) {
            return null;
        }
        Method getter = childMeta.getPropertyReadMethod(valueObject.getClass());
        if (getter == null) {
            return null;
        }
        Type propType = getter.getGenericReturnType();
        return BeanUtils.getCollectionType(propType);
    }

    public Class<?> getChildrenOrFolderClass(DBXTreeItem childMeta) {
        Class<?> childrenClass = this.getChildrenClass(childMeta);
        if (childrenClass == null && this instanceof DBNContainer) {
            childrenClass = ((DBNContainer) this).getChildrenClass();
        }
        return childrenClass;
    }

    ////////////////////////////////////////////////////////////////////////////////////
    // Reflection utils

    private static Object extractPropertyValue(DBRProgressMonitor monitor, Object object, DBXTreeItem meta)
        throws DBException {
        // Read property using reflection
        if (object == null || meta == null) {
            return null;
        }
        String propertyName = meta.getPropertyName();
        if (propertyName.contains(".")) {
            // Extract property recursively
            for (String fieldName : propertyName.split("\\.")) {
                object = extractDynamicPropertyValue(monitor, object, fieldName);
                if (object == null) {
                    return null;
                }
            }
            return object;
        }
        try {
            if (object instanceof DPIClientObject) {
                return ((DPIClientObject) object).dpiPropertyValue(monitor, meta.getPropertyName());
            }
            Method getter = meta.getPropertyReadMethod(object.getClass());
            if (getter == null) {
                log.warn("Can't find property '" + propertyName + "' read method in '" + object.getClass().getName() + "'");
                return null;
            }
            Class<?>[] paramTypes = getter.getParameterTypes();
            if (paramTypes.length == 0) {
                // No params - just read it
                return getter.invoke(object);
            } else if (paramTypes.length == 1 && paramTypes[0] == DBRProgressMonitor.class) {
                // Read with progress monitor
                return getter.invoke(object, monitor);
            } else {
                log.warn("Can't read property '" + propertyName + "' - bad method signature: " + getter.toString());
                return null;
            }
        } catch (IllegalAccessException ex) {
            log.warn("Error accessing items " + propertyName, ex);
            return null;
        } catch (InvocationTargetException ex) {
            if (ex.getTargetException() instanceof DBException) {
                throw (DBException) ex.getTargetException();
            }
            throw new DBException("Can't read " + propertyName + ": " + ex.getTargetException().getMessage(), ex.getTargetException());
        }
    }

    private static Object extractDynamicPropertyValue(DBRProgressMonitor monitor, Object object, String propertyName) throws DBException {
        try {
            Method getter = DBXTreeItem.findPropertyReadMethod(object.getClass(), propertyName);
            if (getter == null) {
                log.warn("Can't find dynamic property '" + propertyName + "' read method in '" + object.getClass().getName() + "'");
                return null;
            }
            Class<?>[] paramTypes = getter.getParameterTypes();
            if (paramTypes.length == 0) {
                // No params - just read it
                return getter.invoke(object);
            } else if (paramTypes.length == 1 && paramTypes[0] == DBRProgressMonitor.class) {
                // Read with progress monitor
                return getter.invoke(object, monitor);
            } else {
                log.warn("Can't read property '" + propertyName + "' - bad method signature: " + getter.toString());
                return null;
            }
        } catch (IllegalAccessException ex) {
            log.warn("Error accessing items " + propertyName, ex);
            return null;
        } catch (InvocationTargetException ex) {
            if (ex.getTargetException() instanceof DBException) {
                throw (DBException) ex.getTargetException();
            }
            throw new DBException("Can't read " + propertyName + ": " + ex.getTargetException().getMessage(), ex.getTargetException());
        }
    }

    public boolean isVirtual() {
        for (DBNNode node = this; node != null; node = node.getParentNode()) {
            if (node instanceof DBNDatabaseNode) {
                DBXTreeNode meta = ((DBNDatabaseNode) node).getMeta();
                if (meta != null && meta.isVirtual()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class PropertyValueReader implements DBRRunnableParametrized<DBRProgressMonitor> {
        private final DBRProgressMonitor monitor;
        private final DBXTreeItem meta;
        private final Object valueObject;
        private Object propertyValue;

        PropertyValueReader(DBRProgressMonitor monitor, DBXTreeItem meta, Object valueObject) {
            this.monitor = monitor;
            this.meta = meta;
            this.valueObject = valueObject;
        }

        @Override
        public void run(DBRProgressMonitor param) throws InvocationTargetException, InterruptedException {
            try {
                propertyValue = extractPropertyValue(monitor, valueObject, meta);
            } catch (DBException e) {
                throw new InvocationTargetException(e);
            }
        }
    }
}
