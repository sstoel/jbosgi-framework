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

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.bundle.support.a.ObjectA;
import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.log.LogService;

/**
 * The bundle object keeps its first (install) revision for as long as the bundle is installed: after an update and a
 * refresh the original revision - with its module class loader and all classes it loaded - stays reachable from the
 * bundle, so every updated bundle holds one discarded class loader for the rest of its life.
 *
 * <h3>Severity</h3>
 * minor - bounded (one stale revision per updated bundle, not one per update), but the class loader and its classes
 * (metaspace) of the original content of every updated bundle are never released.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.AbstractBundleState} ({@code initialRevision}).
 *
 * <h3>Found by</h3>
 * resolver workstream, leak sweep after wildfly/06: with the provided-wire leak (resolver/19) removed by a trial patch
 * the update/refresh loop still retained one old revision; HPROF root path:
 * {@code HostBundleRevision <- .initialRevision UserBundleState}.
 *
 * <h3>Spec reference</h3>
 * BundleWiring.isInUse javadoc (OSGi Core R8): "Once a bundle wiring is no longer in use, it is considered stale and is
 * discarded by the framework." FrameworkWiring.refreshBundles javadoc: removal pending bundle revisions "are removed"
 * when the bundles are refreshed; BundleRevisions.getRevisions then no longer lists the old revision - but the bundle
 * still references it.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleState.java:99,113}
 * ({@code initialRevision} is set in the constructor and never cleared) and {@code :202-208}
 * ({@code getBundleRevision}/{@code addBundleRevision}: the field is only a fallback before the first
 * addBundleRevision). Pre-existing (since 2013).
 *
 * <h3>Root cause</h3>
 * The constructor's revision is kept in a field that is never reset when the revision is replaced and removed.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=InitialRevisionRetainedIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}). The bundle imports nothing, so the provided-wire leak of resolver/19 does
 * not interfere.
 *
 * <h3>Observed</h3>
 * <pre>
 * testOriginalRevisionCollectableAfterUpdate: The class of the original revision of an updated and refreshed bundle
 *   must be garbage collectable, but it is still reachable
 * </pre>
 *
 * <h3>Expected</h3>
 * After update + refresh only the current revision is reachable from the bundle; the original revision, its class
 * loader and classes are collectable.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: the classes of all previous revisions of an updated and refreshed bundle are collected
 * ($SP/explore/resolver/leak/Leak2.java "update": 0 of N alive).
 *
 * <h3>Suggested fix</h3>
 * Clear {@code initialRevision} in {@code addBundleRevision} (or keep only {@code currentRevision}, set in the
 * constructor), so that the bundle only references the current revision and the revisions still in use are held by
 * the revision list until they are removed. Verified (JDK 25): with {@code initialRevision = null} in
 * {@code addBundleRevision} this test passes and the full framework itest suite has no new failure.
 *
 * <h3>Regression vs WF41</h3>
 * no - same field on WF41.
 *
 * @since 13-Oct-2026
 */
public class InitialRevisionRetainedIssueTestCase extends AbstractFrameworkLaunchTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testOriginalRevisionCollectableAfterUpdate() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
        Bundle bundle = framework.getBundleContext().installBundle("initial-revision", new ByteArrayInputStream(createBundle(0)));
        bundle.start();
        Class<?> original = bundle.loadClass(ObjectA.class.getName());
        WeakReference<Class<?>> ref = new WeakReference<Class<?>>(original);

        bundle.update(new ByteArrayInputStream(createBundle(1)));
        refresh(bundle);
        bundle.start();
        assertNotSame("The updated bundle has a new class loader", original, bundle.loadClass(ObjectA.class.getName()));
        original = null;

        flushLogHistory();
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        assertTrue("The class of the original revision of an updated and refreshed bundle must be garbage collectable, but it is still reachable", ref.get() == null);
    }

    private void refresh(Bundle bundle) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(bundle), new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        assertTrue("Refresh completes", latch.await(10, TimeUnit.SECONDS));
    }

    private void flushLogHistory() {
        ServiceReference<LogService> sref = framework.getBundleContext().getServiceReference(LogService.class);
        if (sref != null) {
            LogService log = framework.getBundleContext().getService(sref);
            for (int i = 0; i < 300; i++)
                log.log(LogService.LOG_INFO, "filler " + i);
            framework.getBundleContext().ungetService(sref);
        }
    }

    private byte[] createBundle(int version) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "initial.revision");
        attributes.putValue(Constants.BUNDLE_VERSION, version + ".0.0");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos, manifest);
        jos.putNextEntry(new JarEntry(ObjectA.class.getName().replace('.', '/') + ".class"));
        InputStream input = ObjectA.class.getResourceAsStream(ObjectA.class.getSimpleName() + ".class");
        try {
            byte[] buffer = new byte[1024];
            for (int len = input.read(buffer); len > 0; len = input.read(buffer))
                jos.write(buffer, 0, len);
        } finally {
            input.close();
        }
        jos.closeEntry();
        jos.close();
        return baos.toByteArray();
    }
}
