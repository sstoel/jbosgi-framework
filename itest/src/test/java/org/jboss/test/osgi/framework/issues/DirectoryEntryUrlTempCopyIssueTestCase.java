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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Every {@code openConnection()} of a bundle entry URL that names a directory (e.g. {@code getEntry("dir/")},
 * {@code getResource("META-INF/")}, {@code getEntry("/")}) writes a temporary jar copy of that whole directory subtree
 * which the framework never deletes; only the finalizer of jboss-vfs {@code TempDir} removes it. With finalization
 * disabled ({@code --finalization=disabled}, JEP 421, the mode that the JDK recommends to test for its announced
 * removal) the copies stay until JVM exit, also after the bundle was uninstalled, refreshed and the framework stopped.
 *
 * <h3>Severity</h3>
 * minor (disk-space leak that depends on a deprecated-for-removal JDK mechanism). On a default JDK 25 the copies
 * disappear only after a GC has found the unreachable adaptor and the finalizer thread has run, so they accumulate
 * between collections; with finalization disabled (or removed in a future JDK) they accumulate until the server is
 * restarted. Each copy holds the entire subtree ({@code getEntry("/")} copies the whole bundle). Library code opens
 * directory URLs routinely, e.g. Spring's PathMatchingResourcePatternResolver calls {@code openConnection()} on the
 * directory URL it scans to check for a {@code JarURLConnection}.
 *
 * <h3>Component</h3>
 * jbosgi-framework core, {@code org.jboss.osgi.framework.internal.RevisionContent#getBundleURL} (the bundle URL stream
 * handler), together with jbosgi-vfs vfs30 {@code VirtualFileAdaptor30#getStreamURL} (creates the copy in a
 * {@code TempDir} that is only deleted by {@code close()} or by jboss-vfs' {@code TempDir.finalize()}).
 *
 * <h3>Found by</h3>
 * subrepos-a workstream: framework-level resource count across 30 install/start/open-directory-URL/uninstall/refresh
 * cycles of plain, nested-jar and multi-release bundles: +0 temp files with default finalization, +2 temp files per
 * cycle with {@code --finalization=disabled}.
 *
 * <h3>Spec reference</h3>
 * JEP 421 "Deprecate Finalization for Removal" (JDK 18, still deprecated for removal in JDK 25): "Finalization remains
 * enabled by default for now, but can be disabled to facilitate early testing." ... "Maintainers of libraries and
 * applications that rely upon finalization should consider migrating to other resource management techniques such as
 * the try-with-resources statement and cleaners." (option {@code --finalization=disabled}). jbosgi-vfs api {@code VirtualFile.getStreamURL()} javadoc: "@return The
 * stream URL or null if this is not the mounted root file." (the framework calls it for arbitrary entries, and the vfs30
 * adaptor creates a temporary copy for them). https://openjdk.org/jeps/421
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/RevisionContent.java:219-229}:
 * {@code VirtualFile real = (orgPath.equals(path) ? child : virtualFile.getChild(path)); ... return
 * real.getStreamURL().openConnection();} ({@code orgPath} is the full VFS path and never equals the URL path, so every
 * call creates a new adaptor, which is never closed); jbosgi-vfs
 * {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:167-190} ({@code getStreamURL}: for a
 * directory, {@code tmpProvider.createTempDir("urlstream")} and a jar copy of the subtree, removed only in
 * {@code close()}); jboss-vfs 3.3.2 {@code TempDir.finalize()}. Pre-existing, present on WF41.
 *
 * <h3>Root cause</h3>
 * The temporary copy is owned by a short-lived VirtualFile adaptor that nobody closes; its cleanup is delegated to
 * finalization.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=DirectoryEntryUrlTempCopyIssueTestCase
 * </pre>
 * The test runs the scenario in a child JVM (same class path) started with {@code --finalization=disabled}.
 *
 * <h3>Observed</h3>
 * <pre>
 * testDirectoryUrlCopiesRemoved: After uninstall, refresh and framework stop no temporary copy of the 5 opened directory
 *   URLs may remain (child JVM with --finalization=disabled), but 5 urlstream-* directories remain under
 *   .../target/dir-url-issue-tmp/vfs expected:&lt;0&gt; but was:&lt;5&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * 0: temporary copies made for a URL connection are deleted when its stream is closed, or at the latest when the bundle
 * revision is closed.
 *
 * <h3>Reference behaviour</h3>
 * Not checked against Equinox/Felix (they open directory entries as zip entries without copying).
 *
 * <h3>Suggested fix</h3>
 * In the bundle URL handler, do not materialize directories: return a connection with an empty stream for directory
 * entries (or the stream of a {@code VirtualJarInputStream} directly), or close the adaptor/TempDir when the returned
 * stream is closed and in {@code RevisionContent.close()}. In vfs30, replace reliance on finalization with
 * {@code java.lang.ref.Cleaner} if a copy must outlive the call.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing; the JDK side changed: finalization is deprecated for removal since JDK 18).
 */
