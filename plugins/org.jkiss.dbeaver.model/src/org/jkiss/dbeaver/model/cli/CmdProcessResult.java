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
package org.jkiss.dbeaver.model.cli;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

public class CmdProcessResult {
    public enum PostAction {
        START_INSTANCE,
        SHUTDOWN,
        ERROR,
        UNKNOWN_COMMAND;
    }

    @NotNull
    private final PostAction postAction;
    @Nullable
    private final String output;

    public CmdProcessResult(@NotNull PostAction postAction) {
        this(postAction, null);
    }

    public CmdProcessResult(@NotNull PostAction postAction, @Nullable String output) {
        this.postAction = postAction;
        this.output = output;
    }

    @NotNull
    public PostAction getPostAction() {
        return postAction;
    }

    @Nullable
    public String getOutput() {
        return output;
    }
}

