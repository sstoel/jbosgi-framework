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
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A Bundle-ClassPath entry that names a corrupt (non-zip) nested jar makes FrameworkWiring.resolveBundles throw an
 * IllegalArgumentException, and the bundle can never be resolved or started.
 *
 * <h3>Severity</h3>
 * minor - needs a damaged nested jar, but one bad optional class path entry makes the whole bundle unusable and the
 * failure surfaces as an undeclared RuntimeException from the lifecycle API.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.spi.VirtualFileResourceLoader#getLocalPaths} (via
 * {@code RevisionContentResourceLoader} / {@code ModuleManagerImpl.createHostModule}).
 *
 * <h3>Found by</h3>
 * R8 verification, delivered test; committed for the R8 release check.
 * fuzz workstream, Jazzer manifest install fuzzer, first seed already: {@code Bundle-ClassPath: .,inner.jar,x/}
 * where {@code inner.jar} is a 9 byte text file. Stack:
 * {@code IllegalArgumentException: JBOSGI011207: Cannot obtain paths from: "/stream.../inner.jar"} at
 * VirtualFileResourceLoader.getLocalPaths(VirtualFileResourceLoader.java:265) &lt;- VirtualFileResourceLoader.&lt;init&gt;(:105)
 * &lt;- RevisionContentResourceLoader.&lt;init&gt;(:66) &lt;- ModuleManagerImpl.createHostModule(:270) &lt;-
 * FrameworkResolverImpl.applyResolverResults(:273) &lt;- FrameworkWiringImpl.resolveBundles(:270), caused by
 * {@code java.util.zip.ZipException: zip END header not found}.
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.resolveBundles javadoc (OSGi Core R8, 10.4.8.5): "Returns true if all specified bundles are
 * resolved; false otherwise. Throws IllegalArgumentException - If the specified Bundles were not created by the same
 * framework instance associated with this FrameworkWiring."
 * OSGi Core R8, 3.9.1 Bundle Class Path: "It defines an ordered list of container paths. Each container path can be
 * found on the entry path." - a container that cannot be used contributes nothing; the RI reports it with a
 * FrameworkEvent and continues.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/spi/VirtualFileResourceLoader.java:257-265}:
 * the IOException from mounting the nested jar is turned into {@code MESSAGES.illegalArgumentCannotObtainPaths}
 * and thrown out of the constructor; nothing on the resolve path catches it.
 *
 * <h3>Root cause</h3>
 * The resource loader for every Bundle-ClassPath entry is built eagerly after the resolver decided the wiring;
 * a container that cannot be opened raises an unchecked exception from the loader constructor that propagates
 * through FrameworkResolverImpl.applyResolverResults to the public API.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=CorruptBundleClassPathJarIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testResolveBundles: resolveBundles must not throw, but threw: java.lang.IllegalArgumentException:
 *   JBOSGI011207: Cannot obtain paths from: "/stream1791914854812/inner.jar"
 * testStart: The bundle must start, the corrupt class path entry is ignored, but:
 *   org.osgi.framework.BundleException: JBOSGI011250: Cannot resolve bundle: corrupt-cp-start:0.0.0
 * </pre>
 *
 * <h3>Expected</h3>
 * The corrupt entry is ignored (optionally with a FrameworkEvent of type ERROR/WARNING), the bundle resolves and
 * starts; at the very least resolveBundles returns false and start throws a BundleException.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 resolves and starts the bundle and publishes a FrameworkEvent.ERROR with
 * {@code java.util.zip.ZipException: Exception in opening zip file .../inner.jar}.
 *
 * <h3>Suggested fix</h3>
 * In VirtualFileResourceLoader / ModuleManagerImpl skip a class path container that cannot be opened and fire a
 * FrameworkEvent (as is done for a missing entry), instead of throwing IllegalArgumentException.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured by the tests workstream's regression classification: both methods fail the same way on the WF41
 * framework 6.6.0.Final) - the code path is unchanged by the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class CorruptBundleClassPathJarIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testResolveBundles() throws Exception {
        Bundle bundle = install("corrupt-cp-resolve");
        try {
            boolean resolved;
            try {
                resolved = getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
            } catch (RuntimeException ex) {
                fail("resolveBundles must not throw, but threw: " + ex);
                return;
            }
            assertTrue("The bundle must resolve, the corrupt class path entry is ignored", resolved);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testStart() throws Exception {
        Bundle bundle = install("corrupt-cp-start");
        try {
            bundle.start();
            assertEquals("Bundle state", Bundle.ACTIVE, bundle.getState());
        } catch (BundleException ex) {
            fail("The bundle must start, the corrupt class path entry is ignored, but: " + ex);
        } finally {
            bundle.uninstall();
        }
    }

    private Bundle install(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(Constants.BUNDLE_CLASSPATH, ".,inner.jar");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        // inner.jar is not a zip file
        jar.putNextEntry(new JarEntry("inner.jar"));
        jar.write("not a jar".getBytes("UTF-8"));
        jar.closeEntry();
        jar.close();
        return getSystemContext().installBundle("corrupt-cp:" + symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
