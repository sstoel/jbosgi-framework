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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
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
 * The bootstrap services keep the bundles that they install, resolve and start at a launch for the life of the
 * framework: a bundle that is installed at a launch (auto start, or restored from the storage) and is updated or
 * uninstalled later keeps its first revision, with its module class loader and all classes it loaded, until the
 * framework stops.
 *
 * <h3>Severity</h3>
 * minor - bounded (one stale revision per bundle installed at a launch), but in WildFly every bundle deployed at boot
 * and redeployed later keeps one discarded class loader (metaspace) for as long as the server runs.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.spi.BootstrapBundlesResolve} ({@code installedRevisions})
 * and {@code BootstrapBundlesActivate} ({@code resolvedBundles}).
 *
 * <h3>Found by</h3>
 * heap analysis on WildFly 42 after the redeploy of a bundle that was deployed at boot: the old class loader was
 * reachable through {@code BootstrapBundlesResolve.installedRevisions} (branch redeploy-hangs).
 *
 * <h3>Spec reference</h3>
 * BundleWiring.isInUse javadoc (OSGi Core R8): "Once a bundle wiring is no longer in use, it is considered stale and is
 * discarded by the framework." FrameworkWiring.refreshBundles javadoc: removal pending bundle revisions are removed
 * when the bundles are refreshed.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/spi/BootstrapBundlesResolve.java:58,96} (the
 * revisions installed at the launch, never cleared) and {@code BootstrapBundlesActivate.java:45,62} (the bundles
 * started at the launch, never cleared). Both services stay up until the framework stops. Pre-existing (WF41 has the
 * same fields).
 *
 * <h3>Root cause</h3>
 * The sets that hand the bundles from one bootstrap phase to the next are kept in the services after the phase is
 * done.
 *
 * <h3>Reproduction</h3>
 * {@code mvn -pl itest test -Dtest=BootstrapBundleRetainedIssueTestCase} (in jbosgi-framework, after
 * {@code mvn install -DskipTests}). The bundle imports nothing, so no wire of another bundle keeps its revision.
 *
 * <h3>Observed</h3>
 * <pre>
 * testAutoStartedBundleCollectableAfterUpdate: The class of the first revision of an auto started bundle must be garbage
 *   collectable after an update and a refresh, but it is still reachable
 * testAutoStartedBundleCollectableAfterUninstall: The class of an auto started bundle must be garbage collectable after
 *   an uninstall and a refresh, but it is still reachable
 * testRestoredBundleCollectableAfterUpdate: The class of the first revision of a bundle restored from the storage must
 *   be garbage collectable after an update and a refresh, but it is still reachable
 * </pre>
 *
 * <h3>Expected</h3>
 * After the update or the uninstall and a refresh the first revision of a bundle installed at a launch, its class
 * loader and its classes are collectable, as for a bundle installed later.
 *
 * <h3>Regression vs WF41</h3>
 * no - same fields on WF41.
 *
 * @since 05-Nov-2026
 */
public class BootstrapBundleRetainedIssueTestCase extends AbstractFrameworkLaunchTest {

    private static final String SYMBOLIC_NAME = "bootstrap.retained";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testAutoStartedBundleCollectableAfterUpdate() throws Exception {
        startWithAutoStartBundle();
        WeakReference<Class<?>> ref = loadAndUpdate();
        assertCollectable("The class of the first revision of an auto started bundle must be garbage collectable after an update and a refresh, but it is still reachable", ref);
    }

    @Test
    public void testAutoStartedBundleCollectableAfterUninstall() throws Exception {
        startWithAutoStartBundle();
        WeakReference<Class<?>> ref = loadAndUninstall();
        assertCollectable("The class of an auto started bundle must be garbage collectable after an uninstall and a refresh, but it is still reachable", ref);
    }

    @Test
    public void testRestoredBundleCollectableAfterUpdate() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
        framework.getBundleContext().installBundle("bootstrap-retained", new ByteArrayInputStream(createBundle(0))).start();

        // The restart restores the bundle from the storage, through the bootstrap services of the persistent bundles
        framework.stop();
        assertEquals("Precondition: the framework stops", FrameworkEvent.STOPPED, framework.waitForStop(10000).getType());
        framework.start();

        WeakReference<Class<?>> ref = loadAndUpdate();
        assertCollectable("The class of the first revision of a bundle restored from the storage must be garbage collectable after an update and a refresh, but it is still reachable", ref);
    }

    private void startWithAutoStartBundle() throws Exception {
        File file = new File(System.getProperty("test.archive.directory", "target/test-libs"), "bootstrap-retained.jar");
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), createBundle(0));
        Map<String, String> props = getFrameworkInitProperties(true);
        props.put(org.jboss.osgi.framework.Constants.PROPERTY_AUTO_START_URLS, file.toURI().toString());
        framework = newFramework(props);
        framework.start();
    }

    // The references to the bundle and its first revision stay in this frame
    private WeakReference<Class<?>> loadAndUpdate() throws Exception {
        Bundle bundle = getTestBundle();
        Class<?> first = bundle.loadClass(ObjectA.class.getName());
        assertNotSame("Precondition: the class is loaded by the bundle", ObjectA.class, first);

        bundle.update(new ByteArrayInputStream(createBundle(1)));
        refresh(Collections.singleton(bundle));
        bundle.start();
        assertNotSame("Precondition: the updated bundle has a new class loader", first, bundle.loadClass(ObjectA.class.getName()));
        return new WeakReference<Class<?>>(first);
    }

    private WeakReference<Class<?>> loadAndUninstall() throws Exception {
        Bundle bundle = getTestBundle();
        Class<?> first = bundle.loadClass(ObjectA.class.getName());
        assertNotSame("Precondition: the class is loaded by the bundle", ObjectA.class, first);

        bundle.uninstall();
        refresh(null);
        assertEquals("Precondition: the bundle is uninstalled", Bundle.UNINSTALLED, bundle.getState());
        return new WeakReference<Class<?>>(first);
    }

    private Bundle getTestBundle() {
        Bundle result = null;
        for (Bundle aux : framework.getBundleContext().getBundles()) {
            if (SYMBOLIC_NAME.equals(aux.getSymbolicName()))
                result = aux;
        }
        assertNotNull("Precondition: the bundle is installed", result);
        assertEquals("Precondition: the bundle is active", Bundle.ACTIVE, result.getState());
        return result;
    }

    private void assertCollectable(String message, WeakReference<Class<?>> ref) throws Exception {
        flushLogHistory();
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        assertNull(message, ref.get());
    }

    private void refresh(Collection<Bundle> bundles) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(bundles, new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        assertTrue("Precondition: the refresh completes", latch.await(10, TimeUnit.SECONDS));
    }

    // The log history keeps the last entries, which reference the bundle
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
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, SYMBOLIC_NAME);
        attributes.putValue(Constants.BUNDLE_VERSION, version + ".0.0");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos, manifest);
        jos.putNextEntry(new JarEntry(ObjectA.class.getName().replace('.', '/') + ".class"));
        InputStream input = ObjectA.class.getResourceAsStream(ObjectA.class.getSimpleName() + ".class");
        try {
            input.transferTo(jos);
        } finally {
            input.close();
        }
        jos.closeEntry();
        jos.close();
        return baos.toByteArray();
    }
}
