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

import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;

/**
 * A URL derived from a bundle entry URL ({@code new URL(bundle.getEntry(..), "../../../x")}) is resolved against the
 * host file system instead of the bundle, so code that holds a bundle entry URL can read any file of the host.
 *
 * <h3>Severity</h3>
 * major (security-relevant containment violation). On Java 25 there is no Security Manager, so bundle code can read host
 * files directly anyway; the risk is in code that resolves relative references against a bundle URL from less trusted
 * input, e.g. XML parsed with its bundle entry URL as system id (relative entity/XInclude/xsd references climb out of
 * the bundle), template engines or resource loaders that join a request path to a bundle base URL. Checked and
 * contained in the server: the Http Service resource path (raw requests with ../, %2e%2e, ..%2f, ..;/, double encoding,
 * backslashes against registerResources with the default and a custom HttpContext all return 400/404/500, never the
 * file). Direct Bundle.getEntry/getResource/findEntries calls with ".." do not return outside content either (they
 * throw StringIndexOutOfBoundsException, fuzz/24).
 *
 * <h3>Containment table (coordinator probe, JDK 25)</h3>
 * <pre>
 * base URL                                   ../../x   %2e%2e/x  ..%2fx   ..\x
 * Bundle.getEntry (stream, file location)     LEAKS     -         -        -
 * Bundle.getResource / ClassLoader.getResource
 *   on main bundle content                   LEAKS     contained contained contained
 * nested Bundle-ClassPath jar resource        contained contained contained contained
 * Bundle-ClassPath directory resource         contained contained contained contained
 * Bundle.getResource("..")                   returns null (contained)
 * Http Service registerResources (server)     contained (400/404/500)
 * </pre>
 * Exploded-directory locations are not installable (JBOSGI011230), so they are not affected.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.RevisionContent#getBundleURL} (the URLStreamHandler
 * of bundle entry URLs).
 *
 * <h3>Found by</h3>
 * fuzz workstream observation (getEntry("../..") resolved above the bundle root before crashing, fuzz/24) followed up by
 * the coordinator with a sentinel-file containment probe for stream-installed and file-location bundles.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 10.1.5.13 Bundle.getEntry(String): "Returns a URL to the entry at the specified path in this bundle. ...
 * Only the contents of this bundle are searched for the entry." 3.9.6 / 10.1.5.15 Bundle.getResource and 4.4.7 Bundle
 * Entries: entry URLs give access to the content of the bundle; a URL scheme defined by the framework ("bundleentry"
 * in the RI) whose relative resolution escapes the bundle gives access to content that is not part of any bundle.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.getEntry-String-
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/RevisionContent.java:219-229}: the handler's
 * {@code openConnection(URL url)} does {@code virtualFile.getChild(url.getPath())} and opens the result without checking
 * that it is below the bundle's root; jboss-vfs resolves ".." segments above a mount into the real file system.
 * Unchanged since 2011 (bfe0aeab), present on WF41 (pre-existing).
 *
 * <h3>Root cause</h3>
 * java.net.URL keeps unresolvable leading "/.." segments when a relative spec is resolved against a bundle entry URL;
 * the handler passes the path to VirtualFile.getChild, which normalizes it against the VFS root rather than the bundle.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=BundleEntryUrlPathTraversalIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * stream-installed bundle: new URL(getEntry("META-INF/MANIFEST.MF"), "../../(30x)/private/var/.../sentinel.txt")
 *   returns the content of the sentinel file outside the bundle
 * file-location bundle: the same
 * </pre>
 *
 * <h3>Expected</h3>
 * Opening a bundle URL whose path resolves outside the bundle fails (FileNotFoundException) or yields no content.
 *
 * <h3>Reference behaviour</h3>
 * Not checked against Equinox/Felix by the coordinator.
 *
 * <h3>Suggested fix</h3>
 * In the handler, normalize {@code url.getPath()} and reject it (FileNotFoundException) if it contains ".." segments
 * that leave the root, or resolve the child and verify it is below {@code virtualFile} (path prefix check on the
 * normalized VirtualFile path) before opening it. Apply the same check in every bundle URL handler (entry, resource,
 * nested Bundle-ClassPath jars).
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class BundleEntryUrlPathTraversalIssueTestCase extends OSGiFrameworkTest {

    private static final String SECRET = "TRAVERSAL-SENTINEL-4711";

    @Test
    public void testStreamInstalledBundleUrlDoesNotEscape() throws Exception {
        File sentinel = createSentinel();
        Bundle bundle = installBundle(createArchive("traversal-stream"));
        try {
            URL derived = new URL(bundle.getEntry("META-INF/MANIFEST.MF"), upTo(sentinel));
            assertFalse("A URL derived from an entry URL of a stream-installed bundle must not read a file outside the bundle, but "
                    + derived + " returned the content of " + sentinel, readsSecret(derived));
        } finally {
            bundle.uninstall();
            sentinel.delete();
        }
    }

    @Test
    public void testFileLocationBundleUrlDoesNotEscape() throws Exception {
        File sentinel = createSentinel();
        File jar = new File(sentinel.getParentFile(), "traversal-file-" + System.nanoTime() + ".jar");
        createArchive("traversal-file").as(ZipExporter.class).exportTo(jar, true);
        Bundle bundle = getSystemContext().installBundle(jar.toURI().toURL().toExternalForm());
        try {
            URL derived = new URL(bundle.getEntry("META-INF/MANIFEST.MF"), upTo(sentinel));
            assertFalse("A URL derived from an entry URL of a file-location bundle must not read a file outside the bundle, but "
                    + derived + " returned the content of " + sentinel, readsSecret(derived));
        } finally {
            bundle.uninstall();
            sentinel.delete();
            jar.delete();
        }
    }

    @Test
    public void testResourceUrlDoesNotEscape() throws Exception {
        File sentinel = createSentinel();
        JavaArchive archive = createArchive("traversal-resource");
        Bundle bundle = installBundle(archive);
        try {
            URL resource = bundle.getResource("data/inside.txt");
            URL derived = new URL(resource, upTo(sentinel));
            assertFalse("A URL derived from a resource URL (Bundle.getResource) must not read a file outside the bundle, but "
                    + derived + " returned the content of " + sentinel, readsSecret(derived));
        } finally {
            bundle.uninstall();
            sentinel.delete();
        }
    }

    private static File createSentinel() throws Exception {
        File sentinel = File.createTempFile("sentinel", ".txt");
        Files.write(sentinel.toPath(), SECRET.getBytes(StandardCharsets.UTF_8));
        return sentinel;
    }

    private static String upTo(File file) throws Exception {
        StringBuilder up = new StringBuilder();
        for (int i = 0; i < 30; i++)
            up.append("../");
        return up + file.getCanonicalPath().substring(1);
    }

    private static boolean readsSecret(URL url) {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).contains(SECRET);
        } catch (Exception ex) {
            return false;
        }
    }

    private JavaArchive createArchive(String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new StringAsset("inside"), "data/inside.txt");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        return archive;
    }
}
