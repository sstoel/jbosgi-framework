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
 * A lazy bundle that was started with {@code START_TRANSIENT | START_ACTIVATION_POLICY} is activated eagerly after an
 * update, its activator runs without a class load.
 *
 * <h2>Severity</h2>
 * minor. As fuzz/17, for a transient start.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code UserBundleState.updateInternal}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of fuzz/17 (fw-minor-B03).
 *
 * <h2>Spec reference</h2>
 * Bundle.update: "If this bundle's state was originally ACTIVE or STARTING, the updated bundle is started as described
 * in the Bundle.start method"; a bundle started with its activation policy waits for its activation (4.4.6.2).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:285-287 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * The restart took the activation policy option from the persistent autostart setting, which a transient start does not
 * write.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=LazyTransientStartUpdateIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The updated bundle is STARTING again, waiting for its activation.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class LazyTransientStartUpdateIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUpdateKeepsTransientLazyActivation() throws Exception {
        Bundle bundle = getSystemContext().installBundle("lazy-transient-update", new ByteArrayInputStream(lazyBundle()));
        try {
            bundle.start(Bundle.START_TRANSIENT | Bundle.START_ACTIVATION_POLICY);
            assertEquals("Precondition: waiting for lazy activation", Bundle.STARTING, bundle.getState());
            bundle.update(new ByteArrayInputStream(lazyBundle()));
            assertEquals("The updated bundle waits for its lazy activation again", Bundle.STARTING, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testUpdateOfTransientEagerStart() throws Exception {
        Bundle bundle = getSystemContext().installBundle("lazy-transient-eager-update", new ByteArrayInputStream(lazyBundle()));
        try {
            bundle.start(Bundle.START_TRANSIENT);
            assertEquals("Precondition: activated eagerly", Bundle.ACTIVE, bundle.getState());
            bundle.update(new ByteArrayInputStream(lazyBundle()));
            assertEquals("The updated bundle is activated eagerly again", Bundle.ACTIVE, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    private static byte[] lazyBundle() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "lazy-transient-update");
        attributes.putValue(Constants.BUNDLE_ACTIVATIONPOLICY, Constants.ACTIVATION_LAZY);
        attributes.putValue(Constants.BUNDLE_ACTIVATOR, NoopActivator.class.getName());
        attributes.putValue(Constants.IMPORT_PACKAGE, "org.osgi.framework");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        String path = NoopActivator.class.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(path));
        InputStream in = LazyTransientStartUpdateIssueTestCase.class.getClassLoader().getResourceAsStream(path);
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
