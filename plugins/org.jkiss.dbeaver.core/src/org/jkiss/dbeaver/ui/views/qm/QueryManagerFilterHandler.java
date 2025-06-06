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
package org.jkiss.dbeaver.ui.views.qm;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Map;

public class QueryManagerFilterHandler extends AbstractHandler implements IElementUpdater {
    public static final String ID = "org.jkiss.dbeaver.core.qm.filter";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        QueryManagerView view = UIUtils.findView(HandlerUtil.getActiveWorkbenchWindow(event), QueryManagerView.class);
        if (view != null) {
            view.setFilterPanelVisible(!view.isFilterPanelVisible());
        }
        return null;
    }


    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        QueryManagerView view = UIUtils.findView(window, QueryManagerView.class);
        element.setChecked(view != null && view.isFilterPanelVisible());
    }
}