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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * Bundle.getEntry, getEntryPaths and findEntries throw StringIndexOutOfBoundsException for a path that leaves the
 * root of the bundle (for example {@code ".."} or {@code "a/../.."}) instead of returning null.
 *
 * <h3>Severity</h3>
 * minor - any caller that passes a relative path from untrusted input (e.g. a web or resource lookup) gets an
 * undeclared RuntimeException.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.RevisionContent#getBundleURL} (getEntry);
 * jbosgi-vfs, vfs30, {@code org.jboss.osgi.vfs30.VFSEntryPathsEnumeration#fixPath} (getEntryPaths, findEntries).
 *
 * <h3>Found by</h3>
 * fuzz workstream: Equinox differential of getEntry/getEntryPaths/findEntries with generated paths and patterns
 * ({@code EntryFuzz}) and a follow-up path traversal probe (paths with "..").
 *
 * <h3>Spec reference</h3>
 * Bundle.getEntry javadoc (OSGi Core R8, 10.1.5.17): "Returns A URL to the entry, or null if no entry could be found
 * ... Throws IllegalStateException - If this bundle has been uninstalled." Bundle.getEntryPaths (10.1.5.18) and
 * Bundle.findEntries (10.1.5.13) likewise return null when nothing is found and declare only IllegalStateException.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/RevisionContent.java:236}
 * ({@code child.getPathName().substring(rootPath.length())}: the child resolved for ".." is above the root, its path
 * is shorter than the root path); jbosgi-vfs {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VFSEntryPathsEnumeration.java:107}
 * (same substring for getEntryPaths/findEntries). Old code, unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The path is normalized by the VFS ({@code ".."} is resolved), and the result is not checked to still be below the
 * bundle root before the root prefix is cut off.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=EntryPathAboveRootIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testGetEntry: getEntry("..") must return null, but threw java.lang.StringIndexOutOfBoundsException: Range [20, 1) out of
 *   bounds for length 1 (at RevisionContent.getBundleURL(RevisionContent.java:236))
 * testGetEntryPaths: getEntryPaths("a/../..") must return null, but threw java.lang.StringIndexOutOfBoundsException:
 *   Range [20, 5) out of bounds for length 5 (at VFSEntryPathsEnumeration.fixPath(VFSEntryPathsEnumeration.java:107))
 * testFindEntries: findEntries("..", "*", false) must return null, but threw java.lang.StringIndexOutOfBoundsException
 * </pre>
 *
 * <h3>Expected</h3>
 * null from all three methods (there is no such entry in the bundle).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 returns null for {@code ".."}, {@code "../"}, {@code "a/../.."} and {@code "../../etc/passwd"}.
 *
 * <h3>Suggested fix</h3>
 * Return null (no entry) when the resolved VirtualFile is not the root or a descendant of the root, before computing
 * the relative path.
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class EntryPathAboveRootIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testGetEntry() throws Exception {
        Bundle bundle = install("entry-above-root-1");
        try {
            assertNull("getEntry(\"..\") must return null", bundle.getEntry(".."));
        } catch (RuntimeException ex) {
            fail("getEntry(\"..\") must return null, but threw " + ex);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testGetEntryPaths() throws Exception {
        Bundle bundle = install("entry-above-root-2");
        try {
            assertNull("getEntryPaths(\"a/../..\") must return null", bundle.getEntryPaths("a/../.."));
        } catch (RuntimeException ex) {
            fail("getEntryPaths(\"a/../..\") must return null, but threw " + ex);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testFindEntries() throws Exception {
        Bundle bundle = install("entry-above-root-3");
        try {
            assertNull("findEntries(\"..\", \"*\", false) must return null", bundle.findEntries("..", "*", false));
        } catch (RuntimeException ex) {
            fail("findEntries(\"..\", \"*\", false) must return null, but threw " + ex);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        jar.putNextEntry(new JarEntry("a/b.txt"));
        jar.write(new byte[] { 'b' });
        jar.closeEntry();
        jar.close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
