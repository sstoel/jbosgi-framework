package org.jboss.test.osgi.framework.issues;
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.junit.Test;
import org.osgi.framework.Bundle;

/**
 * The entries of a bundle JAR below a path that also occurs as a (zero length) file entry are invisible: when the
 * JAR has the entry {@code p/q} before {@code p/q/A.class}, getEntry, getResource and loadClass do not find
 * {@code p/q/A.class}, also not through a directory on the Bundle-ClassPath.
 *
 * <h3>Severity</h3>
 * minor. It needs a JAR in which a path is both a file and a directory, which the JDK (java.util.zip / JarFile),
 * Equinox and Felix accept; such JARs come from hand written archiving code like the R8 TCK's. The entries are
 * dropped silently, the bundle installs and fails later with ClassNotFoundException.
 *
 * <h3>Component</h3>
 * jbosgi-framework (bundle content through jbosgi-vfs30), caused by the dependency org.jboss:jboss-vfs
 * 3.3.2.Final, org.jboss.vfs.spi.JavaZipFileSystem (constructor).
 *
 * <h3>Found by</h3>
 * TCK workstream: OSGi Core R8 TCK org.osgi.test.cases.framework.launch 8.0.0,
 * {@code MultiReleaseJarTestCase.testMultiReleaseClassLoad} ({@code java.lang.ClassNotFoundException:
 * org.osgi.test.cases.framework.launch.mrjars.test.sub2.TestClassBase from [Module "jbosgi.mrBundle:1.0.0.bid2rev0"
 * from FrameworkModuleLoaderImpl]}) and {@code MultiReleaseJarTestCase.testMultiReleaseGetResources}
 * ({@code junit.framework.ComparisonFailure: Wrong resource. expected:<R[ESOURCE XXX]> but was:<R[NF]>}). The TCK
 * builds its test bundle with {@code bundleEntries.put("classPathDir/org/osgi/test/cases/framework/launch/mrjars/test/sub2", null)}
 * (no trailing slash, so a zero length file entry) followed by the classes and resources in
 * {@code classPathDir/.../sub2/}. Equinox 3.18.600 and Felix 7.0.5 pass both tests (JDK 25).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 10.1.5.17 Bundle.getEntry(String)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html): "Returns a URL to the entry at the
 * specified path in this bundle. This bundle's class loader is not used to search for the entry. Only the contents
 * of this bundle are searched for the entry."
 * and 3.9.1 Bundle Class Path (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "An entry on the Bundle-ClassPath can refer to a directory in the container. However, it is not always possible
 * to establish the directory's existence. For example, directories can be omitted in JAR/ZIP files. In such a case,
 * a Framework must probe the directory to see if any resources can be found in this directory. That is, even if the
 * directory construct is absent in the container, if resources can be found assuming this directory, than it must
 * still be chosen for the Bundle-ClassPath."
 *
 * <h3>Code location</h3>
 * jboss-vfs 3.3.2.Final, org/jboss/vfs/spi/JavaZipFileSystem.java:108-112 (building the tree of the zip entries):
 * {@code final Map<String, ZipNode> children = node.children; if (children == null) { // todo - log bad zip entry
 * continue FILES; }} - an entry whose parent path was first seen as a file node is skipped. The framework mounts
 * every bundle JAR with this file system (jbosgi-vfs30, AbstractVFS / VirtualFileAdaptor30). Same jboss-vfs version
 * on WF41.
 *
 * <h3>Root cause</h3>
 * jboss-vfs builds one node per path; the node of {@code p/q} is a file node without children, so the later entries
 * {@code p/q/...} are dropped while the zip is mounted. The framework never sees them.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ZipFileEntryHidesDirectoryIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testEntryBelowFileEntry: The bundle entry org/jboss/test/osgi/framework/classloader/support/a/A.class must be found
 *   even though the JAR also has a file entry org/jboss/test/osgi/framework/classloader/support/a
 * testClassBelowFileEntryOnBundleClassPath: The class must be loaded from the Bundle-ClassPath directory classPathDir:
 *   java.lang.ClassNotFoundException: org.jboss.test.osgi.framework.classloader.support.a.A from [Module ...]
 * </pre>
 *
 * <h3>Expected</h3>
 * All entries of the JAR are part of the bundle content; a path that is a file entry and a directory prefix is
 * treated as a directory for the entries below it (as java.util.zip, Equinox and Felix do).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.600 and Felix 7.0.5 pass MultiReleaseJarTestCase.testMultiReleaseClassLoad and
 * testMultiReleaseGetResources (JDK 25). Direct differential (JDK 25, standalone program, JAR entries
 * {@code p/q, p/q/r.txt, cpd/, cpd/s/t, cpd/s/t/u.txt}, Bundle-ClassPath {@code .,cpd}):
 * Equinox 3.18.600 and Felix 7.0.5: getEntry("p/q/r.txt") != null and getResource("s/t/u.txt") != null;
 * JBoss OSGi WF41-osgi-r8 and the WF41 build (jbosgi-framework-core 6.6.0.Final): both null. Without the
 * colliding entries {@code p/q} and {@code cpd/s/t} all three frameworks find both.
 *
 * <h3>Suggested fix</h3>
 * In jboss-vfs (or a patched copy): when a token's node exists as a file node and the entry continues below it,
 * turn the node into a directory node (keep the file entry, or drop the zero length file) instead of skipping the
 * entry. Alternatively do not depend on jboss-vfs's tree for bundle JAR content.
 *
 * <h3>Regression vs WF41</h3>
 * no (jboss-vfs 3.3.2.Final on both).
 */
