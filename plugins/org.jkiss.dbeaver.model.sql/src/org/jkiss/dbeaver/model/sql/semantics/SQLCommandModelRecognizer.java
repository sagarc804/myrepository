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
package org.jkiss.dbeaver.model.sql.semantics;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.Pair;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.eval.ScriptVariablesResolver;
import org.jkiss.dbeaver.model.sql.semantics.model.SQLCommandModel;
import org.jkiss.dbeaver.model.sql.semantics.model.SQLQueryModel;
import org.jkiss.dbeaver.model.stm.LSMInspections;
import org.jkiss.dbeaver.model.stm.STMTreeNode;
import org.jkiss.dbeaver.model.stm.STMTreeRuleNode;
import org.jkiss.dbeaver.model.stm.STMTreeTermNode;
import org.jkiss.dbeaver.utils.GeneralUtils;

import java.util.*;

public class SQLCommandModelRecognizer {

    private static STMTreeTermNode makeNode(int start, int length) {
        return new STMTreeTermNode(new CommonToken(new Pair<>(null, null), -1, 0, start, start + length - 1));
    }

    private static SQLQuerySymbolEntry makeSymbol(int start, int length, @NotNull String name, @NotNull SQLQuerySymbolClass symbolClass) {
        SQLQuerySymbolEntry symbol = new SQLQuerySymbolEntry(makeNode(start, length), name, name, null);
        symbol.getSymbol().setSymbolClass(symbolClass);
        return symbol;
    }


    /**
     * Makes new SQLQueryModel instance for the control command in the script
     */
    public static SQLQueryModel recognizeCommand(
        @NotNull SQLQueryRecognitionContext recognitionContext,
        @NotNull String text,
        @Nullable SQLScriptContext scriptContext
    ) {
        Set<SQLQuerySymbolEntry> symbolEntries = new HashSet<>();
        String cmdPrefix = recognitionContext.getSyntaxManager().getControlCommandPrefix();
        String multilinePrefix = cmdPrefix.repeat(2);

        int start;
        int end;
        if (text.startsWith(multilinePrefix)) {
            start = multilinePrefix.length();
            symbolEntries.add(makeSymbol(0, multilinePrefix.length(), multilinePrefix, SQLQuerySymbolClass.DBEAVER_COMMAND));

            if (text.endsWith(multilinePrefix)) {
                end = text.length() - multilinePrefix.length();
                symbolEntries.add(makeSymbol(end, multilinePrefix.length(), multilinePrefix, SQLQuerySymbolClass.DBEAVER_COMMAND));
            } else {
                end = text.length();
            }
        } else if (text.startsWith(cmdPrefix)) {
            start = cmdPrefix.length();
            end = text.length();
            symbolEntries.add(makeSymbol(0, cmdPrefix.length(), cmdPrefix, SQLQuerySymbolClass.DBEAVER_COMMAND));
        } else {
            start = 0;
            end = text.length();
        }
        String cmdText = text.substring(start, end);

        Interval nameInterval = LSMInspections.matchAnyWordHead(cmdText);
        if (nameInterval != null) {
            symbolEntries.add(makeSymbol(
                start, nameInterval.length(),
                cmdText.substring(nameInterval.a, nameInterval.b + 1),
                SQLQuerySymbolClass.DBEAVER_COMMAND
            ));
        }
        STMTreeNode fakeTree = new STMTreeRuleNode();
        SQLCommandModel cmdModel = new SQLCommandModel(fakeTree, text);
        registerVariables(scriptContext, cmdText, start, symbolEntries, cmdModel);
        registerCommandParameter(text, symbolEntries);
        return new SQLQueryModel(fakeTree, cmdModel, symbolEntries, Collections.emptyList());
    }

    private static void registerVariables(
        @Nullable SQLScriptContext scriptContext,
        @NotNull String cmdText,
        int start,
        @NotNull Set<SQLQuerySymbolEntry> symbolEntries,
        @NotNull SQLCommandModel cmdModel
    ) {
        ScriptVariablesResolver variablesResolver = scriptContext == null || scriptContext.getExecutionContext() == null
            ? null
            : new ScriptVariablesResolver(scriptContext);
        List<GeneralUtils.VariableEntryInfo> vars = GeneralUtils.findAllVariableEntries(cmdText);
        for (GeneralUtils.VariableEntryInfo varEntry : vars) {
            SQLQuerySymbolEntry symbolEntry = makeSymbol(
                start + varEntry.start(), varEntry.end() - varEntry.start(),
                cmdText.substring(varEntry.start(), varEntry.end()),
                SQLQuerySymbolClass.DBEAVER_VARIABLE
            );
            symbolEntries.add(symbolEntry);
            cmdModel.addVariable(symbolEntry, variablesResolver == null ? "?" : variablesResolver.get(varEntry.name()));
        }
    }

    private static void registerCommandParameter(@NotNull String text, @NotNull Set<SQLQuerySymbolEntry> symbolEntries) {
        List<SQLQuerySymbolEntry> alreadyHighlighted = symbolEntries.stream()
            .sorted(Comparator.comparingInt(e -> e.getInterval().a))
            .toList();
        int prevPos = 0;
        for (SQLQuerySymbolEntry entry : alreadyHighlighted) {
            int entryPos = entry.getInterval().a;
            if (prevPos < entryPos) {
                symbolEntries.add(makeSymbol(prevPos, entryPos - prevPos, text.substring(prevPos, entryPos), SQLQuerySymbolClass.UNKNOWN));
            }
            prevPos = entry.getInterval().b + 1; // because interval here is inclusive
        }
        if (prevPos < text.length()) {
            symbolEntries.add(makeSymbol(prevPos, text.length() - prevPos, text.substring(prevPos), SQLQuerySymbolClass.UNKNOWN));
        }
    }


}
