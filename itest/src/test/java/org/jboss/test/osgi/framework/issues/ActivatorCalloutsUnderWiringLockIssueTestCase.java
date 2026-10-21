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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The activator of a bundle that is started while it is not resolved, and the activator of a bundle that is updated or
 * uninstalled, runs under the framework wiring lock: an activator that waits for another thread which resolves an
 * unrelated bundle stalls both for up to 30 s.
 *
 * <h2>Severity</h2>
 * major. As concurrency/05 for these three operations: extenders and management agents that wait for other threads in
 * their activator stall the resolves of the whole framework.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManagerPlugin.startBundle}, {@code updateBundle} and {@code uninstallBundle}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of concurrency/05 (fw-major).
 *
 * <h2>Spec reference</h2>
 * Bundle.start, update and uninstall wait only for the state change of the same bundle; OSGi Core R8 4.7.3.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:928-934 (89ed93ad).
 *
 * <h2>Root cause</h2>
 * These operations took the framework wiring lock for their whole duration, the activator calls included.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ActivatorCalloutsUnderWiringLockIssueTestCase
 * </pre>
 * The activator of X lets another thread resolve the unrelated bundle Y and waits up to 5 s for it.
 *
 * <h2>Expected</h2>
 * Y is resolved while the activator of X waits.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
public class ActivatorCalloutsUnderWiringLockIssueTestCase extends OSGiTest {

    private Framework framework;
    private BundleContext context;

    @Before
    public void setUp() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/activator-callouts-wiring-lock").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        context = framework.getBundleContext();
    }

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(40000);
        }
    }

    @Test
    public void testStartOfUnresolvedBundle() throws Exception {
        Bundle x = context.installBundle("callouts-start-x", bundle("callouts.start.x", true));
        AtomicReference<String> result = resolveInCallout("start", context.installBundle("callouts-start-y", bundle("callouts.start.y", false)));
        x.start();
        assertEquals("Y is resolved while the activator start of the unresolved bundle X waits for it", "resolved", result.get());
    }

    @Test
    public void testUpdate() throws Exception {
        Bundle x = context.installBundle("callouts-update-x", bundle("callouts.update.x", true));
        x.start();
        AtomicReference<String> result = resolveInCallout("stop", context.installBundle("callouts-update-y", bundle("callouts.update.y", false)));
        x.update(bundle("callouts.update.x", true));
        assertEquals("Y is resolved while the activator stop of the updated bundle X waits for it", "resolved", result.get());
    }

    @Test
    public void testUninstall() throws Exception {
        Bundle x = context.installBundle("callouts-uninstall-x", bundle("callouts.uninstall.x", true));
        x.start();
        AtomicReference<String> result = resolveInCallout("stop", context.installBundle("callouts-uninstall-y", bundle("callouts.uninstall.y", false)));
        x.uninstall();
        assertEquals("Y is resolved while the activator stop of the uninstalled bundle X waits for it", "resolved", result.get());
    }

    // The callout of the activator resolves Y on another thread and waits for it
    private AtomicReference<String> resolveInCallout(String callout, final Bundle y) {
        final AtomicReference<String> result = new AtomicReference<String>("activator " + callout + " not called");
        final AtomicReference<ServiceRegistration<Runnable>> registration = new AtomicReference<ServiceRegistration<Runnable>>();
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("callout", callout);
        registration.set(context.registerService(Runnable.class, new Runnable() {
            public void run() {
                registration.get().unregister();
                final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
                Thread thread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            framework.adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(y));
                        } catch (Throwable th) {
                            failure.set(th);
                        }
                    }
                }, "callouts-resolve-y");
                thread.setDaemon(true);
                thread.start();
                try {
                    thread.join(5000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (thread.isAlive())
                    result.set("blocked: " + thread.getState());
                else if (failure.get() != null)
                    result.set("failed: " + failure.get());
                else
                    result.set(y.getState() == Bundle.RESOLVED ? "resolved" : "not resolved, state " + y.getState());
            }
        }, props));
        return result;
    }

    private static InputStream bundle(final String symbolicName, final boolean withActivator) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        if (withActivator)
            archive.addClasses(CalloutActivator.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                if (withActivator) {
                    builder.addBundleActivator(CalloutActivator.class.getName());
                    builder.addImportPackages("org.osgi.framework");
                }
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    /** Runs the callout service of the test (callout=start or callout=stop) */
    public static class CalloutActivator implements BundleActivator {
        public void start(BundleContext context) throws Exception {
            callout(context, "start");
        }

        public void stop(BundleContext context) throws Exception {
            callout(context, "stop");
        }

        private static void callout(BundleContext context, String what) throws Exception {
            ServiceReference<?>[] refs = context.getServiceReferences(Runnable.class.getName(), "(callout=" + what + ")");
            if (refs != null) {
                Runnable runnable = (Runnable) context.getService(refs[0]);
                if (runnable != null)
                    runnable.run();
            }
        }
    }
}
