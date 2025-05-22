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
package org.jkiss.dbeaver.model.navigator;

import org.apache.commons.jexl3.JexlContext;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeFolder;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeItem;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeNode;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.DBSWrapper;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableColumn;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.AlphanumericComparator;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.nio.file.Path;
import java.util.*;

/**
 * Navigator helper functions
 */
public class DBNUtils {

    private static final Log log = Log.getLog(DBNUtils.class);

    public static DBNDatabaseNode getNodeByObject(DBSObject object) {
        DBNModel model = getNavigatorModel(object);
        return model == null ? null : model.getNodeByObject(object);
    }

    @Nullable
    public static DBNModel getNavigatorModel(DBSObject object) {
        DBPProject project = DBUtils.getObjectOwnerProject(object);
        if (project == null) {
            return null;
        }
        return project.getNavigatorModel();
    }

    public static DBNDatabaseNode getNodeByObject(DBRProgressMonitor monitor, DBSObject object, boolean addFiltered) {
        DBNModel model = getNavigatorModel(object);
        return model == null ? null : model.getNodeByObject(monitor, object, addFiltered);
    }

    public static DBNDatabaseNode getChildFolder(DBRProgressMonitor monitor, DBNDatabaseNode node, Class<?> folderType) {
        try {
            for (DBNDatabaseNode childNode : node.getChildren(monitor)) {
                if (!(childNode instanceof DBNDatabaseFolder folder)) {
                    continue;
                }
                final DBXTreeFolder meta = folder.getMeta();
                if (!CommonUtils.isEmpty(meta.getType())) {
                    final Class<?> objectClass = meta.getSource().getObjectClass(meta.getType());
                    if (objectClass != null && folderType.isAssignableFrom(objectClass)) {
                        return childNode;
                    }
                }
            }
        } catch (DBException e) {
            log.error("Error reading child folder", e);
        }
        return null;
    }

    public static DBNNode[] getNodeChildrenFiltered(DBRProgressMonitor monitor, DBNNode node, boolean forTree) throws DBException {
        DBNNode[] children = node.getChildren(monitor);
        if (children != null && children.length > 0) {
            children = filterNavigableChildren(children, forTree);
        }
        return children;
    }

    public static DBNNode[] filterNavigableChildren(DBNNode[] children, boolean forTree)
    {
        if (ArrayUtils.isEmpty(children)) {
            return children;
        }
        DBNNode[] result;
        if (forTree) {
            List<DBNNode> filtered = new ArrayList<>();
            for (DBNNode node : children) {
                if (node instanceof DBPHiddenObject hiddenObject && hiddenObject.isHidden()) {
                    continue;
                }
                if (node instanceof DBNDatabaseNode dbNode && !dbNode.getMeta().isNavigable()) {
                    continue;
                }
                filtered.add(node);
            }
            result = filtered.toArray(new DBNNode[0]);
        } else {
            result = children;
        }
        sortNodes(result);
        return result;
    }

    private static void sortNodes(DBNNode[] children) {
        if (children.length == 0) {
            return;
        }

        DBPPreferenceStore prefStore = DBWorkbench.getPlatform().getPreferenceStore();
        DBNNode firstChild = children[0];
        // Sort children is we have this feature on in preferences
        // and if children are not folders

        if (firstChild.getAdapter(Path.class) != null) {
            Arrays.sort(children, NodeFolderComparator.INSTANCE);
            return;
        }

        if (firstChild instanceof DBNContainer) {
            return;
        }

        if (firstChild instanceof DBNDatabaseItem item && item.getObject() instanceof DBSTableColumn) {
            if (prefStore.getBoolean(ModelPreferences.NAVIGATOR_SORT_ALPHABETICALLY)) {
                Arrays.sort(children, NodeNameComparator.INSTANCE);
            }
            return;
        }

        Comparator<DBNNode> comparator = null;

        if (prefStore.getBoolean(ModelPreferences.NAVIGATOR_SORT_ALPHABETICALLY)) {
            comparator = NodeNameComparator.INSTANCE;
        }

        if (prefStore.getBoolean(ModelPreferences.NAVIGATOR_SORT_FOLDERS_FIRST) || isMergedEntity(firstChild)) {
            comparator = NodeFolderComparator.INSTANCE.thenComparing((o1, o2) -> {
                if (o1 instanceof DBNContainer && o2 instanceof DBNContainer) {
                    return 0;
                } else if (o1 instanceof DBNContainer) {
                    return 1;
                } else if (o2 instanceof DBNContainer) {
                    return -1;
                }
                return AlphanumericComparator.getInstance()
                    .compare(o1.getNodeDisplayName(), o2.getNodeDisplayName());
            });
        }
        if (comparator != null) {
            Arrays.sort(children, comparator);
        }
    }

