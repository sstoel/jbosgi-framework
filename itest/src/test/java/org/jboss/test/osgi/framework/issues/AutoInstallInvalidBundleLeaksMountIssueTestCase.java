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

import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * A jar in {@code org.jboss.osgi.auto.install} that is not a valid bundle stays open (mounted in jboss-vfs) after the
 * framework rejected it: {@code BundleInfo.createBundleInfo(URL)} mounts the archive to read its manifest and throws a
 * {@code BundleException} without closing the virtual file it created; the framework only logs the exception.
 *
 * <h3>Severity</h3>
 * minor (resource leak on an error path: one open jar file and one jboss-vfs mount per rejected URL per framework
 * init, kept until JVM exit, also after the framework is stopped).
 *
 * <h3>Component</h3>
 * jbosgi-spi {@code org.jboss.osgi.spi.BundleInfo#createBundleInfo(URL)} / {@code #createBundleInfo(String)} (root
 * cause), called by jbosgi-framework core {@code BootstrapBundlesInstallPlugin}. The test lives in the framework itest
 * because the jbosgi-spi test class path has no jboss-vfs implementation (jbosgi-vfs30's jboss-vfs dependency is not
 * transitive there), so BundleInfo cannot be exercised in jbosgi-spi without a pom change.
 *
 * <h3>Found by</h3>
 * subrepos-a workstream, code review of the error paths of the spi utilities that the framework calls.
 *
 * <h3>Spec reference</h3>
 * Ownership of {@code java.io.Closeable} resources: the virtual file is created inside {@code createBundleInfo(URL)}
 * and not handed to anyone when the method throws, so only the method can close it. jbosgi-vfs
 * {@code org.jboss.osgi.vfs.VirtualFile} documents unclosed virtual files as leaks ("System property to enable detection
 * of unclosed virtual files: jboss.osgi.vfs.leakDebugging").
 *
 * <h3>Code location</h3>
 * jbosgi-spi {@code src/main/java/org/jboss/osgi/spi/BundleInfo.java:98-103} ({@code new BundleInfo(toVirtualFile(url),
 * ...)}; same at {@code :87-96}) and {@code :117-142} (the constructor throws after {@code VFSUtils.getManifest} mounted
 * the archive); framework caller {@code core/src/main/java/org/jboss/osgi/framework/internal/BootstrapBundlesInstallPlugin.java:89-96}
 * (catches the BundleException and logs JBOSGI "Cannot install initial bundle"). Pre-existing, present on WF41.
 *
 * <h3>Root cause</h3>
 * No try/catch around the constructor in the BundleInfo factory methods that create the virtual file themselves.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=AutoInstallInvalidBundleLeaksMountIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testRejectedAutoInstallJarClosed: The jar .../target/autoinstall-not-a-bundle.jar was rejected by the framework
 *   (not installed) and the framework is stopped, so it must not stay mounted (open), but it is still a mounted
 *   directory in jboss-vfs
 * </pre>
 *
 * <h3>Expected</h3>
 * When BundleInfo creation fails, the virtual file it created is closed; nothing of a rejected auto-install jar stays
 * open.
 *
 * <h3>Reference behaviour</h3>
 * n/a (jboss-osgi specific bootstrap property).
 *
 * <h3>Suggested fix</h3>
 * In {@code BundleInfo.createBundleInfo(URL)} and {@code createBundleInfo(String)}:
 * {@code VirtualFile root = toVirtualFile(url); try { return new BundleInfo(root, ...); } catch (BundleException | RuntimeException ex) { VFSUtils.safeClose(root); throw ex; }}.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class AutoInstallInvalidBundleLeaksMountIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void stopFramework() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testRejectedAutoInstallJarClosed() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "autoinstall-not-a-bundle.jar");
        archive.addAsResource(new StringAsset("not a bundle"), "readme.txt");
        File file = new File("target/autoinstall-not-a-bundle.jar").getAbsoluteFile();
        try (InputStream in = archive.as(ZipExporter.class).exportAsInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            in.transferTo(out);
        }

        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-autoinstall-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        props.put("org.jboss.osgi.auto.install", file.toURI().toString());
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        for (Bundle bundle : framework.getBundleContext().getBundles()) {
            if (bundle.getBundleId() != 0)
                throw new IllegalStateException("Precondition: the jar without manifest is not installed, but found " + bundle);
        }
        framework.stop();
        framework.waitForStop(10000);

        VirtualFile probe = AbstractVFS.toVirtualFile(file.toURI());
        try {
            // isDirectory() does not mount; a jar file is a directory in jboss-vfs only while it is mounted
            assertFalse("The jar " + file + " was rejected by the framework (not installed) and the framework is stopped, so it must not stay mounted (open), but it is still a mounted directory in jboss-vfs",
                    probe.isDirectory());
        } finally {
            VFSUtils.safeClose(probe);
        }
    }
}
