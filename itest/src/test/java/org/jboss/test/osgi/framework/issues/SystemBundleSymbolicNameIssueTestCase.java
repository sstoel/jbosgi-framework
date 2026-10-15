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
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A user bundle with {@code Bundle-SymbolicName: system.bundle} is installed, but the framework then mistakes it for
 * the system bundle: FrameworkWiring.resolveBundles throws IllegalStateException ("Cannot load module:
 * jbosgi.framework") and the bundle can never be resolved or started.
 *
 * <h3>Severity</h3>
 * minor - unusual symbolic name, but any bundle can make resolveBundles throw for every caller that includes it.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code ModuleManagerImpl#getModuleIdentifier} / {@code #addModule} and
 * {@code FrameworkModuleLoaderImpl#createModuleService}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, Jazzer manifest install fuzzer (deep mode): crash input with
 * {@code Bundle-SymbolicName: system.bundle}; minimized to that single header.
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.resolveBundles javadoc (OSGi Core R8, 10.4.8.5): "Returns true if all specified bundles are
 * resolved; false otherwise. Throws IllegalArgumentException - If the specified Bundles were not created by the same
 * framework instance". Constants.SYSTEM_BUNDLE_SYMBOLICNAME (10.1.15): "Identifies the implementation-independent
 * symbolic name of the system bundle" - the framework identifies the system bundle by its bundle id 0 / its
 * revision, not by the name of a user bundle.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:131} and
 * {@code :211}: {@code if (SYSTEM_BUNDLE_SYMBOLICNAME.equals(icap.getName()))} returns the framework module
 * identifier for any revision named system.bundle, so no module is created for the user bundle and
 * {@code FrameworkModuleLoaderImpl.createModuleService} (:214-225) fails with
 * {@code ModuleNotFoundException: jbosgi.framework} wrapped in {@code IllegalStateException(JBOSGI011217)}.
 *
 * <h3>Root cause</h3>
 * The system bundle is recognized by its symbolic name instead of by identity (bundle id 0 / SystemBundleRevision).
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SystemBundleSymbolicNameIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testResolveBundles: resolveBundles must not throw for a bundle named system.bundle, but threw:
 *   java.lang.IllegalStateException: JBOSGI011217: Cannot load module: jbosgi.framework
 * testStart: The installed bundle named system.bundle must start, but: BundleException: JBOSGI011250: Cannot resolve bundle:
 *   system.bundle:1.0.24 caused by java.lang.IllegalStateException: JBOSGI011217: Cannot load module: jbosgi.framework
 * </pre>
 *
 * <h3>Expected</h3>
 * Either the install is rejected with a BundleException, or the bundle resolves and starts like any other bundle;
 * resolveBundles never throws IllegalStateException.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 installs, resolves and starts a bundle named system.bundle.
 *
 * <h3>Suggested fix</h3>
 * In ModuleManagerImpl test {@code brev instanceof SystemBundleRevision} (or bundle id 0) instead of comparing the
 * symbolic name; alternatively reject the name at install.
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class SystemBundleSymbolicNameIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testResolveBundles() throws Exception {
        Bundle bundle;
        try {
            bundle = install("system-bundle-name-resolve");
        } catch (BundleException ex) {
            // Rejected at install, acceptable
            return;
        }
        try {
            getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
        } catch (RuntimeException ex) {
            fail("resolveBundles must not throw for a bundle named system.bundle, but threw: " + ex);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testStart() throws Exception {
        Bundle bundle;
        try {
            bundle = install("system-bundle-name-start");
        } catch (BundleException ex) {
            return;
        }
        try {
            bundle.start();
            assertEquals("Bundle state", Bundle.ACTIVE, bundle.getState());
        } catch (BundleException ex) {
            fail("The installed bundle named system.bundle must start, but: " + ex + " caused by " + ex.getCause());
        } finally {
            bundle.uninstall();
        }
    }

    private Bundle install(String location) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, Constants.SYSTEM_BUNDLE_SYMBOLICNAME);
        attributes.putValue(Constants.BUNDLE_VERSION, "1.0." + location.length());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return getSystemContext().installBundle(location, new ByteArrayInputStream(baos.toByteArray()));
    }
}
