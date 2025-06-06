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
package org.jkiss.dbeaver.ui.controls.resultset.view.actions;

import org.eclipse.core.runtime.IStatus;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetContainer;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetContainerExt;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetErrorAction;

public final class GoToErrorAction implements IResultSetErrorAction {
    @Override
    public void perform(@NotNull IResultSetContainer container, @NotNull IStatus status) {
        if (container instanceof IResultSetContainerExt ext) {
            ext.showCurrentError();
        }
    }

    @Override
    public boolean isVisible(@NotNull IResultSetContainer container, @NotNull IStatus status) {
        return container instanceof IResultSetContainerExt && status.getException() != null;
    }
}
