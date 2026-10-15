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

import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The default org.osgi.framework.system.packages does not contain all Java platform packages available at run time:
 * besides the java.* packages only the javax/org.* packages of a static list are exported, so platform packages that
 * are missing from the list - javax.smartcardio (module java.smartcardio) and, since Java 25, javax.sound
 * (module java.desktop) - cannot be imported.
 *
 * <h3>Severity</h3>
 * minor - a bundle that imports javax.smartcardio or javax.sound does not resolve; every new Java SE package needs a
 * code change.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.SystemPathsImpl.getDefaultSystemPackages} with the
 * static list {@code org.jboss.osgi.framework.spi.SystemPaths.DEFAULT_SYSTEM_PACKAGES}.
 *
 * <h3>Found by</h3>
 * resolver workstream, comparison of the system bundle exports with the unqualified exports of the java.* modules of
 * the running JDK (21, 25, 27) and with Equinox 3.21 (which exports both packages).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.4 Execution Environment
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.ee): "Starting in Java SE
 * 9 [...] Java SE 9 provides a standard way to interrogate the Java platform for the packages currently available at
 * runtime. Therefore, the Framework must set the org.osgi.framework.system.packages launching property, see Launching
 * Properties, to the list of Java platform packages currently available at runtime."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/SystemPathsImpl.java:145-156}
 * ({@code getDefaultSystemPackages}: java.* from the boot layer, every other package only if it is in
 * {@code SystemPaths.DEFAULT_SYSTEM_PACKAGES}) - commit 8d863429 "Implement OSGi Core R7"; the static list in
 * {@code core/src/main/java/org/jboss/osgi/framework/spi/SystemPaths.java} has no javax.smartcardio and no
 * javax.sound.
 *
 * <h3>Root cause</h3>
 * Only java.* packages are taken from the running platform; the non-java platform packages still come from a static
 * list that is incomplete (javax.smartcardio) and cannot know packages added by newer JDKs (javax.sound, Java 25).
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SystemPackagesPlatformPackagesIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}). On JDK 21 only javax.smartcardio is missing.
 *
 * <h3>Observed</h3>
 * <pre>
 * testAllPlatformPackagesExported: org.osgi.framework.system.packages must contain all packages exported by the Java
 *   SE modules of the running platform, missing: [javax.smartcardio, javax.sound]
 * testImportOfPlatformPackageResolves: A bundle that imports javax.smartcardio must resolve expected:&lt;true&gt; but
 *   was:&lt;false&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Every package that a java.* (Java SE) module of the boot layer exports unqualified is in the default system
 * packages and exported by the system bundle.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 on JDK 25 exports javax.smartcardio and javax.sound (and also the jdk.* / com.sun.* module packages).
 *
 * <h3>Suggested fix</h3>
 * Take all unqualified exports of the java.* modules of {@code ModuleLayer.boot()} (as is already done for the
 * java.* packages) instead of filtering the static list; keep the static list only for non-platform additions.
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 had a static list too (without javax.smartcardio); javax.sound only exists since Java 25.
 *
 * @since 13-Oct-2026
 */
public class SystemPackagesPlatformPackagesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testAllPlatformPackagesExported() throws Exception {
        String property = getSystemContext().getProperty(Constants.FRAMEWORK_SYSTEMPACKAGES);
        Set<String> configured = new HashSet<String>();
        for (String spec : property.split(",")) {
            int index = spec.indexOf(';');
            configured.add((index > 0 ? spec.substring(0, index) : spec).trim());
        }
        Set<String> missing = new TreeSet<String>();
        for (Module module : ModuleLayer.boot().modules()) {
            ModuleDescriptor descriptor = module.getDescriptor();
            if (descriptor == null || !descriptor.name().startsWith("java."))
                continue;
            for (ModuleDescriptor.Exports exports : descriptor.exports()) {
                if (!exports.isQualified() && !configured.contains(exports.source()))
                    missing.add(exports.source());
            }
        }
        assertTrue("org.osgi.framework.system.packages must contain all packages exported by the Java SE modules of the running platform, missing: " + missing, missing.isEmpty());
    }

    @Test
    public void testImportOfPlatformPackageResolves() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            assertEquals("A bundle that imports javax.smartcardio must resolve", true, getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle)));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive createBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "smartcardio-importer");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addImportPackages("javax.smartcardio");
                return builder.openStream();
            }
        });
        return archive;
    }
}