public class ZipFileEntryHidesDirectoryIssueTestCase extends OSGiFrameworkTest {

    private static final String CLASS_PATH = A.class.getName().replace('.', '/') + ".class";
    private static final String PACKAGE_PATH = CLASS_PATH.substring(0, CLASS_PATH.lastIndexOf('/'));

    @Test
    public void testEntryBelowFileEntry() throws Exception {
        File file = createBundle("zip.file.entry.root", "");
        Bundle bundle = installBundle(file.toURI().toString());
        try {
            assertNotNull("The bundle entry " + CLASS_PATH + " must be found even though the JAR also has a file entry " + PACKAGE_PATH,
                    bundle.getEntry(CLASS_PATH));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testClassBelowFileEntryOnBundleClassPath() throws Exception {
        File file = createBundle("zip.file.entry.classpath", "classPathDir/");
        Bundle bundle = installBundle(file.toURI().toString());
        try {
            try {
                bundle.loadClass(A.class.getName());
            } catch (ClassNotFoundException ex) {
                fail("The class must be loaded from the Bundle-ClassPath directory classPathDir: " + ex);
            }
        } finally {
            bundle.uninstall();
        }
    }

    /**
     * A bundle with the zero length file entry [prefix]p/q followed by the entry [prefix]p/q/A.class,
     * like the test bundle of the TCK's MultiReleaseJarTestCase. With a prefix, the prefix is a Bundle-ClassPath
     * directory that has its own directory entry.
     */
    private File createBundle(String symbolicName, String prefix) throws Exception {
        File file = File.createTempFile(symbolicName, ".jar");
        file.deleteOnExit();
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Bundle-ManifestVersion", "2");
        attributes.putValue("Bundle-SymbolicName", symbolicName);
        if (prefix.length() > 0)
            attributes.putValue("Bundle-ClassPath", ".," + prefix.substring(0, prefix.length() - 1));
        JarOutputStream jar = new JarOutputStream(new FileOutputStream(file), manifest);
        try {
            if (prefix.length() > 0) {
                // The class path directory itself is present, only the colliding entry is unusual
                jar.putNextEntry(new JarEntry(prefix));
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry(prefix + PACKAGE_PATH));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(prefix + CLASS_PATH));
            jar.write(getClassBytes());
            jar.closeEntry();
        } finally {
            jar.close();
        }
        return file;
    }

    private static byte[] getClassBytes() throws Exception {
        InputStream input = A.class.getClassLoader().getResourceAsStream(CLASS_PATH);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) > 0)
                output.write(buffer, 0, length);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
