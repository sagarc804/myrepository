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
package org.jkiss.dbeaver.ui.editors.sql.commands;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;

public class RefreshActiveSchemaHandler extends AbstractSchemaHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        var sqlEditor = getEditor(event);
        if (sqlEditor != null) {
            var context = getExecutionContext(sqlEditor);
            if (context instanceof DBCExecutionContextDefaults<?,?> ecd) {
                var schema = ecd.getDefaultSchema();
                if (schema != null) {
                    var node = getDatabaseNode(sqlEditor, schema);
                    if (node != null) {
                        refreshNode(node);
                    }
                }
            }
        }
        return null;
    }
}

