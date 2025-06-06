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
package org.jkiss.dbeaver.ui.app.standalone;

import org.apache.commons.cli.CommandLine;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Resource;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferenceConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.WorkbenchPlugin;
import org.eclipse.ui.internal.ide.ChooseWorkspaceData;
import org.eclipse.ui.internal.ide.ChooseWorkspaceDialog;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.LogOutputStream;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.core.DBeaverActivator;
import org.jkiss.dbeaver.core.DesktopPlatform;
import org.jkiss.dbeaver.core.DesktopUI;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.app.DBPApplicationController;
import org.jkiss.dbeaver.model.app.DBPApplicationDesktop;
import org.jkiss.dbeaver.model.app.DBPPlatform;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.cli.CmdProcessResult;
import org.jkiss.dbeaver.model.impl.app.BaseWorkspaceImpl;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.rcp.DesktopApplicationImpl;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.registry.SWTBrowserRegistry;
import org.jkiss.dbeaver.registry.timezone.TimezoneRegistry;
import org.jkiss.dbeaver.registry.updater.VersionDescriptor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.dbeaver.runtime.ui.console.ConsoleUserInterface;
import org.jkiss.dbeaver.ui.app.standalone.rpc.DBeaverInstanceServer;
import org.jkiss.dbeaver.ui.app.standalone.rpc.IInstanceController;
import org.jkiss.dbeaver.ui.app.standalone.update.VersionUpdateDialog;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.dbeaver.utils.SystemVariablesResolver;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import org.jkiss.utils.StandardConstants;
import org.osgi.framework.Version;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class controls all aspects of the application's execution
 */
public class DBeaverApplication extends DesktopApplicationImpl implements DBPApplicationDesktop, DBPApplicationController {

    private static final Log log = Log.getLog(DBeaverApplication.class);

    public static final String APPLICATION_PLUGIN_ID = "org.jkiss.dbeaver.ui.app.standalone";

    public static final String WORKSPACE_DIR_LEGACY = "${user.home}/.dbeaver"; //$NON-NLS-1$
    public static final String WORKSPACE_DIR_4 = "${user.home}/.dbeaver4"; //$NON-NLS-1$

    public static final String[] WORKSPACE_DIR_PREVIOUS = {
        WORKSPACE_DIR_4,
        WORKSPACE_DIR_LEGACY};

    static final String VERSION_PROP_PRODUCT_NAME = "product-name";
    static final String VERSION_PROP_PRODUCT_VERSION = "product-version";

    private static final String PROP_EXIT_DATA = IApplicationContext.EXIT_DATA_PROPERTY; //$NON-NLS-1$
    private static final String PROP_EXIT_CODE = "eclipse.exitcode"; //$NON-NLS-1$

    public static final String DEFAULT_WORKSPACE_FOLDER = "workspace6";
    public static final String DEFAULT_WORKSPACES_FILE = ".workspaces";

    private static final String PLUGINS_FOLDER = ".plugins";
    private static final String CORE_RESOURCES_PLUGIN_FOLDER = "org.eclipse.core.resources";
    private static final String STARTUP_ACTIONS_FILE = "dbeaver-startup-actions.properties";
    private static final String RESET_USER_PREFERENCES = "reset_user_preferences";
    private static final String RESET_WORKSPACE_CONFIGURATION = "reset_workspace_configuration";

    private final String WORKSPACE_DIR_6; //$NON-NLS-1$
    private final Path FILE_WITH_WORKSPACES;
    public final String WORKSPACE_DIR_CURRENT;

    static boolean WORKSPACE_MIGRATED = false;

    static DBeaverApplication instance;

    private boolean exclusiveMode = false;
    private boolean reuseWorkspace = false;
    private boolean primaryInstance = true;
    private boolean headlessMode = false;

    private DBeaverInstanceServer instanceServer;

    private OutputStream debugWriter;
    private PrintStream oldSystemOut;
    private PrintStream oldSystemErr;

    private Display display = null;

    private boolean resetUserPreferencesOnRestart, resetWorkspaceConfigurationOnRestart;
    private long lastUserActivityTime = -1;

