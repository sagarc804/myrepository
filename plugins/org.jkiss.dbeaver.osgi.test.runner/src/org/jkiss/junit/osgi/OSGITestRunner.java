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
package org.jkiss.junit.osgi;

import org.eclipse.equinox.internal.app.CommandLineArgs;
import org.eclipse.osgi.internal.framework.EquinoxBundle;
import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.service.environment.EnvironmentInfo;
import org.eclipse.osgi.service.runnable.ApplicationLauncher;
import org.eclipse.osgi.util.ManifestElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.junit.osgi.annotation.RunWithApplication;
import org.jkiss.junit.osgi.annotation.RunWithProduct;
import org.jkiss.junit.osgi.annotation.RunnerProxy;
import org.jkiss.junit.osgi.behaviors.IAsyncApplication;
import org.jkiss.junit.osgi.delegate.ProxyFilter;
import org.jkiss.junit.osgi.launcher.TestLauncher;
import org.jkiss.utils.Pair;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleWiring;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <h2>OSGITestRunner</h2>
 * <p>
 *     The class is responsible for running the OSGi tests inside IDEA.
 *     It does by starting the OSGi framework and loading all the required bundles.
 *     If OSGI environment is already running, it will not start a new one.
 *     <li>{@link RunWithProduct} annotation to specify the product to run the test in.</li>
 *     <li>{@link RunnerProxy} to specify the runner which should be executed in OSGI environment.</li>
 *     <li>{@link RunWithApplication} to specify the application to run the test in.</li>
 *     <br>
 *     Should allow debugging of the tests in the IDEA.
 * </p>
 */
public class OSGITestRunner extends BlockJUnit4ClassRunner {

    public static final Pattern startLevel = Pattern.compile("@(\\d+):start");
    private static final Log log = Log.getLog(OSGITestRunner.class);
    private static final boolean DEBUG_BUNDLE_LAUNCH = false;
    private final Class<?> testClass;
    private Framework framework;
    private Path productPath;

    private String testBundleName;
    private Bundle testBundle;
    private String appRegistryName;
    private String appBundleName;
    private String[] args;
    private Object runnerProxy = null;

    public OSGITestRunner(
        @NotNull Class<? extends IAsyncApplication> testClass
    ) throws Exception {
        super(testClass);
        if (testClass.getAnnotation(RunnerProxy.class) == null) {
            throw new IllegalArgumentException("RunnerProxy annotation not found");
        }
        this.testClass = testClass;
        if (isRunFromIDEA()) {
            //use UTF-8 for run
            try {
                // Determine name of test bundle
                // Analyze classpath, we don't have other way because we are not in OSGI container yet
                // All test bundles are compiled and classes are in <bundle-path>/target
                URL resource = testClass.getClassLoader().getResource(testClass.getName().replace('.', '/') + ".class");
                if (resource != null) {
                    String testClassPath = resource.toString();
                    Pattern pluginNamePattern = Pattern.compile(".+/([\\w.]+)/target/");
                    Matcher matcher = pluginNamePattern.matcher(testClassPath);
                    if (matcher.find()) {
                        testBundleName = matcher.group(1);
                    }
                }
            } catch (Exception e) {
                log.error(e);
            }

            this.productPath = findProduct();

            getAppBundleFromAnnotation();
            this.framework = initializeFramework();
            startFramework();
            createProxyInTheBundleClassloader(testBundle.loadClass(testClass.getName()));
        } else {
            createProxyInSameClassloader();
        }
    }

    private void getAppBundleFromAnnotation() {
        if (testClass.getAnnotation(RunWithApplication.class) != null) {
            RunWithApplication annotation = testClass.getAnnotation(RunWithApplication.class);
            this.appRegistryName = annotation.registryName();
            this.appBundleName = annotation.bundleName();
            this.args = annotation.args();
        } else {
            throw new IllegalArgumentException("Application not found");
        }
    }

    @Override
    public void filter(Filter filter) throws NoTestsRemainException {
        super.filter(filter);
        try {
            if (isRunFromIDEA()) {
                Constructor<?> constructor = testBundle.loadClass(ProxyFilter.class.getName()).getConstructors()[0];
                Object filterProxy = constructor.newInstance(filter);
                runnerProxy.getClass().getMethod("filter", testBundle.loadClass(Filter.class.getName())).invoke(runnerProxy, filterProxy);
            } else {
                runnerProxy.getClass().getMethod("filter", Filter.class).invoke(runnerProxy, filter);
            }
        } catch (Exception e) {
            log.error("Error applying filter to proxy", e);
        }
    }

    @Override
    public void run(RunNotifier notifier) {
        if (isRunFromIDEA()) {
            runInsideOSGI(notifier);
        } else {
            launchInExistingOSGI(notifier);
        }
    }

    private boolean isRunFromIDEA() {
        return "app".equals(this.getClass().getClassLoader().getName());
    }

    private Path findProduct() {
        if (testClass.getAnnotation(RunWithProduct.class) != null) {
            RunWithProduct annotation = testClass.getAnnotation(RunWithProduct.class);
            String product = annotation.value();
            Path workspace = Path.of(findWorkspaceDir().toString());
            return workspace.resolve(product);
        } else {
            throw new IllegalArgumentException("Product not found");
        }
    }

