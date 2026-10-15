package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * When the asynchronous part of FrameworkWiring.refreshBundles fails - for example because it cannot obtain the
 * bundle locks within the 30 second LockManager timeout while another lifecycle operation runs - the refresh is
 * abandoned silently: the FrameworkListeners passed to refreshBundles never receive PACKAGES_REFRESHED, and no
 * FrameworkEvent.ERROR is published.
 *
 * <h3>Severity</h3>
 * major - callers that wait for the refresh to complete (PackageAdmin.refreshPackages users, deployers, management
 * operations) wait forever; the refresh is lost without any trace.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkWiringImpl#refreshBundlesInternal}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful multi-threaded API fuzzer ({@code Stateful}, 4 and 8 threads): "refreshBundles did not
 * complete in 60s" while the Framework Refresh Thread was idle (its task had ended without notifying the listener).
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.refreshBundles javadoc (OSGi Core R8, 10.4.8.4): "For any exceptions that are thrown during any of
 * these steps, a framework event of type FrameworkEvent.ERROR is fired containing the exception. ... When this
 * process completes after the bundles are refreshed, the Framework will fire a Framework event of type
 * FrameworkEvent.PACKAGES_REFRESHED to announce it has completed the bundle refresh. The specified FrameworkListeners
 * are notified in the order specified. Each specified FrameworkListener will be called with a Framework event of type
 * FrameworkEvent.PACKAGES_REFRESHED."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:186-188}
 * ({@code lockManager.lockItems(Method.REFRESH, items)} throws {@code LockException} after 30 s, outside the try
 * block) and {@code :240-245} (PACKAGES_REFRESHED is only fired at the end of the try block; a
 * {@code BundleException} is rethrown as {@code IllegalStateException}). The Runnable runs on the refresh executor
 * (:144-151), so the exception just ends the task.
 *
 * <h3>Root cause</h3>
 * No finally/catch-all that fires FrameworkEvent.ERROR and PACKAGES_REFRESHED when the refresh task fails.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=RefreshListenerNotNotifiedIssueTestCase} (in jbosgi-framework; about 45 s).
 *
 * <h3>Observed</h3>
 * <pre>
 * testListenerNotifiedAfterLockTimeout (50 s): The refresh listener must receive PACKAGES_REFRESHED, events: []
 * (neither the refresh listener nor a FrameworkEvent.ERROR was delivered)
 * </pre>
 *
 * <h3>Expected</h3>
 * The refresh listener receives PACKAGES_REFRESHED once the refresh ends (after the slow activator finishes, or with
 * a FrameworkEvent.ERROR if the refresh had to give up).
 *
 * <h3>Reference behaviour</h3>
 * Not compared (the 30 s lock timeout is specific to jbosgi).
 *
 * <h3>Suggested fix</h3>
 * Wrap the body of refreshBundlesInternal (including lockItems) in try/catch/finally: fire FrameworkEvent.ERROR for
 * an exception and always fire PACKAGES_REFRESHED to the listeners.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 13-Oct-2026
 */
public class RefreshListenerNotNotifiedIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testListenerNotifiedAfterLockTimeout() throws Exception {
        final Bundle slow = install("refresh-listener-slow", SlowStartActivator.class);
        try {
            assertTrue("Precondition: resolved", getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(slow)));
            // the activator holds the START lock of the bundle for 35 s, longer than the 30 s LockManager timeout
            Thread starter = new Thread(new Runnable() {
                public void run() {
                    try {
                        slow.start();
                    } catch (Exception ex) {
                        // ignore
                    }
                }
            });
            starter.start();
            Thread.sleep(500);

            final List<String> events = new CopyOnWriteArrayList<String>();
            FrameworkListener errors = new FrameworkListener() {
                public void frameworkEvent(FrameworkEvent event) {
                    if (event.getType() == FrameworkEvent.ERROR)
                        events.add("ERROR " + event.getThrowable());
                }
            };
            getSystemContext().addFrameworkListener(errors);
            final CountDownLatch latch = new CountDownLatch(1);
            getSystemContext().getBundle().adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(slow), new FrameworkListener() {
                public void frameworkEvent(FrameworkEvent event) {
                    events.add("listener " + event.getType());
                    if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED)
                        latch.countDown();
                }
            });
            starter.join(60000);
            boolean notified = latch.await(15, TimeUnit.SECONDS);
            getSystemContext().removeFrameworkListener(errors);
            assertTrue("The refresh listener must receive PACKAGES_REFRESHED, events: " + new ArrayList<String>(events), notified);
        } finally {
            slow.uninstall();
        }
    }

    public static class SlowStartActivator implements BundleActivator {
        public void start(BundleContext context) throws Exception {
            Thread.sleep(35000);
        }

        public void stop(BundleContext context) {
        }
    }

    private Bundle install(String symbolicName, Class<?> activator) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(Constants.BUNDLE_ACTIVATOR, activator.getName());
        attributes.putValue(Constants.IMPORT_PACKAGE, "org.osgi.framework");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        String path = activator.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(path));
        InputStream in = getClass().getClassLoader().getResourceAsStream(path);
        byte[] buffer = new byte[4096];
        for (int len = in.read(buffer); len > 0; len = in.read(buffer))
            jar.write(buffer, 0, len);
        in.close();
        jar.closeEntry();
        jar.close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
