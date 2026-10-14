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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A JAR on the Bundle-ClassPath is mounted over its own path in the bundle content: from then on the bundle's
 * entries show the embedded JAR as a directory. getEntry, getEntryPaths, findEntries, getResource and
 * BundleWiring.listResources return the contents of the embedded JAR under paths like
 * {@code lib/inner.jar/pb/r.txt} that do not exist in the bundle.
 *
 * <h3>Severity</h3>
 * major - bundles with embedded JARs are common (WABs with WEB-INF/lib, libraries packaged with Bundle-ClassPath).
 * Every scanner that walks the bundle entries (findEntries/getEntryPaths, e.g. web.xml/annotation/TLD/blueprint
 * scanning, resource listing) sees each embedded JAR as a directory and its classes and resources a second time
 * under wrong names; getEntryPaths reports the JAR file as a directory ("lib/inner.jar/"), so a copier or
 * extractor treats it as a folder; listResources returns names that are not class loader resource names.
 *
 * <h3>Component</h3>
 * jbosgi-vfs, vfs30, {@code org.jboss.osgi.vfs30.VirtualFileAdaptor30#ensureMounted};
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleRevision#getBundleClassPath}.
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 5): seeded class/resource loading differential {@code fa.LoadDiff}
 * (seed 1, 60 random bundle graphs) against Felix 7.0.5 and Equinox 3.21: in case 1 jbosgi's
 * {@code listResources("/", "*.txt", LISTRESOURCES_RECURSE)} of a bundle with {@code Bundle-ClassPath:
 * lib/inner.jar,.} contained {@code lib/inner.jar/pa/r.txt}; the probe {@code fa.ProbeList} then showed the same for
 * getEntry/getEntryPaths/findEntries/getResource.
 *
 * <h3>Spec reference</h3>
 * Bundle.getEntry javadoc (OSGi Core R8, 10.1.5.17, https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html):
 * "Returns a URL to the entry at the specified path in this bundle. This bundle's class loader is not used to
 * search for the entry. Only the contents of this bundle are searched for the entry." - the bundle contains the
 * file entry {@code lib/inner.jar}, not {@code lib/inner.jar/pb/r.txt}.<br>
 * Bundle.getEntryPaths javadoc (10.1.5.18): "Returns an Enumeration of all the paths (String objects) to entries
 * within this bundle whose longest sub-path matches the specified path. ... Returned paths indicating subdirectory
 * paths end with a "/"." - {@code lib/inner.jar} is a file entry, not a subdirectory.<br>
 * OSGi Core R8, 3.9.1 Bundle Class Path / 3.9.6 Resource Loading: a class path entry names a JAR file in the bundle
 * whose contents are searched by the class loader under their own names ({@code pb/r.txt}), not under the JAR's
 * path.<br>
 * BundleWiring.listResources javadoc (10.4.7.18): "Returns the names of resources visible to this bundle wiring's
 * class loader. The returned names can be used to access the resources via this bundle wiring's class loader."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleRevision.java:199-201}
 * ({@code VirtualFile child = rootFile.getChild(path); ... AbstractVFS.toVirtualFile(child.toURL())}) creates the
 * class path root for the embedded JAR on the same VFS path; jbosgi-vfs
 * {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:317-322}
 * ({@code VFS.mountZip(vfsFile, vfsFile, tmpProvider)}) mounts the ZIP over that path when its children are first
 * read. From then on the bundle root's VFS tree contains a directory {@code lib/inner.jar/}. The in-place
 * mount is pre-existing (9aa26086 / 150dbd2, 2010); on the R8 branch it already happens at install (see
 * Regression vs WF41).
 *
 * <h3>Root cause</h3>
 * The embedded JAR is mounted in place (mount point = the JAR's own path inside the mounted bundle), so the
 * bundle's entry space and the class path view of the embedded JAR are the same VFS tree.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=NestedJarExposedAsDirectoryIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testGetEntryInsideEmbeddedJar: getEntry("lib/inner.jar/pb/r.txt") must be null (no such entry in the bundle), but was
 *   bundle://nested.jar.1-1-0-0/lib/inner.jar/pb/r.txt
 * testGetEntryPathsShowsJarAsFile: getEntryPaths("lib/") expected:&lt;[lib/inner.jar]&gt; but was:&lt;[lib/inner.jar/]&gt;
 * testFindEntriesDoesNotDescendIntoEmbeddedJar: findEntries("lib", "*", true) expected:&lt;[/lib/inner.jar]&gt; but was:
 *   &lt;[/lib/inner.jar/, /lib/inner.jar/pb/, /lib/inner.jar/pb/r.txt]&gt;
 * testGetResourceUnderEmbeddedJarPath: getResource("lib/inner.jar/pb/r.txt") must be null, but was
 *   bundle://nested.jar.4-2-0-0/lib/inner.jar/pb/r.txt
 * testListResourcesNames: listResources("/", "*.txt", RECURSE) expected:&lt;[pa/r.txt, pb/r.txt]&gt; but was:
 *   &lt;[lib/inner.jar/pb/r.txt, pa/r.txt, pb/r.txt]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The bundle entries are those of the bundle JAR: getEntry/getResource of {@code lib/inner.jar/pb/r.txt} return
 * null, getEntryPaths("lib/") is [lib/inner.jar], findEntries("lib", "*", true) finds only /lib/inner.jar, and
 * listResources returns pb/r.txt (from the embedded JAR) and pa/r.txt (from ".") only.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 give exactly the expected results for the same bundle (and pb/r.txt is loadable
 * through the class loader on all three frameworks).
 *
 * <h3>Suggested fix</h3>
 * Do not mount the embedded JAR over its own path: mount a copy (or the ZIP content) at a private mount point
 * (e.g. a temp/unique VFS path, as {@code toVirtualFile(String, InputStream)} does for streams) and use that as the
 * class path root; or open embedded JARs through a ZipFile-based class path entry that does not alter the bundle
 * root's VFS tree.
 *
 * <h3>Regression vs WF41</h3>
 * yes (measured): the same test class run on an origin/WF41 jbosgi-framework worktree (5a017edc, framework
 * 6.6.0.Final, jbosgi-vfs 5.6.0.Final) and on a WF41-osgi-r8 worktree (d7b20d08), both on JDK 25:
 * testGetEntryInsideEmbeddedJar, testGetEntryPathsShowsJarAsFile, testGetResourceUnderEmbeddedJarPath and
 * testListResourcesNames pass on WF41 (also when run one at a time) and fail on R8; only
 * testFindEntriesDoesNotDescendIntoEmbeddedJar fails on both, with the same result. So on WF41 the embedded JAR shows
 * through only via findEntries; on R8 it is mounted over its own path already at install, and getEntry, getEntryPaths,
 * getResource and listResources see it as well. Likely cause (not bisected): the R7 commit 8d863429 "Implement OSGi
 * Core R7" added the multi-release support, and the RevisionContent constructor (RevisionContent.java:63) calls
 * MultiReleaseSupport.getVersionedRoots(rootFile, ...) for every Bundle-ClassPath entry (UserBundleRevision.java:202),
 * which reads the manifest of lib/inner.jar and so mounts it at install. The in-place mount itself (see Root cause) is
 * pre-existing; the R8 branch makes it visible to 4 of the 5 asserted APIs.
 *
 * @since 14-Oct-2026
 */
public class NestedJarExposedAsDirectoryIssueTestCase extends OSGiFrameworkTest {

    private static final String NESTED = "lib/inner.jar/pb/r.txt";

    @Test
    public void testGetEntryInsideEmbeddedJar() throws Exception {
        Bundle bundle = install("nested-jar-1");
        try {
            assertNotNull("The embedded JAR is an entry", bundle.getEntry("lib/inner.jar"));
            URL url = bundle.getEntry(NESTED);
            assertNull("getEntry(\"" + NESTED + "\") must be null (no such entry in the bundle), but was " + url, url);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testGetEntryPathsShowsJarAsFile() throws Exception {
        Bundle bundle = install("nested-jar-2");
        try {
            List<String> paths = Collections.list(bundle.getEntryPaths("lib/"));
            assertEquals("getEntryPaths(\"lib/\")", Collections.singletonList("lib/inner.jar"), paths);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testFindEntriesDoesNotDescendIntoEmbeddedJar() throws Exception {
        Bundle bundle = install("nested-jar-3");
        try {
            List<String> paths = new ArrayList<String>();
            Enumeration<URL> en = bundle.findEntries("lib", "*", true);
            while (en != null && en.hasMoreElements()) {
                String path = en.nextElement().getPath();
                paths.add(path.substring(path.indexOf("/lib/")));
            }
            Collections.sort(paths);
            assertEquals("findEntries(\"lib\", \"*\", true)", Collections.singletonList("/lib/inner.jar"), paths);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testGetResourceUnderEmbeddedJarPath() throws Exception {
        Bundle bundle = install("nested-jar-4");
        try {
            assertNotNull("pb/r.txt from the embedded JAR is visible to the class loader", bundle.getResource("pb/r.txt"));
            URL url = bundle.getResource(NESTED);
            assertNull("getResource(\"" + NESTED + "\") must be null, but was " + url, url);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testListResourcesNames() throws Exception {
        Bundle bundle = install("nested-jar-5");
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertFalse(frameworkWiring.resolveBundles(Collections.singleton(bundle)) == false);
            BundleWiring wiring = bundle.adapt(BundleWiring.class);
            Collection<String> names = wiring.listResources("/", "*.txt", BundleWiring.LISTRESOURCES_RECURSE);
            List<String> expected = new ArrayList<String>();
            expected.add("pa/r.txt");
            expected.add("pb/r.txt");
            assertEquals("listResources(\"/\", \"*.txt\", LISTRESOURCES_RECURSE)", expected, new ArrayList<String>(new TreeSet<String>(names)));
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
        attributes.putValue(Constants.BUNDLE_CLASSPATH, ".,lib/inner.jar");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        addEntry(jar, "lib/", null);
        addEntry(jar, "lib/inner.jar", innerJar());
        addEntry(jar, "pa/", null);
        addEntry(jar, "pa/r.txt", "outer".getBytes("UTF-8"));
        jar.close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }

    private static byte[] innerJar() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos);
        addEntry(jar, "pb/", null);
        addEntry(jar, "pb/r.txt", "inner".getBytes("UTF-8"));
        jar.close();
        return baos.toByteArray();
    }

    private static void addEntry(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        if (content != null)
            jar.write(content);
        jar.closeEntry();
    }
}