    private static Path findWorkspaceDir() {
        Path workPath = Paths.get("").toAbsolutePath();
        Path currentPath = workPath.toAbsolutePath();
        while (currentPath != null) {
            Path potentialWorkspaceDir = currentPath.resolve("dbeaver-workspace/products");
            if (Files.exists(potentialWorkspaceDir)) {
                return workPath.relativize(potentialWorkspaceDir);
            }
            currentPath = currentPath.getParent();
        }
        throw new IllegalStateException("dbeaver-workspace/products directory not found");
    }

    private void launchInExistingOSGI(RunNotifier notifier) {
        try {
            if (testClass.getAnnotation(RunnerProxy.class) != null) {
                Arrays.stream(runnerProxy.getClass().getMethods()).filter(it -> it.getName().equals("run")).findFirst().orElseThrow()
                    .invoke(runnerProxy, notifier);
            }
        } catch (Throwable throwable) {
            log.error("An error occurred while running the test", throwable);
        }
    }

    private void createProxyInSameClassloader(
    ) throws NoSuchMethodException, ClassNotFoundException, InstantiationException, IllegalAccessException, InvocationTargetException {
        Constructor<?> constructor = testClass
            .getClassLoader()
            .loadClass(testClass.getAnnotation(RunnerProxy.class).value().getName())
            .getConstructor(Class.class);
        runnerProxy = constructor.newInstance(testClass);
    }

    private void runInsideOSGI(RunNotifier notifier) {
        try {
            if (testClass.getAnnotation(RunnerProxy.class) != null) {
                Class<?> runningClass = testBundle.loadClass(testClass.getName());
                if (IAsyncApplication.class.isAssignableFrom(testClass)) {
                    long startTime = System.currentTimeMillis();
                    long endTime = 0;
                    boolean setUpIsDone = false;
                    while (!setUpIsDone && endTime < 300000) {
                        setUpIsDone = (boolean) runningClass.getMethod("verifyLaunched")
                            .invoke(runningClass.getConstructor().newInstance());
                        endTime = System.currentTimeMillis() - startTime;
                        if (!setUpIsDone) {
                            Thread.sleep(100);
                        }
                    }
                }
                Method runMethod = Arrays.stream(runnerProxy.getClass().getMethods()).filter(it -> it.getName().equals("run"))
                    .findFirst().orElseThrow();
                Object proxyNotifier = createProxyNotifier(notifier);
                runMethod.invoke(runnerProxy, proxyNotifier);
            }
        } catch (Throwable throwable) {
            log.error("An error occurred while running the test", throwable);
        } finally {
            try {
                framework.stop();
                framework.waitForStop(0);
            } catch (Exception e) {
                log.error("Error stopping framework", e);
            }
        }
    }

    private void startFramework() throws Exception {
        framework.init();
        // Start the OSGi framework
        BundleContext context = framework.getBundleContext();
        // Load and start all bundles
        loadAndStartBundles(context);
        EquinoxConfiguration equinoxConfig = null;
        if (args != null) {
            ServiceReference<EnvironmentInfo> configRef = context.getServiceReference(EnvironmentInfo.class);
            equinoxConfig = (EquinoxConfiguration) context.getService(configRef);
            equinoxConfig.setAllArgs(args);
            equinoxConfig.setAppArgs(args);
        }
        framework.start();
        if (equinoxConfig != null) {
            Method processCommandLine = CommandLineArgs.class.getDeclaredMethod(
                "processCommandLine",
                EnvironmentInfo.class
            );
            processCommandLine.setAccessible(true);
            processCommandLine.invoke(null, equinoxConfig);
        }
        TestLauncher launcher = new TestLauncher(context);
        context.registerService(ApplicationLauncher.class.getName(), launcher,
            null
        );
        if (IAsyncApplication.class.isAssignableFrom(testClass)) {
            Thread thread = new Thread(() -> launcher.start(appRegistryName, args));
            thread.start();
        } else {
            launcher.start(appRegistryName, args);
        }
    }

    @NotNull
    private void createProxyInTheBundleClassloader(
        @NotNull Class<?> runningClass
    ) throws NoSuchMethodException, ClassNotFoundException, InstantiationException, IllegalAccessException, InvocationTargetException {
        Constructor<?> proxy = testBundle.loadClass(testClass.getAnnotation(RunnerProxy.class)
            .value()
            .getName()).getConstructor(Class.class);
        runnerProxy = proxy.newInstance(runningClass);
    }

    @NotNull
    private Object createProxyNotifier(
        RunNotifier notifier
    ) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException {
        Object newOsgiNotifier = testBundle.loadClass(RunNotifier.class.getName()).getConstructor().newInstance();

        try {
            Class<?> osgiListenerClass = testBundle.loadClass(OSGITestRunListener.class.getName());
            Object osgiListener = osgiListenerClass.getConstructor(Object.class).newInstance(notifier);
            Method addListenerMethod = Arrays.stream(newOsgiNotifier.getClass().getMethods())
                .filter(method -> method.getName().equals("addListener")).findFirst().orElseThrow();
            addListenerMethod.invoke(newOsgiNotifier, osgiListener);
        } catch (Throwable e) {
            log.debug(e);
        }

        return newOsgiNotifier;
    }

