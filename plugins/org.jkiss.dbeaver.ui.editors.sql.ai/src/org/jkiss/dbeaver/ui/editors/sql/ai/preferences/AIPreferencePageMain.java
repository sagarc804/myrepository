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
package org.jkiss.dbeaver.ui.editors.sql.ai.preferences;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.IWorkbenchPropertyPage;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.*;
import org.jkiss.dbeaver.model.ai.completion.DAICompletionEngine;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.rm.RMConstants;
import org.jkiss.dbeaver.registry.configurator.UIPropertyConfiguratorDescriptor;
import org.jkiss.dbeaver.registry.configurator.UIPropertyConfiguratorRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.IObjectPropertyConfigurator;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.ai.internal.AIUIMessages;
import org.jkiss.dbeaver.ui.preferences.AbstractPrefPage;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AIPreferencePageMain extends AbstractPrefPage implements IWorkbenchPreferencePage, IWorkbenchPropertyPage {
    private static final Log log = Log.getLog(AIPreferencePageMain.class);
    public static final String PAGE_ID = "org.jkiss.dbeaver.preferences.ai";
    private final AISettings settings;

    private DAICompletionEngine completionEngine;
    private Combo serviceCombo;

    private final Map<String, String> serviceNameMappings = new HashMap<>();
    private final Map<String, EngineConfiguratorPage> engineConfiguratorMapping = new HashMap<>();
    private EngineConfiguratorPage activeEngineConfiguratorPage;
    private Button enableAICheck;

    public AIPreferencePageMain() {
        this.settings = AISettingsRegistry.getInstance().getSettings();
        String activeEngine = this.settings.activeEngine();
        try {
            completionEngine = AIEngineRegistry.getInstance().getCompletionEngine(activeEngine);
        } catch (DBException e) {
            log.error("Error getting engine configuration", e);

            DBWorkbench.getPlatformUI().showError(
                "Error loading AI settings",
                "Error loading AI settings for " + activeEngine,
                e
            );
        }
    }

    @Override
    public IAdaptable getElement() {
        return this.settings;
    }

    @Override
    public void setElement(IAdaptable element) {

    }

    @Nullable
    private IObjectPropertyConfigurator<DAICompletionEngine, AIEngineSettings<?>> createEngineConfigurator() {
        UIPropertyConfiguratorDescriptor engineDescriptor =
            UIPropertyConfiguratorRegistry.getInstance().getDescriptor(completionEngine.getClass().getName());
        if (engineDescriptor != null) {
            try {
                return engineDescriptor.createConfigurator();
            } catch (DBException e) {
                log.error(e);
            }
        }
        return null;
    }

    @Override
    protected void performDefaults() {
        if (!hasAccessToPage()) {
            return;
        }
        enableAICheck.setSelection(!this.settings.isAiDisabled());
    }

    @Override
    public boolean performOk() {
        if (!hasAccessToPage()) {
            return false;
        }
        this.settings.setAiDisabled(!enableAICheck.getSelection());
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        this.settings.setActiveEngine(serviceNameMappings.get(serviceCombo.getText()));
        if (!serviceCombo.getText().isEmpty()) {
            for (Map.Entry<String, EngineConfiguratorPage> entry : engineConfiguratorMapping.entrySet()) {
                try {
                    AIEngineSettings<?> engineConfiguration = this.settings.getEngineConfiguration(entry.getKey());
                    entry.getValue().saveSettings(engineConfiguration);
                } catch (DBException e) {
                    log.error("Error saving engine settings", e);

                    DBWorkbench.getPlatformUI().showError(
                        "Error saving AI settings",
                        "Error saving engine settings for " + entry.getKey(),
                        e
                    );
                }
            }
        }
        AISettingsRegistry.getInstance().saveSettings(this.settings);
        try {
            store.save();
        } catch (IOException e) {
            log.debug(e);
        }

        return true;
    }

    @NotNull
    @Override
    protected Control createPreferenceContent(@NotNull Composite parent) {
        Composite composite = UIUtils.createComposite(parent, 1);
        enableAICheck = UIUtils.createCheckbox(
            composite,
            AIUIMessages.gpt_preference_page_checkbox_enable_ai_label,
            AIUIMessages.gpt_preference_page_checkbox_enable_ai_tip,
            false,
            2);

        composite.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite serviceComposite = UIUtils.createComposite(composite, 2);
        serviceComposite.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING));
        serviceCombo = UIUtils.createLabelCombo(serviceComposite, "Engine", SWT.DROP_DOWN | SWT.READ_ONLY);
        List<AIEngineDescriptor> completionEngines = AIEngineRegistry.getInstance()
            .getCompletionEngines();
        int defaultEngineSelection = -1;
        for (int i = 0; i < completionEngines.size(); i++) {
            serviceCombo.add(completionEngines.get(i).getLabel());
            serviceNameMappings.put(completionEngines.get(i).getLabel(), completionEngines.get(i).getId());
            if (completionEngines.get(i).isDefault()) {
                defaultEngineSelection = i;
            }
            if (completionEngines.get(i).getId().equals(this.settings.activeEngine())) {
                serviceCombo.select(i);
            }
        }
        if (serviceCombo.getSelectionIndex() == -1 && defaultEngineSelection != -1) {
            serviceCombo.select(defaultEngineSelection);
        }

        final Group engineGroup = UIUtils.createControlGroup(composite, "Engine Settings", 2, SWT.BORDER, 5);
        engineGroup.setLayoutData(new GridData(GridData.FILL_BOTH));
        if (completionEngine != null) {
            drawConfiguratorComposite(this.settings.activeEngine(), engineGroup);
        }
        serviceCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                String id = serviceNameMappings.get(serviceCombo.getText());
                try {
                    completionEngine = AIEngineRegistry.getInstance().getCompletionEngine(id);
                } catch (DBException ex) {
                    log.error("Error getting engine configuration");
                    return;
                }
                if (activeEngineConfiguratorPage != null) {
                    activeEngineConfiguratorPage.disposeControl();
                }
                drawConfiguratorComposite(id, engineGroup);
                engineGroup.layout(true, true);
                UIUtils.resizeShell(parent.getShell());
            }
        });
        performDefaults();

        return composite;
    }

    private void drawConfiguratorComposite(@NotNull String id, @NotNull Group engineGroup) {
        activeEngineConfiguratorPage = engineConfiguratorMapping.get(id);

        if (activeEngineConfiguratorPage == null) {
            IObjectPropertyConfigurator<DAICompletionEngine, AIEngineSettings<?>> engineConfigurator
                = createEngineConfigurator();
            activeEngineConfiguratorPage = new EngineConfiguratorPage(engineConfigurator);
            activeEngineConfiguratorPage.createControl(engineGroup, completionEngine);
            try {
                activeEngineConfiguratorPage.loadSettings(this.settings.getEngineConfiguration(id));
            } catch (DBException e) {
                log.error("Error loading engine settings", e);

                DBWorkbench.getPlatformUI().showError(
                    "Error loading AI settings",
                    "Error loading engine settings for " + id,
                    e
                );
            }
            engineConfiguratorMapping.put(id, activeEngineConfiguratorPage);
        } else {
            activeEngineConfiguratorPage.createControl(engineGroup, completionEngine);
        }
    }

    @Override
    public void init(IWorkbench workbench) {

    }

    private static class EngineConfiguratorPage {
        private final IObjectPropertyConfigurator<DAICompletionEngine, AIEngineSettings<?>> configurator;
        private Composite composite;

        EngineConfiguratorPage(IObjectPropertyConfigurator<DAICompletionEngine, AIEngineSettings<?>> configurator) {
            this.configurator = configurator;
        }

        private void createControl(Composite parent, DAICompletionEngine engine) {
            composite = UIUtils.createComposite(parent, 1);
            composite.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            if (configurator != null) {
                configurator.createControl(composite, engine, () -> {});
            }
        }

        private void disposeControl() {
            composite.dispose();
        }

        private void loadSettings(AIEngineSettings<?> settings) {
            if (configurator != null) {
                configurator.loadSettings(settings);
            }
        }

        private void saveSettings(AIEngineSettings<?> settings) {
            if (configurator != null) {
                configurator.saveSettings(settings);
            }
        }
    }



    @Override
    protected boolean hasAccessToPage() {
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_CONFIGURATION_MANAGER);
    }
}