    private static boolean isMergedEntity(DBNNode node) {
        return node instanceof DBNDatabaseNode dbNode &&
           dbNode.getObject() instanceof DBSEntity &&
           dbNode.getObject().getDataSource().getContainer().getNavigatorSettings().isMergeEntities();
    }

    public static boolean isDefaultElement(Object element)
    {
        if (element instanceof DBSWrapper wrapper) {
            DBSObject object = wrapper.getObject();
            if (object != null) {
                // Get default context from default instance - not from active object
                DBCExecutionContext defaultContext = DBUtils.getDefaultContext(object.getDataSource(), false);
                if (defaultContext != null) {
                    DBCExecutionContextDefaults<?, ?> contextDefaults = defaultContext.getContextDefaults();
                    if (contextDefaults != null) {
                        return Objects.equals(contextDefaults.getDefaultCatalog(), object)
                            || Objects.equals(contextDefaults.getDefaultSchema(), object);
                    }
                }
            }
        } else if (element instanceof DBNProject nodeProject) {
            return nodeProject.getProject() == DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        }
        return false;
    }

    @NotNull
    public static String getLastNodePathSegment(@NotNull String path) {
        int divPos = path.lastIndexOf('/');
        return divPos == -1 ? path : path.substring(divPos + 1);
    }