    public DBeaverApplication() {
        this(DesktopPlatform.DBEAVER_DATA_DIR, DEFAULT_WORKSPACE_FOLDER, DEFAULT_WORKSPACES_FILE);
    }

    protected DBeaverApplication(String defaultWorkspaceLocation, String defaultAppWorkspaceName, String defaultWorkspacesFile) {

        // Explicitly set UTF-8 as default file encoding
        // In some places Eclipse reads this property directly.
        //System.setProperty(StandardConstants.ENV_FILE_ENCODING, GeneralUtils.UTF8_ENCODING);

        // Detect default workspace location
        // Since 6.1.3 it is different for different OSes
        // Windows: %AppData%/DBeaverData
        // MacOS: ~/Library/DBeaverData
        // Linux: $XDG_DATA_HOME/DBeaverData
        String workingDirectory = RuntimeUtils.getWorkingDirectory(defaultWorkspaceLocation);

        // Workspace dir
        WORKSPACE_DIR_6 = new File(workingDirectory, defaultAppWorkspaceName).getAbsolutePath();
        WORKSPACE_DIR_CURRENT = WORKSPACE_DIR_6;
        FILE_WITH_WORKSPACES = Paths.get(workingDirectory, defaultWorkspacesFile); //$NON-NLS-1$
    }

    /**
     * Gets singleton instance of DBeaver application
     * @return application or null if application wasn't started or was stopped.
     */
    public static DBeaverApplication getInstance() {
        return instance;
    }

    @Override
    public long getLastUserActivityTime() {
        return lastUserActivityTime;
    }

    @NotNull
    @Override
    public DBPPreferenceStore getPreferenceStore() {
        return DBeaverActivator.getInstance().getPreferences();
    }