    private Framework initializeFramework() {
        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage", "osgi-cache");
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        // Specify the directory where the dev.properties file is located
        config.put("osgi.dev", "file:" + productPath.toAbsolutePath().resolve("dev.properties").normalize());
        if (DEBUG_BUNDLE_LAUNCH) {
            config.put("osgi.debug", "file:" + productPath.toAbsolutePath().resolve("debug_config").normalize());
            config.put("org.osgi.framework.debug", "true");
            config.put("org.osgi.framework.debug.loader", "true");
            config.put("org.osgi.framework.debug.resolver", "true");
        }
        // Enable boot delegation, to avoid class loading issues for some classes
        config.put("osgi.compatibility.bootdelegation", "true");
        FrameworkFactory frameworkFactory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        return frameworkFactory.newFramework(config);
    }

    private Bundle loadAndStartBundles(BundleContext context) throws Exception {
        // Specify the directory where the bundles are located
        File bundleDir = productPath.resolve("config.ini").toFile();
        Properties props = new Properties();
        Set<String> installed = Arrays.stream(framework.getBundleContext().getBundles())
            .map(Bundle::getLocation)
            .collect(Collectors.toSet());
        props.load(new FileInputStream(bundleDir));
        PriorityQueue<Pair<Bundle, Integer>> bundlesByStartLevel = new PriorityQueue<>((v1, v2) -> {
            Integer firstStart = v1.getSecond();
            Integer secondStart = v2.getSecond();
            return Integer.compare(firstStart, secondStart);
        });
        // Install all bundles from the directory
        for (String bundleFile : ManifestElement.getArrayFromList(props.getProperty("osgi.bundles"))) {
            if (bundleFile.contains(".app") && !bundleFile.contains(appBundleName) && !bundleFile.contains("org.eclipse")) {
                continue;
            }
            Matcher matcher = startLevel.matcher(bundleFile);
            int startLevel = 0;
            if (matcher.find()) {
                startLevel = Integer.parseInt(matcher.group(1));
            }
            if (bundleFile.lastIndexOf('@') >= 0) {
                bundleFile = bundleFile.substring(0, bundleFile.lastIndexOf('@'));
            }
            if (installed.contains(bundleFile) || bundleFile.contains("org.eclipse.osgi_")) {
                continue;
            }
            try {
                Bundle bundle = context.installBundle(bundleFile);
                if (startLevel != 0 || bundle.getSymbolicName().equals(testBundleName)) {
                    bundlesByStartLevel.add(new Pair<>(bundle, startLevel));
                }
            } catch (BundleException e) {
                log.error("Error initializing bundle message", e);
            }
        }

        Bundle appBundle = null;
        // find appBundleContainingClassname app bundle
        for (Bundle bundle : context.getBundles()) {
            if (bundle.getSymbolicName().contains(this.appBundleName)) {
                appBundle = bundle;
                break;
            }
        }
        // Start all installed bundles
        for (Pair<Bundle, Integer> bundleWithStartLevel : bundlesByStartLevel) {
            Bundle bundle = bundleWithStartLevel.getFirst();

            if (bundle instanceof EquinoxBundle eb && eb.isFragment()) {
                // We need to activate main test bundle (it has to be in the list of auto-activation bundles)
                // For that we also check that test bundle is a fragment.
                // In this case we activate fragment host instead of main bundle
                Bundle hostBundle = null;
                if (bundle.getSymbolicName().equals(testBundleName)) {
                    Dictionary<String, String> headers = bundle.getHeaders();
                    String hostBundleHeader = headers.get("Fragment-Host");
                    if (hostBundleHeader != null) {
                        for (Bundle b : context.getBundles()) {
                            if (b.getSymbolicName().equals(hostBundleHeader)) {
                                hostBundle = b;
                                break;
                            }
                        }
                    }
                }
                if (hostBundle != null) {
                    bundle = hostBundle;
                }
            }

            if (bundle.getState() != Bundle.ACTIVE) {
                try {
                    bundle.start();
                    try {
                        bundle.loadClass(testClass.getName());
                        testBundle = bundle;
                    } catch (ClassNotFoundException e) {
                        // ignore, expected
                        //log.error(e);
                    }
                    log.debug("Started bundle: " + bundle.getSymbolicName());
                } catch (BundleException e) {
                    if (!e.getMessage().contains("Invalid operation on a fragment")) {
                        log.error("Error starting bundle message", e);
                    }
                }
            }
        }
        for (Pair<Bundle, Integer> bundleIntegerPair : bundlesByStartLevel) {
            if (bundleIntegerPair.getFirst().adapt(BundleWiring.class) == null) {
                log.error("Bundle not resolved: " + bundleIntegerPair.getFirst().getSymbolicName());
            }
        }
        return appBundle;
    }
}

