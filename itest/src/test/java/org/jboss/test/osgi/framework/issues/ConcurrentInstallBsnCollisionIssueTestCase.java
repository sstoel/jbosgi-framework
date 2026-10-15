/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.hooks.bundle.CollisionHook;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Concurrent installs of two bundles with the same symbolic name and version (different locations) are both accepted
 * under {@code org.osgi.framework.bsnversion=managed} (the default): the name/version uniqueness check and the
 * registration of the new bundle are not atomic, so each install checks against the bundles installed before either of
 * them, and the collision between the two new bundles is neither rejected nor shown to the CollisionHooks.
 *
 * <h3>Severity</h3>
 * minor. Edge case, but it breaks a framework invariant that the rest of the framework relies on (one bundle per
 * symbolic name and version unless a CollisionHook allows more): e.g. two WildFly deployments of the same bundle under
 * different names deployed at the same time (deployment scanner, parallel management operations) both get installed,
 * while deployed one after the other the second is rejected with DUPLICATE_BUNDLE_ERROR. With
 * {@code bsnversion=single} the same race applies.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.installBundleRevision} /
 * {@code checkUniqunessPolicy}.
 *
 * <h3>Found by</h3>
 * concurrency workstream (check-then-act review of the paths without a LockManager lock: INSTALL takes none,
 * {@code BundleManagerPlugin.getLockableItems} returns no items for it); made deterministic with a CollisionHook that
 * waits (CyclicBarrier) until both installing threads are past the check; Equinox/Felix differential
 * ({@code explore/concurrency/diff/Diff.java c05}).
 *
 * <h3>Spec reference</h3>
 * Constants.FRAMEWORK_BSNVERSION_MANAGED javadoc (osgi.core 8.0.0): "Specifies the framework must consult the bundle
 * collision hook services to determine if it will be an error to install a bundle or update a bundle to have the same
 * symbolic name and version as another installed bundle. If no bundle collision hook services are registered, then it
 * will be an error to install a bundle or update a bundle to have the same symbolic name and version as another
 * installed bundle."; OSGi Core R8, 54.7.2.3 CollisionHook.filterCollisions: "The collision candidate collection
 * contains the existing bundles installed which have the same symbolic name and version as the bundle being
 * installed. ... For the specified operation to succeed, the collection of collision candidates must be empty after
 * all registered collision hook services have been called."; BundleContext (10.1.7): "Concurrency Thread-safe".
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:514-515}
 * ({@code checkUniqunessPolicy(context.getBundle(), symbolicName, version, CollisionHook.INSTALLING)} - computes the
 * candidates from {@code getBundles(symbolicName, range)} and calls the hooks) and {@code :546-550} (the bundle
 * revision, and with it the bundle, becomes visible only later, in HostBundleRevisionFactory.create); no lock or
 * re-check around both ({@code getLockableItems}, {@code :708-713}: INSTALL locks nothing). Pre-existing (same code on
 * WF41).
 *
 * <h3>Root cause</h3>
 * Check-then-act race: the uniqueness check is not atomic with the installation (no per-name lock, no re-check once
 * the bundle is registered).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConcurrentInstallBsnCollisionIssueTestCase
 * </pre>
 * Bundle Z (concurrency04.same 1.0) is installed first; a CollisionHook allows a collision with Z (removes Z from the
 * candidates, as a region/isolation hook does) and waits (at most 5 s) until both installing threads are in the hook.
 * Two threads then install two more bundles concurrency04.same 1.0 from two jar files (plain file locations; stream
 * installs would hit connect/11).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: two concurrent installs of concurrency04.same 1.0 collide with each other; the hook only
 * allowed the collision with the bundle installed before, so exactly one of them must succeed (the other must fail
 * with DUPLICATE_BUNDLE_ERROR or the hook must see the first one as candidate), but the results are
 * [installed, installed] and the hooks saw candidates [[concurrency04.same:1.0.0], [concurrency04.same:1.0.0]]
 * expected:&lt;2&gt; but was:&lt;3&gt;
 * </pre>
 * 3 of 3 runs failed (JDK 25); the interleaving is forced by the hook.
 *
 * <h3>Expected</h3>
 * Two bundles concurrency04.same 1.0 (Z and one of the new ones); the other install fails with
 * BundleException.DUPLICATE_BUNDLE_ERROR (or is offered to the hooks with the first new bundle as candidate).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 (Diff c05, JDK 25): ends with 2 bundles - it holds a per symbolic name lock across the collision check
 * and the install (the second install fails because the hook holds the first one up). Felix 7.0.5 has the same race as
 * jbosgi (3 bundles).
 *
 * <h3>Suggested fix</h3>
 * Serialise installs (and updates) per symbolic name: take a per-name lock (or the FrameworkWiringLock / an install
 * lock) around checkUniqunessPolicy and the creation of the bundle, or re-check the uniqueness after the new bundle is
 * registered and uninstall it on a collision. Verified with a trial patch (installBundleRevision serialised): the test
 * passes (the second install fails with DUPLICATE_BUNDLE_ERROR after the hook's 5 s wait).
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 *
 * <h3>Related</h3>
 * connect/09 ConcurrentInstallSameLocationIssueTestCase (same missing install serialisation for one location; its
 * per-location fix does not cover different locations).
 */
public class ConcurrentInstallBsnCollisionIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testConcurrentInstallsOfSameSymbolicNameAndVersion() throws Exception {
        File dir = new File("target/concurrency04-bsn-collision").getAbsoluteFile();
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File(dir, "storage").getPath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        props.put(Constants.FRAMEWORK_BSNVERSION, Constants.FRAMEWORK_BSNVERSION_MANAGED);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        final BundleContext context = framework.getBundleContext();

        final Bundle z = context.installBundle(bundleFile(dir, "z").getPath());

        // Allow the collision with Z, and let both installs pass the check before either continues
        final CyclicBarrier bothInHook = new CyclicBarrier(2);
        final List<String> hookCandidates = Collections.synchronizedList(new ArrayList<String>());
        context.registerService(CollisionHook.class, new CollisionHook() {
            public void filterCollisions(int operationType, Bundle target, Collection<Bundle> collisionCandidates) {
                if (operationType != CollisionHook.INSTALLING)
                    return;
                hookCandidates.add(collisionCandidates.toString());
                collisionCandidates.remove(z);
                try {
                    bothInHook.await(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    // the other install did not come (it was serialised or rejected): continue alone
                }
            }
        }, null);

        final File[] files = { bundleFile(dir, "a"), bundleFile(dir, "b") };
        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final File file = files[i];
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        context.installBundle(file.getPath());
                        results.add("installed");
                    } catch (BundleException ex) {
                        results.add(ex.getType() == BundleException.DUPLICATE_BUNDLE_ERROR ? "DUPLICATE_BUNDLE_ERROR" : ex.toString());
                    } catch (Throwable th) {
                        results.add(th.toString());
                    }
                }
            }, "concurrency04-install-" + i);
            threads[i].start();
        }
        for (Thread thread : threads)
            thread.join(20000);

        int count = 0;
        for (Bundle bundle : context.getBundles()) {
            if ("concurrency04.same".equals(bundle.getSymbolicName()))
                count++;
        }
        assertEquals("two concurrent installs of concurrency04.same 1.0 collide with each other; the hook only allowed the collision with the bundle "
                + "installed before, so exactly one of them must succeed (the other must fail with DUPLICATE_BUNDLE_ERROR or the hook must see the first "
                + "one as candidate), but the results are " + results + " and the hooks saw candidates " + hookCandidates, 2, count);
    }

    private static File bundleFile(File dir, String name) throws Exception {
        dir.mkdirs();
        File file = new File(dir, "concurrency04-" + name + ".jar");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attrs.putValue(Constants.BUNDLE_SYMBOLICNAME, "concurrency04.same");
        attrs.putValue(Constants.BUNDLE_VERSION, "1.0");
        new JarOutputStream(new FileOutputStream(file), manifest).close();
        return file;
    }
}
