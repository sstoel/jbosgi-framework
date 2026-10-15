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

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;

/**
 * Bundle.update() without an input stream does not read the bundle's location again: it re-installs the content that
 * was read at install time, so an updated jar at the bundle's location is never picked up.
 *
 * <h3>Severity</h3>
 * minor. The usual "update from where it was installed" operation (management consoles, shells, file installers
 * calling {@code bundle.update()}) silently keeps the old code; only an explicit stream or a Bundle-UpdateLocation
 * header works.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.UserBundleState#createUpdateRevision
 *
 * <h3>Found by</h3>
 * services workstream round 2 (framework.api statements #71/#73), Equinox differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, Bundle.update(InputStream)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.update-InputStream-):
 * "If the specified InputStream is null, the Framework must create the InputStream from which to read the updated
 * bundle by interpreting, in an implementation dependent manner, this bundle's Bundle-UpdateLocation Manifest header,
 * if present, or this bundle's original location." Bundle.update(): "This method performs the same function as
 * calling update(InputStream) with a null InputStream."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:327-351
 * ({@code if (updateLocation != null) {...} else { rootFile = getDeployment().getRoot(); }}); the install path
 * (AbstractBundleContext.java:201-202) copies the content from {@code new URL(location).openStream()}, so the
 * deployment root is that copy, not the location. Same code on WF41.
 *
 * <h3>Root cause</h3>
 * Without Bundle-UpdateLocation the update re-uses the current deployment's root instead of opening the original
 * location.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=UpdateWithoutStreamIgnoresLocationIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testUpdateReadsOriginalLocation: update() must read the bundle from its location (now version 2.0)
 *   expected:&lt;2.0.0&gt; but was:&lt;1.0.0&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The bundle has the content found at its location at the time of update(): version 2.0.0.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0, same scenario (file: location rewritten with version 2.0, then update()): version 2.0.0.
 *
 * <h3>Suggested fix</h3>
 * When Bundle-UpdateLocation is absent and the location is a URL, read the update from
 * {@code new URL(getLocation()).openStream()}; keep the current root only for locations that are not URLs.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class UpdateWithoutStreamIgnoresLocationIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUpdateReadsOriginalLocation() throws Exception {
        File file = new File("target/update-without-stream.jar");
        write(file, "1.0");
        Bundle bundle = getSystemContext().installBundle(file.toURI().toURL().toString());
        try {
            assertEquals(Version.parseVersion("1.0"), bundle.getVersion());
            write(file, "2.0");
            bundle.update();
            assertEquals("update() must read the bundle from its location (now version 2.0)", Version.parseVersion("2.0"), bundle.getVersion());
        } finally {
            bundle.uninstall();
        }
    }

    private void write(File file, final String version) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "update-without-stream");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("update-without-stream");
                builder.addBundleVersion(version);
                return builder.openStream();
            }
        });
        try (FileOutputStream out = new FileOutputStream(file)) {
            archive.as(ZipExporter.class).exportTo(out);
        }
    }
}
