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
package org.jkiss.dbeaver.ui.editors.sql.ai.format;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.ai.AICompletionConstants;
import org.jkiss.dbeaver.model.ai.AISettings;
import org.jkiss.dbeaver.model.ai.format.IAIFormatter;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.IObjectPropertyConfigurator;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.ai.internal.AIUIMessages;

public class DefaultFormattingConfigurator implements IObjectPropertyConfigurator<IAIFormatter, AISettings> {
    private Button includeSourceTextInCommentCheck;
    private Button executeQueryImmediatelyCheck;

    private Button sendTypeInfoCheck;

    private Button sendDescriptionCheck;

    protected Composite settingsPanel;


    @Override
    public void createControl(
        @NotNull Composite parent,
        IAIFormatter object,
        @NotNull Runnable propertyChangeListener
    ) {
        settingsPanel = UIUtils.createComposite(parent, 2);
        settingsPanel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Group completionComposite = UIUtils.createControlGroup(settingsPanel, "SQL Completion", 1, GridData.FILL_HORIZONTAL, SWT.DEFAULT);
        UIUtils.createControlLabel(completionComposite, AIUIMessages.gpt_preference_page_advanced_appearance_group, 2);
        Composite appearanceSettings = UIUtils.createComposite(completionComposite, 2);
        appearanceSettings.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING | GridData.FILL_HORIZONTAL));

        createAppearanceSettings(appearanceSettings, propertyChangeListener);
        UIUtils.createControlLabel(completionComposite, AIUIMessages.gpt_preference_page_completion_group, 2);
        Composite completionGroup = UIUtils.createComposite(completionComposite, 2);
        completionGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        createCompletionSettings(completionGroup, propertyChangeListener);

        Composite rightPanel = UIUtils.createComposite(settingsPanel, 1);
        rightPanel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        createRightPanel(rightPanel);
    }

    protected void createRightPanel(Composite rightPanel) {
        Group schemaGroup = UIUtils.createControlGroup(
            rightPanel,
            AIUIMessages.gpt_preference_page_schema_group,
            2,
            SWT.NONE,
            5
        );
        createSchemaSettings(schemaGroup);
    }

    protected void createCompletionSettings(Composite completionGroup, Runnable propertyChangeListener) {
        completionGroup.setLayoutData(new GridData(GridData.FILL_BOTH));
        executeQueryImmediatelyCheck = UIUtils.createCheckbox(
            completionGroup,
            AIUIMessages.gpt_preference_page_completion_execute_immediately_label,
            AIUIMessages.gpt_preference_page_completion_execute_immediately_tip,
            false,
            2);

    }

    protected void createSchemaSettings(Composite schemaGroup) {
        schemaGroup.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING | GridData.FILL_HORIZONTAL));
        sendTypeInfoCheck = UIUtils.createCheckbox(
            schemaGroup,
            AIUIMessages.gpt_preference_page_completion_send_type_label,
            AIUIMessages.gpt_preference_page_completion_send_type_tip,
            false,
            2);
        sendDescriptionCheck = UIUtils.createCheckbox(
            schemaGroup,
            AIUIMessages.gpt_preference_page_completion_execute_description_label,
            AIUIMessages.gpt_preference_page_completion_execute_description_tip,
            false,
            2);
    }

    protected void createAppearanceSettings(Composite appearanceGroup, Runnable propertyChangeListener) {
        includeSourceTextInCommentCheck = UIUtils.createCheckbox(
            appearanceGroup,
            AIUIMessages.gpt_preference_page_completion_include_source_label,
            AIUIMessages.gpt_preference_page_completion_include_source_tip,
            false,
            2);
    }


    @Override
    public void loadSettings(@NotNull AISettings aiSettings) {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        includeSourceTextInCommentCheck.setSelection(store.getBoolean(AICompletionConstants.AI_INCLUDE_SOURCE_TEXT_IN_QUERY_COMMENT));
        executeQueryImmediatelyCheck.setSelection(store.getBoolean(AICompletionConstants.AI_COMPLETION_EXECUTE_IMMEDIATELY));
        sendTypeInfoCheck.setSelection(store.getBoolean(AICompletionConstants.AI_SEND_TYPE_INFO));
        sendDescriptionCheck.setSelection(store.getBoolean(AICompletionConstants.AI_SEND_DESCRIPTION));
    }

    @Override
    public void saveSettings(@NotNull AISettings aiSettings) {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        store.setValue(AICompletionConstants.AI_INCLUDE_SOURCE_TEXT_IN_QUERY_COMMENT, includeSourceTextInCommentCheck.getSelection());
        store.setValue(AICompletionConstants.AI_COMPLETION_EXECUTE_IMMEDIATELY, executeQueryImmediatelyCheck.getSelection());
        store.setValue(AICompletionConstants.AI_SEND_TYPE_INFO, sendTypeInfoCheck.getSelection());
        store.setValue(AICompletionConstants.AI_SEND_DESCRIPTION, sendDescriptionCheck.getSelection());
    }

    @Override
    public void resetSettings(@NotNull AISettings aiSettings) {

    }

    @Override
    public boolean isComplete() {
        return true;
    }
}