public class DirectoryEntryUrlTempCopyIssueTestCase {

    private static final int OPENS = 5;

    @Test
    public void testDirectoryUrlCopiesRemoved() throws Exception {
        File tmpdir = new File("target/dir-url-issue-tmp").getAbsoluteFile();
        deleteRecursively(tmpdir);
        tmpdir.mkdirs();

        List<String> command = new ArrayList<String>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("--finalization=disabled");
        command.add("-Djava.io.tmpdir=" + tmpdir);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Child.class.getName());
        command.add(new File(tmpdir, "store").getPath());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), "UTF-8");
        if (!process.waitFor(120, TimeUnit.SECONDS))
            process.destroyForcibly();

        int index = output.indexOf("URLSTREAM_DIRS=");
        if (index < 0)
            throw new IllegalStateException("Child JVM did not report a result:\n" + output);
        int remaining = Integer.parseInt(output.substring(index + 15).trim().split("\\s")[0]);
        assertEquals("After uninstall, refresh and framework stop no temporary copy of the " + OPENS
                + " opened directory URLs may remain (child JVM with --finalization=disabled), but " + remaining + " urlstream-* directories remain under "
                + new File(tmpdir, "vfs"), 0, remaining);
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Map<String, String> props = new HashMap<String, String>();
            props.put(Constants.FRAMEWORK_STORAGE, args[0]);
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
            Framework framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
            framework.start();
            Bundle bundle = framework.getBundleContext().installBundle("dir-url-bundle", new ByteArrayInputStream(createBundle()));
            bundle.start();
            for (int i = 0; i < OPENS; i++) {
                URL url = bundle.getEntry("dir/");
                try (InputStream in = url.openConnection().getInputStream()) {
                    in.readAllBytes();
                }
            }
            bundle.uninstall();
            FrameworkWiring wiring = framework.adapt(FrameworkWiring.class);
            Collection<Bundle> pending = wiring.getRemovalPendingBundles();
            final CountDownLatch latch = new CountDownLatch(1);
            wiring.refreshBundles(pending, new FrameworkListener() {
                @Override
                public void frameworkEvent(FrameworkEvent event) {
                    latch.countDown();
                }
            });
            latch.await(30, TimeUnit.SECONDS);
            framework.stop();
            framework.waitForStop(10000);
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(200);
            }
            System.out.println("URLSTREAM_DIRS=" + countStreamDirs(new File(System.getProperty("java.io.tmpdir"), "vfs")));
            System.out.flush();
            Runtime.getRuntime().halt(0); // skip the jboss-vfs shutdown hook that deletes the whole temp area
        }

        private static int countStreamDirs(File dir) {
            File[] files = dir.listFiles();
            if (files == null)
                return 0;
            int count = 0;
            for (File file : files) {
                if (file.isDirectory()) {
                    if (file.getName().startsWith("urlstream-"))
                        count++;
                    count += countStreamDirs(file);
                }
            }
            return count;
        }

        private static byte[] createBundle() throws Exception {
            final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "dir-url-bundle");
            archive.addAsResource(new StringAsset("a"), "dir/a.txt");
            archive.addAsResource(new StringAsset("b"), "dir/sub/b.txt");
            archive.setManifest(new Asset() {
                @Override
                public InputStream openStream() {
                    OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                    builder.addBundleManifestVersion(2);
                    builder.addBundleSymbolicName(archive.getName());
                    return builder.openStream();
                }
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream input = archive.as(ZipExporter.class).exportAsInputStream()) {
                input.transferTo(out);
            }
            return out.toByteArray();
        }
    }

    private static void deleteRecursively(File file) throws Exception {
        if (!file.exists())
            return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children)
                deleteRecursively(child);
        }
        Files.delete(file.toPath());
    }
}
