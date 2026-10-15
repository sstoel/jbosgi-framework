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
import java.io.InputStream;
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

/**
 * Updating a bundle with a lazy activation policy that is waiting for its lazy activation (state STARTING, started
 * with START_ACTIVATION_POLICY) starts the updated bundle eagerly: its activator runs and it becomes ACTIVE although no
 * class was loaded from it.
 *
 * <h3>Severity</h3>
 * minor - lazy activation is defeated by an update; the activator of a lazily started bundle runs at update time.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState#updateInternal}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful API fuzzer follow-up (service use probe of lazily started bundles), compared with
 * Equinox 3.21 and the Bundle.update javadoc.
 *
 * <h3>Spec reference</h3>
 * Bundle.update(InputStream) javadoc (OSGi Core R8, 10.1.5.38): "If this bundle's state is ACTIVE, STARTING or
 * STOPPING, this bundle is stopped ... If this bundle's state was originally ACTIVE, the updated bundle is started as
 * described in the Bundle.start method." - a bundle that was STARTING (waiting for lazy activation) is not ACTIVE, and
 * Bundle.start with the bundle's declared-activation autostart setting does not activate a lazy bundle.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:269-274}
 * ({@code restart = true} also for STARTING) and {@code :297-310} ({@code getBundleManager().startBundle(this,
 * Bundle.START_TRANSIENT)}, i.e. eager activation, ignoring the activation policy and the autostart setting).
 *
 * <h3>Root cause</h3>
 * The restart after an update always uses eager activation and is also done for a bundle that was only waiting for
 * its lazy activation.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=LazyBundleUpdateActivatesIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testUpdateKeepsLazyActivation: The updated lazy bundle must not be activated eagerly, state 32
 * </pre>
 *
 * <h3>Expected</h3>
 * After the update the bundle is not ACTIVE (Equinox: STARTING again, waiting for lazy activation).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: the updated bundle is STARTING (lazy), the activator has not run.
 *
 * <h3>Suggested fix</h3>
 * Restart only an originally ACTIVE bundle, and restart it with {@code START_TRANSIENT} plus
 * {@code START_ACTIVATION_POLICY} when its autostart setting uses the declared activation policy.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 13-Oct-2026
 */
public class LazyBundleUpdateActivatesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUpdateKeepsLazyActivation() throws Exception {
        Bundle bundle = getSystemContext().installBundle("lazy-update", new ByteArrayInputStream(lazyBundle()));
        try {
            bundle.start(Bundle.START_ACTIVATION_POLICY);
            assertEquals("Precondition: waiting for lazy activation", Bundle.STARTING, bundle.getState());
            bundle.update(new ByteArrayInputStream(lazyBundle()));
            assertTrue("The updated lazy bundle must not be activated eagerly, state " + bundle.getState(), bundle.getState() != Bundle.ACTIVE);
        } finally {
            bundle.uninstall();
        }
    }

    private static byte[] lazyBundle() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "lazy-update");
        attributes.putValue(Constants.BUNDLE_ACTIVATIONPOLICY, Constants.ACTIVATION_LAZY);
        attributes.putValue(Constants.BUNDLE_ACTIVATOR, NoopActivator.class.getName());
        attributes.putValue(Constants.IMPORT_PACKAGE, "org.osgi.framework");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        String path = NoopActivator.class.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(path));
        InputStream in = LazyBundleUpdateActivatesIssueTestCase.class.getClassLoader().getResourceAsStream(path);
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
