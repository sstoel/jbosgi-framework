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
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * A bundle with a lazy activation policy that was started eagerly (Bundle.start() without START_ACTIVATION_POLICY)
 * is only lazily started after a framework restart: it stays in STARTING with its activator not called, and its
 * persistent autostart setting is changed to "declared activation".
 *
 * <h3>Severity</h3>
 * major - after every restart the activators of such bundles do not run until some class of the bundle is loaded,
 * so their services are missing; the stored autostart setting is overwritten.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.spi.BootstrapBundlesActivate} (start of the persisted
 * bundles at launch).
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful API fuzzer ({@code Stateful}, seed 3): the persistent-state oracle reported
 * {@code ap=false} before and {@code ap=true} after a restart (BundleStartLevel.isActivationPolicyUsed); minimized
 * with a lazy bundle and compared with Equinox 3.21 and the WF41 framework.
 *
 * <h3>Spec reference</h3>
 * Bundle.start(int) javadoc (OSGi Core R8, 10.1.5.33): "If the START_TRANSIENT option is not set then set this
 * bundle's autostart setting to Started with declared activation if the START_ACTIVATION_POLICY option is set or
 * Started with eager activation if not set. When the Framework is restarted and this bundle's autostart setting is
 * not Stopped, this bundle must be automatically started." BundleStartLevel.isActivationPolicyUsed (10.5.2.2):
 * "Returns whether the bundle's autostart setting indicates that the activation policy declared in the bundle
 * manifest must be used."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/spi/BootstrapBundlesActivate.java:70-76}:
 * every persisted autostart bundle is started with {@code bundle.start(Bundle.START_ACTIVATION_POLICY)}, ignoring
 * the stored {@code ActivationPolicyUsed} property ({@code StorageState.isBundleActivationPolicyUsed()}, which
 * {@code StartLevelManagerImpl.increaseStartLevel} (:161-168) does honour) and without START_TRANSIENT, so the call
 * also rewrites the autostart setting to "declared activation". Code from 2012 (on WF41 too).
 *
 * <h3>Root cause</h3>
 * The launch path does not restore the eager/declared part of the autostart setting.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=EagerAutostartLostOnRestartIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testEagerlyStartedLazyBundleIsActiveAfterRestart: The eagerly started bundle must be ACTIVE after the restart
 *   (not waiting for lazy activation) expected:&lt;32&gt; but was:&lt;8&gt;
 * (a probe also shows isActivationPolicyUsed() false before and true after the restart, and the same for a bundle
 * without activation policy)
 * </pre>
 *
 * <h3>Expected</h3>
 * After the restart the bundle is ACTIVE (its activator ran) and isActivationPolicyUsed() is still false.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: ACTIVE and isActivationPolicyUsed() false after the restart. A bundle started with
 * START_ACTIVATION_POLICY is STARTING after the restart on both frameworks, as expected.
 *
 * <h3>Suggested fix</h3>
 * For persisted bundles start with {@code START_TRANSIENT} plus {@code START_ACTIVATION_POLICY} only if
 * {@code StorageState.isBundleActivationPolicyUsed()} is true (as StartLevelManagerImpl does).
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class EagerAutostartLostOnRestartIssueTestCase {

    @Test
    public void testEagerlyStartedLazyBundleIsActiveAfterRestart() throws Exception {
        String storage = new File("target/eager-autostart-restart").getAbsolutePath();
        Framework framework = newFramework(storage, true);
        framework.start();
        try {
            Bundle bundle = framework.getBundleContext().installBundle("eager-autostart", new ByteArrayInputStream(lazyBundle()));
            bundle.start();
            assertEquals("Precondition: eagerly started", Bundle.ACTIVE, bundle.getState());
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }

        framework = newFramework(storage, false);
        framework.start();
        try {
            Bundle bundle = framework.getBundleContext().getBundle("eager-autostart");
            assertEquals("The eagerly started bundle must be ACTIVE after the restart (not waiting for lazy activation)", Bundle.ACTIVE,
                    bundle.getState());
            assertFalse("The autostart setting must still be 'eager activation' after the restart",
                    bundle.adapt(BundleStartLevel.class).isActivationPolicyUsed());
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    private static Framework newFramework(String storage, boolean clean) {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, storage);
        if (clean)
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        return factory.newFramework(props);
    }

    private static byte[] lazyBundle() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "eager-autostart");
        attributes.putValue(Constants.BUNDLE_ACTIVATIONPOLICY, Constants.ACTIVATION_LAZY);
        attributes.putValue(Constants.BUNDLE_ACTIVATOR, NoopActivator.class.getName());
        attributes.putValue(Constants.IMPORT_PACKAGE, "org.osgi.framework");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        String path = NoopActivator.class.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(path));
        InputStream in = EagerAutostartLostOnRestartIssueTestCase.class.getClassLoader().getResourceAsStream(path);
        byte[] buffer = new byte[4096];
        for (int len = in.read(buffer); len > 0; len = in.read(buffer))
            jar.write(buffer, 0, len);
        in.close();
        jar.closeEntry();
        jar.close();
        return baos.toByteArray();
    }

    public static class NoopActivator implements BundleActivator {
        public void start(BundleContext context) {
        }

        public void stop(BundleContext context) {
        }
    }
}
