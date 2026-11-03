/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.jboss.test.osgi.framework.issues.hooks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;

/**
 * Helpers of the issue tests of the R8 release check: frameworks of their own with a storage area each, test bundles
 * with arbitrary headers, activator hooks and a holder of the framework wiring lock.
 */
public final class ReleaseCheckSupport {

    private static final List<String> HOOK_KEYS = new ArrayList<String>();

    private ReleaseCheckSupport() {
    }

    public static File storage(String name) {
        return new File("target/release-check/" + name).getAbsoluteFile();
    }

    public static void delete(File file) {
        File[] files = file.listFiles();
        for (File aux : files != null ? files : new File[0])
            delete(aux);
        file.delete();
    }

    /** A framework that is not started yet; extra properties as name, value, ... */
    public static Framework newFramework(File storage, boolean clean, String... extra) throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, storage.getPath());
        if (clean)
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        for (int i = 0; i < extra.length; i += 2)
            props.put(extra[i], extra[i + 1]);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }

    public static Framework newConnectFramework(File storage, ModuleConnector connector) throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, storage.getPath());
        return ServiceLoader.loadService(ConnectFrameworkFactory.class).newFramework(props, connector);
    }

    public static void stop(Framework framework) throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(60000);
        }
    }

    /** The content of a bundle; headers as name, value, ... */
    public static InputStream bundle(String symbolicName, Class<?>[] classes, String... headers) throws IOException {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName + ".jar");
        if (classes != null && classes.length > 0)
            archive.addClasses(classes);
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        main.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        for (int i = 0; i < headers.length; i += 2)
            main.putValue(headers[i], headers[i + 1]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        archive.add(new ByteArrayAsset(out.toByteArray()), "META-INF/MANIFEST.MF");
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    public static InputStream bundle(String symbolicName, String... headers) throws IOException {
        return bundle(symbolicName, null, headers);
    }

    /** The content of a bundle whose activator calls the hooks of its symbolic name, with an import of org.osgi.framework */
    public static InputStream hookedBundle(String symbolicName, String... headers) throws IOException {
        String imports = "org.osgi.framework";
        List<String> all = new ArrayList<String>();
        for (int i = 0; i < headers.length; i += 2) {
            if (Constants.IMPORT_PACKAGE.equals(headers[i])) {
                imports = headers[i + 1] + "," + imports;
            } else {
                all.add(headers[i]);
                all.add(headers[i + 1]);
            }
        }
        all.add(Constants.IMPORT_PACKAGE);
        all.add(imports);
        all.add(Constants.BUNDLE_ACTIVATOR);
        all.add(HookActivator.class.getName());
        return bundle(symbolicName, new Class<?>[] { HookActivator.class }, all.toArray(new String[all.size()]));
    }

    /** The hook that the activator of the bundle with the symbolic name calls in start or stop */
    public static void hook(String kind, String symbolicName, Consumer<BundleContext> hook) {
        String key = HookActivator.key(kind, symbolicName);
        synchronized (HOOK_KEYS) {
            HOOK_KEYS.add(key);
        }
        System.getProperties().put(key, hook);
    }

    public static void clearHooks() {
        synchronized (HOOK_KEYS) {
            for (String key : HOOK_KEYS)
                System.getProperties().remove(key);
            HOOK_KEYS.clear();
        }
    }

    public static void await(CountDownLatch latch, String what) throws InterruptedException {
        if (latch.await(30, TimeUnit.SECONDS) == false)
            throw new IllegalStateException("Not reached in 30 s: " + what);
    }

    public static File bundleDir(File storage, long bundleId) {
        return new File(storage, "bundle-" + bundleId);
    }

    /** The thread waits in the framework's lock manager or for a lock of a framework class */
    public static boolean isWaitingForLock(Thread thread) {
        Thread.State state = thread.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING && state != Thread.State.BLOCKED)
            return false;
        for (StackTraceElement element : thread.getStackTrace()) {
            if (element.getClassName().endsWith("LockManagerImpl") && element.getMethodName().equals("lockItemsInternal"))
                return true;
            if (element.getClassName().startsWith("org.jboss.osgi.framework.internal") && element.getMethodName().startsWith("lock"))
                return true;
        }
        return false;
    }

    public static boolean awaitWaitingForLock(Thread thread, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (isWaitingForLock(thread))
                return true;
            Thread.sleep(2);
        }
        return isWaitingForLock(thread);
    }

    public static List<Thread> threadsNamed(String name) {
        List<Thread> result = new ArrayList<Thread>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName()) && thread.isAlive())
                result.add(thread);
        }
        return result;
    }

    /**
     * Holds the framework wiring lock: a thread resolves a bundle and waits in the begin of a resolver hook, which the
     * framework calls under the wiring lock, until {@link #release()}
     */
    public static final class WiringLockHolder implements ResolverHookFactory {
        private final CountDownLatch inHook = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final BundleContext context;
        private final Bundle unresolved;
        private Thread thread;

        public WiringLockHolder(BundleContext context) throws Exception {
            this.context = context;
            String name = "wiring.lock.holder." + System.nanoTime();
            unresolved = context.installBundle(name, bundle(name));
        }

        public void begin() throws Exception {
            context.registerService(ResolverHookFactory.class, this, null);
            thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        unresolved.start();
                    } catch (Exception ex) {
                        // the framework may stop
                    }
                }
            }, "wiring-lock-holder");
            thread.setDaemon(true);
            thread.start();
            await(inHook, "the resolve holds the wiring lock in the resolver hook");
        }

        public void release() throws InterruptedException {
            release.countDown();
            if (thread != null)
                thread.join(60000);
        }

        @Override
        public ResolverHook begin(Collection<BundleRevision> triggers) {
            if (Thread.currentThread() == thread && inHook.getCount() > 0) {
                inHook.countDown();
                try {
                    release.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ResolverHook() {
                public void filterResolvable(Collection<BundleRevision> candidates) {
                }

                public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                }

                public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                }

                public void end() {
                }
            };
        }
    }
}
