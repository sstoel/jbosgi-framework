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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.log.LogService;

/**
 * Memory leak: a provider wiring keeps the wires of every requirer wiring that was ever wired to it. When a requirer
 * revision is discarded (uninstall + refresh, update + refresh, refresh) its wires stay in the provider wiring's
 * internal provided-wire map, and each wire references the requirer revision, its bundle and its class loader with
 * all loaded classes. For the system bundle and other exporters that are never refreshed this grows without bound.
 *
 * <h3>Severity</h3>
 * critical - unbounded memory/metaspace leak in a long running framework: every redeploy (uninstall/install or
 * update) of a bundle that imports a package of the system bundle or of another long-lived bundle leaks its class
 * loader. Same root cause as wildfly/06 ({@code org.jboss.test.osgi.issues.StaleProvidedWireLeakIssueTestCase} in the
 * jbosgi testsuite, which shows the impact in a WildFly server: 81 copies of an Activator class after 80 redeploys).
 * This test reproduces it in the plain framework, without a server.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractWiring} ({@code provided} map: addProvidedWire is
 * the only writer, nothing ever removes a wire) and {@code AbstractEnvironment.uninstallResources} /
 * {@code AbstractWiringSupport.refresh} (discard the requirer wiring without touching the provider wirings);
 * reached from jbosgi-framework {@code BundleManagerPlugin.removeRevision} and the refresh/unresolve path.
 *
 * <h3>Found by</h3>
 * resolver workstream, follow-up of wildfly/06: framework-level reproduction with WeakReferences plus heap dumps of
 * the uninstall, update and Require-Bundle/DynamicImport variants ($SP/explore/resolver/leak/Leak.java, Leak2.java).
 * GC root path of the retained revisions: {@code HostBundleRevision <- .requirer AbstractBundleWire <- ArrayList <-
 * HashMap .provided <- AbstractBundleWiring (system bundle) <- AtomicReference .wiring <- AbstractBundleWiringSupport
 * <- SystemBundleRevision <- SystemBundleState}.
 *
 * <h3>Spec reference</h3>
 * BundleWiring.isInUse javadoc (OSGi Core R8, org.osgi.framework.wiring 1.2): "A bundle wiring is in use if it is
 * the current wiring or if some other in use bundle wiring is dependent upon it. Once a bundle wiring is no longer in
 * use, it is considered stale and is discarded by the framework." 7.3 Bundle Wiring
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html): "Bundle Wirings that are not in use
 * (no other Bundle Wiring is wired to it) can be removed immediately but in-use Bundle Wirings must remain in place
 * until they become no longer in use."
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractWiring.java:56} ({@code provided}),
 * {@code :99-130} ({@code addProvidedWire}, no remove method);
 * {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:145-181} ({@code uninstallResources}:
 * calls {@code getWiringSupport().refresh()} on the removed resource only);
 * {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractBundleWiring.java:150-161} (the R7 commit 37a0543 hides
 * the stale wires in getProvidedWires but keeps them). The accumulation is pre-existing (same on WF41).
 *
 * <h3>Root cause</h3>
 * Discarding a requirer wiring never removes its required wires from the provider wirings.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=StaleRequirerWiresLeakIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testUninstalledImporterIsCollectable: 5 classes of uninstalled and refreshed bundles must be garbage collectable,
 *   but 5 are still reachable
 * testSystemBundleWiresDoNotGrowOnUninstall: The system bundle wiring must not keep the wires of removed requirers:
 *   provided wires before 0, after 5 install/uninstall/refresh cycles 5 expected:&lt;0&gt; but was:&lt;5&gt;
 * testSystemBundleWiresDoNotGrowOnUpdate: The system bundle wiring must not keep the wires of discarded requirer
 *   wirings: provided wires after the first resolve 1, after 5 update/refresh cycles 11 expected:&lt;1&gt; but was:&lt;11&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * After uninstall + refresh (or update + refresh) the discarded revision, its class loader and its classes are
 * collectable, and the provider wiring only holds the wires of current/in-use requirer wirings.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 with the same uninstall, update, Require-Bundle and DynamicImport-Package loops: all classes of the
 * removed revisions are collected (0 of N alive); jbosgi: N of N alive.
 *
 * <h3>Suggested fix</h3>
 * When a requirer wiring is discarded, remove each of its required wires from the provider's wiring. The place where
 * every discarded wiring passes is {@code AbstractWiringSupport.refresh()} (called by HostBundleRevision/
 * FragmentBundleRevision.refresh on unresolve and by AbstractEnvironment.uninstallResources): take the old wiring
 * ({@code wiring.getAndSet(null)}) and for each of its required wires call a new
 * {@code AbstractWiring.removeProvidedWire(wire)} on {@code ((XWire) wire).getProviderWiring()}; afterwards the
 * getProvidedWires filter is not needed. Verified with a trial patch of jbosgi-resolver api (JDK 25): all three test
 * methods pass, the jbosgi-resolver suite stays green, the full framework itest suite has no new failure, and the
 * Require-Bundle, DynamicImport-Package and activator variants of the exploratory program leave 0 of N classes. (Doing
 * it only in uninstallResources, as first tried for wildfly/06, does not cover update + refresh: the old wiring is
 * already gone when uninstallResources runs.) After this fix an updated bundle still retains its first revision, a
 * separate root cause filed as resolver/20.
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 never removed provided wires either (see wildfly/06).
 *
 * @since 13-Oct-2026
 */
