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
package org.jkiss.dbeaver.core;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.jkiss.awt.injector.ProxyInjector;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.impl.preferences.BundlePreferenceStore;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.features.DBRFeatureRegistry;
import org.jkiss.dbeaver.ui.AWTUtils;
import org.jkiss.dbeaver.ui.ConnectionFeatures;
import org.jkiss.dbeaver.ui.browser.BrowsePeerMethods;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import java.io.PrintStream;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The activator class controls the plug-in life cycle
 */
public class DBeaverActivator extends AbstractUIPlugin {

    // The shared instance
    private static DBeaverActivator instance;
    private ResourceBundle pluginResourceBundle, coreResourceBundle;
    private PrintStream debugWriter;
    private DBPPreferenceStore preferences;

    public DBeaverActivator() {
    }

    public static DBeaverActivator getInstance() {
        return instance;
    }

    @Override
    public void start(BundleContext context)
        throws Exception {
        super.start(context);

        instance = this;

        Bundle bundle = getBundle();
        ModelPreferences.setMainBundle(bundle);
        preferences = new BundlePreferenceStore(bundle);

        DBRFeatureRegistry.getInstance().registerFeatures(CoreFeatures.class);
        DBRFeatureRegistry.getInstance().registerFeatures(ConnectionFeatures.class);
        try {
            coreResourceBundle = ResourceBundle.getBundle(CoreMessages.BUNDLE_NAME);
            pluginResourceBundle = Platform.getResourceBundle(bundle);
        } catch (MissingResourceException x) {
            coreResourceBundle = null;
        }
        if (getPreferenceStore().getBoolean(DBeaverPreferences.UI_USE_EMBEDDED_AUTH)) {
            try {
                if (AWTUtils.isDesktopSupported()) {
                    injectProxyPeer();
                } else {
                    getLog().warn("Desktop interface not available");
                    getPreferenceStore().setValue(DBeaverPreferences.UI_USE_EMBEDDED_AUTH, false);
                }
            } catch (Throwable e) {
                getLog().warn(e.getMessage());
                getPreferenceStore().setValue(DBeaverPreferences.UI_USE_EMBEDDED_AUTH, false);
            }
        }
    }

    private void injectProxyPeer() throws NoSuchFieldException, IllegalAccessException {
        ProxyInjector proxyInjector = new ProxyInjector();
        proxyInjector.injectBrowseInteraction(BrowsePeerMethods::canBrowseInSWTBrowser, BrowsePeerMethods::browseInSWTBrowser);
    }

    @Override
    public void stop(BundleContext context)
        throws Exception {
        this.shutdownUI();
        this.shutdownCore();

        if (debugWriter != null) {
            debugWriter.close();
            debugWriter = null;
        }
        instance = null;

        super.stop(context);
    }

    private void shutdownUI() {
        DesktopUI.disposeUI();
    }

    /**
     * Returns the plugin's resource bundle,
     *
     * @return core resource bundle
     */
    public static ResourceBundle getCoreResourceBundle() {
        return getInstance().coreResourceBundle;
    }

    public static ResourceBundle getPluginResourceBundle() {
        return getInstance().pluginResourceBundle;
    }

    public DBPPreferenceStore getPreferences() {
        return preferences;
    }

    private void shutdownCore() {
        try {
            // Dispose core
            if (DesktopPlatform.instance != null) {
                DesktopPlatform.instance.dispose();
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.err.println("Internal error after shutdown process:" + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static ImageDescriptor getImageDescriptor(String path) {
        return imageDescriptorFromPlugin(DesktopPlatform.PLUGIN_ID, path);
    }
}
