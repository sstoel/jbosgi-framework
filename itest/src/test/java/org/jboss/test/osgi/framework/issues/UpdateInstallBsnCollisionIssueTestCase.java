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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
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
 * An update of a bundle to a symbolic name and version, and the install of another bundle with that name and version
 * at the same time, both pass the uniqueness check: two bundles have the same name and version.
 *
 * <h2>Severity</h2>
 * minor. With {@code org.osgi.framework.bsnversion=managed} or {@code single} two bundles have the same symbolic name
 * and version.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code UserBundleState.createUpdateRevision}, {@code BundleManagerPlugin.installBundleRevision}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of concurrency/04 (fw-minor).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.4.1 and 10.2.3.5 (bsnversion): a bundle with the symbolic name and version of an installed bundle is
 * not installed or updated (DUPLICATE_BUNDLE_ERROR), unless a CollisionHook allows it.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:612-628 (dc5d2881): an update takes no
 * lock of the symbolic name.
 *
 * <h2>Root cause</h2>
 * Only installs were serialized per symbolic name, the check of an update ran at the same time as that of an install.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=UpdateInstallBsnCollisionIssueTestCase
 * </pre>
 * A CollisionHook lets the update and the install wait for each other in their check.
 *
 * <h2>Expected</h2>
 * One of the two succeeds, the other fails with DUPLICATE_BUNDLE_ERROR.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class UpdateInstallBsnCollisionIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testUpdateAndInstallOfSameSymbolicNameAndVersion() throws Exception {
        File dir = new File("target/update-install-bsn-collision").getAbsoluteFile();
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File(dir, "storage").getPath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        props.put(Constants.FRAMEWORK_BSNVERSION, Constants.FRAMEWORK_BSNVERSION_MANAGED);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        final BundleContext context = framework.getBundleContext();

        final Bundle z = context.installBundle(bundleFile(dir, "z", "update.install.same").getPath());
        final Bundle other = context.installBundle(bundleFile(dir, "other", "update.install.other").getPath());
        final File updateFile = bundleFile(dir, "update", "update.install.same");
        final File installFile = bundleFile(dir, "install", "update.install.same");

        // The collision with Z is allowed. The update and the install wait for each other in their check, if they get
        // there at the same time
        final CyclicBarrier bothInHook = new CyclicBarrier(2);
        context.registerService(CollisionHook.class, new CollisionHook() {
            public void filterCollisions(int operationType, Bundle target, Collection<Bundle> collisionCandidates) {
                collisionCandidates.remove(z);
                try {
                    bothInHook.await(3, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    // the other one was serialized: continue alone
                }
            }
        }, null);

        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        Thread update = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream input = new FileInputStream(updateFile);
                    other.update(input);
                    results.add("updated");
                } catch (BundleException ex) {
                    results.add(ex.getType() == BundleException.DUPLICATE_BUNDLE_ERROR ? "update DUPLICATE_BUNDLE_ERROR" : ex.toString());
                } catch (Throwable th) {
                    results.add(th.toString());
                }
            }
        }, "update-install-update");
        Thread install = new Thread(new Runnable() {
            public void run() {
                try {
                    context.installBundle(installFile.getPath());
                    results.add("installed");
                } catch (BundleException ex) {
                    results.add(ex.getType() == BundleException.DUPLICATE_BUNDLE_ERROR ? "install DUPLICATE_BUNDLE_ERROR" : ex.toString());
                } catch (Throwable th) {
                    results.add(th.toString());
                }
            }
        }, "update-install-install");
        update.start();
        install.start();
        update.join(30000);
        install.join(30000);

        int count = 0;
        for (Bundle bundle : context.getBundles()) {
            if ("update.install.same".equals(bundle.getSymbolicName()))
                count++;
        }
        assertEquals("Z and one other bundle have the symbolic name and version, the results are " + results, 2, count);
    }

    private static File bundleFile(File dir, String name, String symbolicName) throws Exception {
        dir.mkdirs();
        File file = new File(dir, "update-install-" + name + ".jar");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attrs.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attrs.putValue(Constants.BUNDLE_VERSION, "1.0");
        new JarOutputStream(new FileOutputStream(file), manifest).close();
        return file;
    }
}