    public static boolean isReadOnly(DBNNode node)
    {
        return node instanceof DBNDatabaseNode dbNode &&
            !(node instanceof DBNDataSource) &&
            !dbNode.getDataSourceContainer().hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_METADATA);
    }

    public static boolean isFolderNode(DBNNode node) {
        return node.allowsChildren();
    }

    public static DBXTreeItem getValidItemsMeta(DBRProgressMonitor monitor, DBNDatabaseNode dbNode) throws DBException {
        DBXTreeItem itemsMeta = dbNode.getItemsMeta();
        if (itemsMeta != null && itemsMeta.isOptional()) {
            // Maybe we need nested item.
            // Specifically this handles optional catalogs and schemas in Generic driver
            Class<?> expectedChildrenType = dbNode.getChildrenOrFolderClass(itemsMeta);
            if (expectedChildrenType != null) {
                List<DBXTreeNode> childMetas = itemsMeta.getChildren(dbNode);
                if (childMetas.size() == 1 && childMetas.get(0) instanceof DBXTreeItem nestedMeta) {
                    Class<?> expectedNestedType = dbNode.getChildrenOrFolderClass(nestedMeta);
                    DBNDatabaseNode[] nodeChildren = dbNode.getChildren(monitor);
                    if (nodeChildren.length > 0 &&
                        !expectedChildrenType.isInstance(nodeChildren[0].getObject()))
                    {
                        // Note: We should've check expectedNestedType.isInstance(nodeChildren[0].getObject())
                        // but we cannot. Because after filters are applied child nodes may contain deeper nested type
                        // FIXME: support it for databases which support only tables
                        itemsMeta = nestedMeta;
                    }
                }
            }
        }
        return itemsMeta;
    }

    private static class NodeFolderComparator implements Comparator<DBNNode> {
        static final NodeFolderComparator INSTANCE = new NodeFolderComparator();

        @Override
        public int compare(DBNNode node1, DBNNode node2) {
            int first = isFolderNode(node1) ? -1 : 1;
            int second = isFolderNode(node2) ? -1 : 1;
            return first - second;
        }

        private static boolean isFolderNode(DBNNode node) {
            if (node instanceof DBNDatabaseNode dbn && dbn.getObject() instanceof DBPObjectWithOrdinalPosition) {
                return false;
            }
            return node instanceof DBNContainer || node.allowsChildren();
        }
    }

    private static class NodeNameComparator implements Comparator<DBNNode> {
        static NodeNameComparator INSTANCE = new NodeNameComparator();

        @Override
        public int compare(DBNNode node1, DBNNode node2) {
            return node1.getNodeDisplayName().compareToIgnoreCase(node2.getNodeDisplayName());
        }
    }

    @Nullable
    public static <T> T getParentOfType(@NotNull Class<T> type, DBNNode node)
    {
        if (node == null) {
            return null;
        }
        for (DBNNode parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (type.isInstance(parent)) {
                return type.cast(parent);
            } else if (parent instanceof DBNRoot) {
                break;
            }
        }
        return null;
    }

    public static JexlContext makeContext(final DBNNode node) {
        return new JexlContext() {

            @Override
            public Object get(String name) {
                if (node instanceof DBNDatabaseNode) {
                    switch (name) {
                        case "object":
                            return ((DBNDatabaseNode) node).getValueObject();
                        case "dataSource":
                            return ((DBNDatabaseNode) node).getDataSource();
                        case "connected":
                            return ((DBNDatabaseNode) node).getDataSource() != null;
                    }
                }
                return null;
            }

            @Override
            public void set(String name, Object value) {
                log.warn("Set is not implemented in DBX model");
            }

            @Override
            public boolean has(String name) {
                return node instanceof DBNDatabaseNode dbNode && name.equals("object")
                    && dbNode.getValueObject() != null;
            }
        };
    }

    /**
     * The method decode symbols('%2F','%25')
     *
     * @param nodePath - path
     * @return - node path object
     */
    public static String decodeNodePath(@NotNull String nodePath) {
        return nodePath.replace("%2F", "/").replace("%25", "%");
    }

    /**
     * The method encode symbols('/','%')
     *
     * @param path - path
     * @return - string path segment
     */
    public static String encodeNodePath(@NotNull String path) {
        return path.replace("%", "%25").replace("/", "%2F");
    }


    public static void disposeNode(DBNNode node, boolean reflect) {
        node.dispose(reflect);
    }

    /**
     * Get default node to open. Useful in case of open flat files with one table.
     */
    @Nullable
    public static DBNDatabaseNode getDefaultDatabaseNodeToOpen(DBRProgressMonitor monitor, DBPDataSource dataSource) throws DBException {
        List<DBSEntity> entities = new ArrayList<>();
        if (dataSource instanceof DBSObjectContainer container) {
            getConnectionEntities(monitor, container, entities);
        }

        DBSObject objectToOpen;
        if (entities.size() == 1) {
            objectToOpen = entities.get(0);
        } else {
            if (entities.size() > 1) {
                objectToOpen = entities.get(0).getParentObject();
            } else {
                objectToOpen = dataSource;
            }
        }
        if (objectToOpen == null) {
            throw new DBException("No entities found in file datasource");
        }
        return DBNUtils.getNodeByObject(monitor, objectToOpen, true);
    }

    private static void getConnectionEntities(
        DBRProgressMonitor monitor,
        DBSObjectContainer container,
        List<DBSEntity> entities
    ) throws DBException {
        for (DBSObject child : container.getChildren(monitor)) {
            if (child instanceof DBSEntity entity) {
                entities.add(entity);
            } else if (child instanceof DBSObjectContainer oc) {
                getConnectionEntities(monitor, oc, entities);
            }
        }
    }
}