public class StaleRequirerWiresLeakIssueTestCase extends AbstractFrameworkLaunchTest {

    private static final int CYCLES = 5;

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testUninstalledImporterIsCollectable() throws Exception {
        startFramework();
        List<WeakReference<Class<?>>> classes = new ArrayList<WeakReference<Class<?>>>();
        for (int i = 0; i < CYCLES; i++) {
            Bundle bundle = install("leak-importer-" + i, 0);
            bundle.start();
            classes.add(new WeakReference<Class<?>>(bundle.loadClass(ObjectA.class.getName())));
            bundle.uninstall();
            refresh(bundle);
        }
        flushLogHistory();
        int alive = 0;
        for (int i = 0; i < 20 && (alive = countAlive(classes)) > 0; i++) {
            System.gc();
            Thread.sleep(100);
        }
        assertTrue(CYCLES + " classes of uninstalled and refreshed bundles must be garbage collectable, but " + alive + " are still reachable", alive == 0);
    }

    @Test
    public void testSystemBundleWiresDoNotGrowOnUninstall() throws Exception {
        startFramework();
        int before = countSystemProvidedWires();
        for (int i = 0; i < CYCLES; i++) {
            Bundle bundle = install("leak-importer-" + i, 0);
            bundle.start();
            bundle.uninstall();
            refresh(bundle);
        }
        int after = countSystemProvidedWires();
        assertEquals("The system bundle wiring must not keep the wires of removed requirers: provided wires before " + before + ", after " + CYCLES
                + " install/uninstall/refresh cycles " + after, before, after);
    }

    @Test
    public void testSystemBundleWiresDoNotGrowOnUpdate() throws Exception {
        startFramework();
        Bundle bundle = install("leak-updated", 0);
        bundle.start();
        int first = countSystemProvidedWires();
        for (int i = 1; i <= CYCLES; i++) {
            bundle.update(new ByteArrayInputStream(createBundle("leak-updated", i)));
            refresh(bundle);
            bundle.start();
        }
        int after = countSystemProvidedWires();
        assertEquals("The system bundle wiring must not keep the wires of discarded requirer wirings: provided wires after the first resolve " + first
                + ", after " + CYCLES + " update/refresh cycles " + after, first, after);
    }

    private void startFramework() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
    }

    private Bundle install(String name, int version) throws Exception {
        return framework.getBundleContext().installBundle(name, new ByteArrayInputStream(createBundle(name, version)));
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
        // The log history legitimately references recent bundles; push them out
        ServiceReference<LogService> sref = framework.getBundleContext().getServiceReference(LogService.class);
        if (sref != null) {
            LogService log = framework.getBundleContext().getService(sref);
            for (int i = 0; i < 300; i++)
                log.log(LogService.LOG_INFO, "filler " + i);
            framework.getBundleContext().ungetService(sref);
        }
    }

    private int countAlive(List<WeakReference<Class<?>>> refs) {
        int alive = 0;
        for (WeakReference<Class<?>> ref : refs)
            alive += ref.get() != null ? 1 : 0;
        return alive;
    }

    private int countSystemProvidedWires() throws Exception {
        // The provided wires that the system bundle wiring holds internally (getProvidedWires hides the stale ones)
        BundleWiring wiring = framework.adapt(BundleWiring.class);
        Field field = null;
        for (Class<?> clazz = wiring.getClass(); clazz != null && field == null; clazz = clazz.getSuperclass()) {
            try {
                field = clazz.getDeclaredField("provided");
            } catch (NoSuchFieldException ex) {
                // try the super class
            }
        }
        assertTrue("AbstractWiring.provided exists", field != null);
        field.setAccessible(true);
        int count = 0;
        for (Object wires : ((Map<?, ?>) field.get(wiring)).values())
            count += ((List<?>) wires).size();
        return count;
    }

    private byte[] createBundle(String name, int version) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, name);
        attributes.putValue(Constants.BUNDLE_VERSION, version + ".0.0");
        attributes.putValue(Constants.IMPORT_PACKAGE, "org.osgi.framework");
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