    @Override
    public Object start(IApplicationContext context) {
        instance = this;

        Location instanceLoc = Platform.getInstanceLocation();

        CommandLine commandLine = DBeaverCommandLine.getInstance().getCommandLine();
        String defaultHomePath = getDefaultInstanceLocation();
        if (DBeaverCommandLine.getInstance()
            .handleCommandLineAsClient(commandLine, defaultHomePath)
            .getPostAction() == CmdProcessResult.PostAction.SHUTDOWN
        ) {
            if (!Log.isQuietMode()) {
                System.err.println("Commands processed. Exit " + GeneralUtils.getProductName() + ".");
            }
            return IApplication.EXIT_OK;
        }

        if (!isWorkspaceSwitchingAllowed() && !WORKSPACE_DIR_CURRENT.equals(defaultHomePath)) {
            log.error("Workspace switching is not allowed when participating in the early access program. Exiting "
                + GeneralUtils.getProductName() + ".");
            return IApplication.EXIT_OK;
        }

        boolean ideWorkspaceSet = setIDEWorkspace(instanceLoc);

        {
            // Lock the workspace
            try {
                if (!instanceLoc.isSet()) {
                    if (!setDefaultWorkspacePath(instanceLoc)) {
                        return IApplication.EXIT_OK;
                    }
                } else if (instanceLoc.isLocked() && !ideWorkspaceSet && !isExclusiveMode()) {
                    // Check for locked workspace
                    if (!setDefaultWorkspacePath(instanceLoc)) {
                        return IApplication.EXIT_OK;
                    }
                }

                if (isExclusiveMode()) {
                    markLocationReadOnly(instanceLoc);
                } else {
                    // Lock the workspace
                    if (!instanceLoc.isLocked()) {
                        instanceLoc.lock();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        loadStartupActions(instanceLoc);

        // Register core components
        initializeApplicationServices();

        // Custom parameters
        try {
            headlessMode = true;
            if (DBeaverCommandLine.getInstance().handleCustomParameters(commandLine)) {
                return IApplication.EXIT_OK;
            }
        } finally {
            headlessMode = false;
        }

        if (isExclusiveMode()) {
            // In shared mode we mustn't run UI
            return IApplication.EXIT_OK;
        }

        final Runtime runtime = Runtime.getRuntime();

        initializeConfiguration();

        // Debug logger
        initDebugWriter();

        log.debug(GeneralUtils.getProductName() + " " + GeneralUtils.getProductVersion() + " is starting"); //$NON-NLS-1$
        log.debug("OS: " + System.getProperty(StandardConstants.ENV_OS_NAME) + " " + System.getProperty(StandardConstants.ENV_OS_VERSION) + " (" + System.getProperty(StandardConstants.ENV_OS_ARCH) + ")");
        log.debug("Java version: " + System.getProperty(StandardConstants.ENV_JAVA_VERSION) + " by " + System.getProperty(StandardConstants.ENV_JAVA_VENDOR) + " (" + System.getProperty(StandardConstants.ENV_JAVA_ARCH) + "bit)");
        log.debug("Install path: '" + SystemVariablesResolver.getInstallPath() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        log.debug("Instance path: '" + instanceLoc.getURL() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        log.debug("Memory available " + (runtime.totalMemory() / (1024 * 1024)) + "Mb/" + (runtime.maxMemory() / (1024 * 1024)) + "Mb");

        // Write version info
        writeWorkspaceInfo();

        // Initialize display early
        // It sets main windows name and images
        getDisplay();

        // https://github.com/eclipse-platform/eclipse.platform.swt/issues/772
        if (!RuntimeUtils.isMacOS() || !RuntimeUtils.isOSVersionAtLeast(14, 0, 0)) {
            // Update splash. Do it AFTER platform startup because platform may initiate some splash shell interactions
            updateSplashHandler();
        }

        if (RuntimeUtils.isWindows() && isStandalone()) {
            SWTBrowserRegistry.overrideBrowser();
        }

        DBWorkbench.getPlatform();

        initializeApplication();

        // Run instance server
        try {
            instanceServer = DBeaverInstanceServer.createServer();
        } catch (Exception e) {
            log.error("Can't start instance server: " + e.getMessage());
        }

        TimezoneRegistry.overrideTimezone();

        if (RuntimeUtils.isWindows()
            && CommonUtils.isEmpty(System.getProperty(GeneralUtils.PROP_TRUST_STORE))
            && CommonUtils.isEmpty(System.getProperty(GeneralUtils.PROP_TRUST_STORE_TYPE))
            && DBWorkbench.getPlatform().getPreferenceStore().getBoolean(ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE)
        ) {
            System.setProperty(GeneralUtils.PROP_TRUST_STORE_TYPE, GeneralUtils.VALUE_TRUST_STORE_TYPE_WINDOWS);
        }

        // Prefs default
        PlatformUI.getPreferenceStore().setDefault(
            IWorkbenchPreferenceConstants.KEY_CONFIGURATION_ID,
            ApplicationWorkbenchAdvisor.DBEAVER_SCHEME_NAME);
        try {
            log.debug("Run workbench");
            getDisplay();
            int returnCode = PlatformUI.createAndRunWorkbench(display, createWorkbenchAdvisor());

            // Copy-pasted from IDEApplication
            // Magic with exit codes to let Eclipse starter switcg workspace

            // the workbench doesn't support relaunch yet (bug 61809) so
            // for now restart is used, and exit data properties are checked
            // here to substitute in the relaunch return code if needed
            if (returnCode != PlatformUI.RETURN_RESTART) {
                return EXIT_OK;
            }

            // if the exit code property has been set to the relaunch code, then
            // return that code now, otherwise this is a normal restart
            return EXIT_RELAUNCH.equals(Integer.getInteger(PROP_EXIT_CODE)) ? EXIT_RELAUNCH
                : EXIT_RESTART;

        } catch (Throwable e) {
            log.debug("Internal error in workbench lifecycle", e);
            return IApplication.EXIT_OK;
        } finally {
            shutdown();
/*
            try {
                Job.getJobManager().join(null, new NullProgressMonitor());
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
*/
            display.dispose();
            display = null;
        }
    }

    private void markLocationReadOnly(Location instanceLoc) {
        try {
            Field isReadOnlyField = instanceLoc.getClass().getDeclaredField("isReadOnly");
            isReadOnlyField.setAccessible(true);
            isReadOnlyField.set(instanceLoc, true);
        } catch (Throwable e) {
            // ignore
            e.printStackTrace();
        }
    }

    private boolean setIDEWorkspace(@NotNull Location instanceLoc) {
        if (instanceLoc.isSet()) {
            return false;
        }
        if (!isWorkspaceSwitchingAllowed()) {
            return false;
        }
        Collection<String> recentWorkspaces = getRecentWorkspaces(instanceLoc);
        if (recentWorkspaces.isEmpty()) {
            return false;
        }
        String lastWorkspace = recentWorkspaces.iterator().next();
        if (!CommonUtils.isEmpty(lastWorkspace) && !WORKSPACE_DIR_CURRENT.equals(lastWorkspace)) {
            try {
                final URL selectedWorkspaceURL = new URL(
                    "file",  //$NON-NLS-1$
                    null,
                    lastWorkspace);
                instanceLoc.set(selectedWorkspaceURL, true);

                return true;
            } catch (Exception e) {
                System.err.println("Can't set IDE workspace to '" + lastWorkspace + "'");
                e.printStackTrace();
            }
        }
        return false;
    }

    @NotNull
    private Collection<String> getRecentWorkspaces(@NotNull Location instanceLoc) {
        ChooseWorkspaceData launchData = new ChooseWorkspaceData(instanceLoc.getDefault());
        String[] arrayOfRecentWorkspaces = launchData.getRecentWorkspaces();
        Collection<String> recentWorkspaces;
        int maxSize;
        if (arrayOfRecentWorkspaces == null) {
            maxSize = 0;
            recentWorkspaces = new ArrayList<>();
        } else {
            maxSize = arrayOfRecentWorkspaces.length;
            recentWorkspaces = new ArrayList<>(Arrays.asList(arrayOfRecentWorkspaces));
        }
        recentWorkspaces.removeIf(Objects::isNull);
        Collection<String> backedUpWorkspaces = getBackedUpWorkspaces();
        if (recentWorkspaces.equals(backedUpWorkspaces) && backedUpWorkspaces.contains(WORKSPACE_DIR_CURRENT)) {
            return backedUpWorkspaces;
        }

        List<String> workspaces = Stream.concat(recentWorkspaces.stream(), backedUpWorkspaces.stream())
            .distinct()
            .limit(maxSize)
            .collect(Collectors.toList());
        if (!recentWorkspaces.contains(WORKSPACE_DIR_CURRENT)) {
            if (recentWorkspaces.size() < maxSize) {
                recentWorkspaces.add(WORKSPACE_DIR_CURRENT);
            } else if (maxSize > 1) {
                workspaces.set(recentWorkspaces.size() - 1, WORKSPACE_DIR_CURRENT);
            }
        }
        launchData.setRecentWorkspaces(Arrays.copyOf(workspaces.toArray(new String[0]), maxSize));
        launchData.writePersistedData();
        saveWorkspacesToBackup(workspaces);
        return workspaces;
    }

    @NotNull
    private Collection<String> getBackedUpWorkspaces() {
        if (!Files.exists(FILE_WITH_WORKSPACES) || Files.isDirectory(FILE_WITH_WORKSPACES)) {
            return Collections.emptyList();
        }
        try {
            return Files.readAllLines(FILE_WITH_WORKSPACES);
        } catch (IOException e) {
            System.err.println("Unable to read backed up workspaces"); //$NON-NLS-1$
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private void saveWorkspacesToBackup(@NotNull List<? extends CharSequence> workspaces) {
        try {
            if (!Files.exists(FILE_WITH_WORKSPACES.getParent())) {
                Files.createDirectories(FILE_WITH_WORKSPACES.getParent());
            } else if (Files.isDirectory(FILE_WITH_WORKSPACES)) {
                // Bug in 22.0.5
                try {
                    Files.delete(FILE_WITH_WORKSPACES);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            Files.write(FILE_WITH_WORKSPACES, workspaces, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Unable to save backed up workspaces"); //$NON-NLS-1$
            e.printStackTrace();
        }
    }
    @Nullable
    public Path getDefaultWorkingFolder() {
        return  Path.of(WORKSPACE_DIR_CURRENT);
    }

    @NotNull
    @Override
    public Class<? extends DBPPlatform> getPlatformClass() {
        return DesktopPlatform.class;
    }

    @Override
    public Class<? extends DBPPlatformUI> getPlatformUIClass() {
        return isHeadlessMode() ? ConsoleUserInterface.class : DesktopUI.class;
    }

    public String getDefaultInstanceLocation() {
        String defaultHomePath = WORKSPACE_DIR_CURRENT;
        Location instanceLoc = Platform.getInstanceLocation();
        if (instanceLoc.isSet()) {
            try {
                defaultHomePath = RuntimeUtils.getLocalFileFromURL(instanceLoc.getURL()).getAbsolutePath();
            } catch (IOException e) {
                System.err.println("Unable to resolve workspace location " + instanceLoc);
                e.printStackTrace();
            }
        }
        return defaultHomePath;
    }

    private void updateSplashHandler() {
        if (ArrayUtils.contains(Platform.getApplicationArgs(), "-nosplash")) {
            return;
        }
        try {
            // look and see if there's a splash shell we can parent off of
            Shell shell = WorkbenchPlugin.getSplashShell(display);
            if (shell != null) {
                // should set the icon and message for this shell to be the
                // same as the chooser dialog - this will be the guy that lives in
                // the task bar and without these calls you'd have the default icon
                // with no message.
                shell.setText(ChooseWorkspaceDialog.getWindowTitle());
                shell.setImages(Window.getDefaultImages());

                Log.Listener splashListener = (message, t) -> {
                    DBeaverSplashHandler.showMessage(CommonUtils.toString(message));
                };
                Log.addListener(splashListener);
                shell.addDisposeListener(e -> {
                    Log.removeListener(splashListener);
                });
                DBeaverSplashHandler.showMessage("Starting " + Platform.getProduct().getName());
            }
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            System.err.println("Error updating splash shell");
        }

    }

    protected void initializeConfiguration() {
        ModelPreferences.IPType stack = ModelPreferences.IPType.getPreferredStack();
        if (stack != ModelPreferences.IPType.AUTO) {
            System.setProperty("java.net.preferIPv4Stack", String.valueOf(stack == ModelPreferences.IPType.IPV4));
        }
        ModelPreferences.IPType address = ModelPreferences.IPType.getPreferredAddresses();
        if (address != ModelPreferences.IPType.AUTO) {
            System.setProperty("java.net.preferIPv6Addresses", String.valueOf(address == ModelPreferences.IPType.IPV6));
        }
        boolean debugNetworkConnections = ModelPreferences.getPreferences().getBoolean(ModelPreferences.PROP_DEBUG_NETWORK_CONNECTIONS);
        if (debugNetworkConnections) {
            System.setProperty("javax.net.debug", "all");
        }
    }

    /**
     * May be overrided in implementors
     */
    protected void initializeApplication() {

    }

    private Display getDisplay() {
        if (display == null) {
            log.debug("Create display");
            // Set display name at the very beginning (#609)
            // This doesn't initialize display - just sets default title
            Display.setAppName(GeneralUtils.getProductName());

            display = Display.getCurrent();
            if (display == null) {
                display = PlatformUI.createDisplay();
            }

            // Check for resource leaks
            Resource.setNonDisposeHandler(originStack -> log.warn("SWT resource leak detected", originStack));
            
            addIdleListeners();
        }
        return display;
    }

    private void addIdleListeners() {
        int [] events = {SWT.KeyDown, SWT.KeyUp, SWT.MouseDown, SWT.MouseMove, SWT.MouseUp, SWT.MouseWheel};
        Listener idleListener = event -> lastUserActivityTime = System.currentTimeMillis();
        for (int event : events) {
            display.addFilter(event, idleListener);
        }
    }

    private boolean setDefaultWorkspacePath(Location instanceLoc) {
        String defaultHomePath = WORKSPACE_DIR_CURRENT;
        final Path homeDir = Path.of(defaultHomePath);
        try {
            if (!Files.exists(homeDir) || isEmptyFolder(homeDir)) {
                if (!tryMigrateFromPreviousVersion(homeDir)) {
                    return false;
                }
            }
        } catch (Throwable e) {
            log.error("Error migrating old workspace version", e);
        }
        try {
            // Make URL manually because file.toURI().toURL() produces bad path (with %20).
            final URL defaultHomeURL = new URL(
                "file",  //$NON-NLS-1$
                null,
                defaultHomePath);
            boolean keepTrying = true;
            while (keepTrying) {
                if (instanceLoc.isLocked() || !instanceLoc.set(defaultHomeURL, true)) {
                    if (exclusiveMode || reuseWorkspace) {
                        instanceLoc.set(defaultHomeURL, false);
                        keepTrying = false;
                        primaryInstance = false;
                    } else {
                        // Can't lock specified path
                        int msgResult = showMessageBox(
                            "DBeaver - Can't lock workspace",
                            "Can't lock workspace at " + defaultHomePath + ".\n" +
                                "It seems that you have another DBeaver instance running.\n" +
                                "You may ignore it and work without lock but it is recommended to shutdown previous instance otherwise you may corrupt workspace data.",
                            SWT.ICON_WARNING | SWT.IGNORE | SWT.RETRY | SWT.ABORT);

                        switch (msgResult) {
                            case SWT.ABORT:
                                return false;
                            case SWT.IGNORE:
                                instanceLoc.set(defaultHomeURL, false);
                                keepTrying = false;
                                primaryInstance = false;
                                break;
                            case SWT.RETRY:
                                break;
                        }
                    }
                } else {
                    break;
                }
            }

        } catch (Throwable e) {
            // Just skip it
            // Error may occur if -data parameter was specified at startup
            System.err.println("Can't switch workspace to '" + defaultHomePath + "' - " + e.getMessage());  //$NON-NLS-1$ //$NON-NLS-2$
        }

        return true;
    }

    private static boolean isEmptyFolder(Path path) throws IOException {
        try (Stream<Path> list = Files.list(path)) {
            return list.findAny().isEmpty();
        }
    }

    protected boolean tryMigrateFromPreviousVersion(Path homeDir) {
        Path previousVersionWorkspaceDir = null;
        for (String oldDir : WORKSPACE_DIR_PREVIOUS) {
            oldDir = GeneralUtils.replaceSystemPropertyVariables(oldDir);
            final Path oldWorkspaceDir = Path.of(oldDir);
            if (Files.exists(oldWorkspaceDir) &&
                Files.exists(GeneralUtils.getMetadataFolder(oldWorkspaceDir))) {
                previousVersionWorkspaceDir = oldWorkspaceDir;
                break;
            }
        }
        if (previousVersionWorkspaceDir != null) {
            DBeaverSettingsImporter importer = new DBeaverSettingsImporter(this, getDisplay());
            if (!importer.migrateFromPreviousVersion(previousVersionWorkspaceDir.toFile(), homeDir.toFile())) {
                return false;
            }
        }
        return true;
    }

    private void writeWorkspaceInfo() {
        Path defaultDir = getDefaultWorkingFolder();
        Path metadataFolder;
        if (defaultDir != null) {
            metadataFolder = defaultDir.resolve(DBPWorkspace.METADATA_FOLDER);
            if (!Files.exists(metadataFolder)) {
                try {
                    Files.createDirectories(metadataFolder);
                } catch (IOException e) {
                    e.printStackTrace();
                    System.err.println("Error creating metadata folder: " + metadataFolder);
                }
            }
        } else {
            metadataFolder = GeneralUtils.getMetadataFolder();
        }
        Properties props = BaseWorkspaceImpl.readWorkspaceInfo(metadataFolder);
        props.setProperty(VERSION_PROP_PRODUCT_NAME, GeneralUtils.getProductName());
        props.setProperty(VERSION_PROP_PRODUCT_VERSION, GeneralUtils.getProductVersion().toString());
        BaseWorkspaceImpl.writeWorkspaceInfo(metadataFolder, props);
    }

    @NotNull
    protected ApplicationWorkbenchAdvisor createWorkbenchAdvisor() {
        return new ApplicationWorkbenchAdvisor(this);
    }

    @Override
    public void stop() {
        final IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        final Display display = workbench.getDisplay();
        display.syncExec(() -> {
            if (!display.isDisposed())
                workbench.close();
        });
    }

    private void shutdown() {
        log.debug("DBeaver is stopping"); //$NON-NLS-1$

        saveStartupActions();

        try {
            DBeaverInstanceServer server = instanceServer;
            if (server != null) {
                instanceServer = null;
                RuntimeUtils.runTask(monitor -> server.stopInstanceServer(), "Stop instance server", 1000);
            }
        } catch (Throwable e) {
            log.error(e);
        } finally {
            instance = null;

            log.debug("DBeaver shutdown completed"); //$NON-NLS-1$

            stopDebugWriter();
        }
    }

    private void initDebugWriter() {
        DBPPreferenceStore preferenceStore = DBeaverActivator.getInstance().getPreferences();
        if (!preferenceStore.getBoolean(DBeaverPreferences.LOGS_DEBUG_ENABLED)) {
            return;
        }
        String logLocation = preferenceStore.getString(DBeaverPreferences.LOGS_DEBUG_LOCATION);
        if (CommonUtils.isEmpty(logLocation)) {
            logLocation = GeneralUtils.getMetadataFolder().resolve(DBConstants.DEBUG_LOG_FILE_NAME).toAbsolutePath().toString(); //$NON-NLS-1$
        }
        logLocation = GeneralUtils.replaceVariables(logLocation, new SystemVariablesResolver());
        File debugLogFile = new File(logLocation);
        try {
            debugWriter = new LogOutputStream(debugLogFile);
            oldSystemOut = System.out;
            oldSystemErr = System.err;
            System.setOut(new PrintStream(new ProxyPrintStream(debugWriter, oldSystemOut)));
            System.setErr(new PrintStream(new ProxyPrintStream(debugWriter, oldSystemErr)));
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    private void stopDebugWriter() {
        if (oldSystemOut != null) System.setOut(oldSystemOut);
        if (oldSystemErr != null) System.setErr(oldSystemErr);

        if (debugWriter != null) {
            IOUtils.close(debugWriter);
            debugWriter = null;
        }
    }

    @Nullable
    @Override
    public IInstanceController getInstanceServer() {
        return instanceServer;
    }

    @Nullable
    public IInstanceController createInstanceClient() {
        return DBeaverInstanceServer.createClient(getDefaultInstanceLocation());
    }

    @Override
    public boolean isStandalone() {
        return true;
    }

    @Override
    public boolean isCommunity() {
        return true;
    }

    @Override
    public boolean isPrimaryInstance() {
        return primaryInstance && !isHeadlessMode();
    }

    @Override
    public boolean isHeadlessMode() {
        return headlessMode;
    }

    @Override
    public boolean isExclusiveMode() {
        return exclusiveMode;
    }

    public void setExclusiveMode(boolean exclusiveMode) {
        this.exclusiveMode = exclusiveMode;
    }

    public boolean isReuseWorkspace() {
        return reuseWorkspace;
    }

    public void setReuseWorkspace(boolean reuseWorkspace) {
        this.reuseWorkspace = reuseWorkspace;
    }

    @Override
    public void setHeadlessMode(boolean headlessMode) {
        this.headlessMode = headlessMode;
    }

    @Override
    public String getInfoDetails(DBRProgressMonitor monitor) {
        return null;
    }

    @Override
    public String getDefaultProjectName() {
        return "General";
    }

    private int showMessageBox(String title, String message, int style) {
        // Can't lock specified path
        Shell shell = new Shell(getDisplay(), SWT.ON_TOP);
        shell.setText(GeneralUtils.getProductTitle());
        MessageBox messageBox = new MessageBox(shell, style);
        messageBox.setText(title);
        messageBox.setMessage(message);
        int msgResult = messageBox.open();
        shell.dispose();
        return msgResult;
    }

    public void notifyVersionUpgrade(@NotNull Version currentVersion, @NotNull VersionDescriptor newVersion, boolean showSkip) {
        VersionUpdateDialog dialog = new VersionUpdateDialog(
            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
            currentVersion,
            newVersion,
            showSkip);
        dialog.open();
    }

    public void setResetUserPreferencesOnRestart(boolean resetUserPreferencesOnRestart) {
        this.resetUserPreferencesOnRestart = resetUserPreferencesOnRestart;
    }

    public void setResetWorkspaceConfigurationOnRestart(boolean resetWorkspaceConfigurationOnRestart) {
        this.resetWorkspaceConfigurationOnRestart = resetWorkspaceConfigurationOnRestart;
    }

    private void saveStartupActions() {
        final Properties props = new Properties();

        if (resetWorkspaceConfigurationOnRestart) {
            props.setProperty(RESET_WORKSPACE_CONFIGURATION, Boolean.TRUE.toString());
        }

        if (resetUserPreferencesOnRestart) {
            props.setProperty(RESET_USER_PREFERENCES, Boolean.TRUE.toString());
        }
        if (!props.isEmpty()) {
            Path path = GeneralUtils.getMetadataFolder().resolve(STARTUP_ACTIONS_FILE);
            try (Writer writer = Files.newBufferedWriter(path)) {
                props.store(writer, "DBeaver startup actions");
            } catch (Exception e) {
                log.error("Unable to save startup actions", e);
            }
        }
    }

    private void loadStartupActions(@NotNull Location instanceLoc) {
        Path instancePath;
        Path actionsPath;

        try {
            instancePath = RuntimeUtils.getLocalPathFromURL(instanceLoc.getURL()).resolve(DBPWorkspace.METADATA_FOLDER);
            actionsPath = instancePath.resolve(STARTUP_ACTIONS_FILE);
        } catch (Exception e) {
            return;
        }

        if (Files.notExists(actionsPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(actionsPath)) {
            final Properties properties = new Properties();
            properties.load(reader);

            if (!properties.isEmpty()) {
                processStartupActions(instancePath, properties.stringPropertyNames());
            }
        } catch (Exception e) {
            log.error("Unable to read startup actions", e);
        } finally {
            try {
                Files.delete(actionsPath);
            } catch (IOException e) {
                log.error("Unable to delete startup actions file: " + e.getMessage());
            }
        }
    }

    private void processStartupActions(
        @NotNull Path instancePath,
        @NotNull Set<String> actions
    ) throws Exception {
        final boolean resetUserPreferences = actions.contains(RESET_USER_PREFERENCES);
        final boolean resetWorkspaceConfiguration = actions.contains(RESET_WORKSPACE_CONFIGURATION);

        if (!resetUserPreferences && !resetWorkspaceConfiguration) {
            return;
        }
        Path path = instancePath.resolve(PLUGINS_FOLDER);
        if (Files.notExists(path) || !Files.isDirectory(path)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                log.trace("Deleting " + file);

                try {
                    Files.delete(file);
                } catch (IOException e) {
                    log.trace("Unable to delete " + file + ":" + e.getMessage());
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.endsWith(PLUGINS_FOLDER)) {
                    return FileVisitResult.CONTINUE;
                }

                final Path relative = path.relativize(dir);

                if (resetUserPreferences && !relative.startsWith(CORE_RESOURCES_PLUGIN_FOLDER)) {
                    return FileVisitResult.CONTINUE;
                }

                if (resetWorkspaceConfiguration && relative.startsWith(CORE_RESOURCES_PLUGIN_FOLDER)) {
                    return FileVisitResult.CONTINUE;
                }

                return FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                log.trace("Deleting " + dir);

                try {
                    Files.delete(dir);
                } catch (IOException e) {
                    log.trace("Unable to delete " + dir + ":" + e.getMessage());
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private class ProxyPrintStream extends OutputStream {
        private final OutputStream debugWriter;
        private final OutputStream stdOut;

        ProxyPrintStream(OutputStream debugWriter, OutputStream stdOut) {
            this.debugWriter = debugWriter;
            this.stdOut = stdOut;
        }

        @Override
        public void write(@NotNull byte[] b) throws IOException {
            debugWriter.write(b);
            stdOut.write(b);
        }

        @Override
        public void write(@NotNull byte[] b, int off, int len) throws IOException {
            debugWriter.write(b, off, len);
            stdOut.write(b, off, len);
        }

        @Override
        public void write(int b) throws IOException {
            debugWriter.write(b);
            stdOut.write(b);
        }

        @Override
        public void flush() throws IOException {
            debugWriter.flush();
            stdOut.flush();
        }

    }
}
