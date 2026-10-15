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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;

/**
 * The system bundle's metadata is built with the symbolic name "system.bundle" and version 0.0.0, while
 * Bundle.getVersion() returns the framework version: the Bundle-Version header, the osgi.identity / osgi.wiring.bundle
 * / osgi.wiring.host capabilities and the bundle-version and bundle-symbolic-name attributes of every system package
 * export say 0.0.0 and "system.bundle", so requirements on the real system bundle version or name never resolve.
 *
 * <h3>Severity</h3>
 * minor. {@code Require-Bundle: system.bundle;bundle-version="[6.8,7)"}, a framework extension with
 * {@code Fragment-Host: system.bundle;extension:=framework;bundle-version=...}, and
 * {@code Import-Package: org.osgi.framework;bundle-symbolic-name=org.jboss.osgi.framework} or
 * {@code ...;bundle-version="[6.8,7)"} never resolve. getHeaders().get("Bundle-Version") (0.0.0) contradicts
 * getVersion() (6.8.0.Final). When the framework is loaded from the aggregated -all jar, whose manifest has no
 * Implementation-Version, getVersion() is 0.0.0 as well (Framework.getVersion() included).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.SystemBundlePlugin#createMetaData and
 * BundleManagerPlugin#getFrameworkVersion
 *
 * <h3>Found by</h3>
 * services workstream round 2 (system bundle version, reported by the coordinator for the -all jar), Equinox
 * differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 3.9.5 Parent Class Loader (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "The export definitions must have the bundle-symbolic-name and bundle-version attributes with the
 * implementation-specific bundle symbolic name and bundle version value of the system bundle." 3.7.10 Provider Selection: "An importer can
 * select an exporter with the import attributes bundle-symbolic-name and bundle-version. The Framework automatically
 * provides these attributes for each export definition." 4.6 The System Bundle
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "The system bundle has a bundle
 * symbolic name that is unique for a specific version. However, the name system.bundle must be recognized as an alias
 * to this implementation-defined name.";
 * Bundle.getHeaders: returns the bundle's Manifest headers; BundleRevision.BUNDLE_NAMESPACE / HOST_NAMESPACE:
 * "The bundle-version capability attribute must contain the Version of the bundle from the Bundle-Version manifest
 * header".
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/SystemBundlePlugin.java:132
 * ({@code OSGiMetaDataBuilder.createBuilder(Constants.SYSTEM_BUNDLE_SYMBOLICNAME, Version.emptyVersion)}),
 * BundleManagerPlugin.java:344-356 (getFrameworkVersion reads only Package.getImplementationVersion(), null in the
 * -all jar), SystemBundleState.java:79-91 (getSymbolicName/getVersion do not use the metadata).
 * Already on WF41 (createMetaData unchanged).
 *
 * <h3>Root cause</h3>
 * The metadata from which the headers and capabilities are derived uses the alias name and the empty version
 * instead of the framework's symbolic name and version; getVersion() is computed separately from the jar manifest's
 * Implementation-Version.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SystemBundleVersionIssueTestCase} (installed core jar) or
 * {@code ... mvn -Dmaven.compiler.proc=full -pl itest -am test -Dtest=SystemBundleVersionIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false}
 * (reactor build). The expected version is determined independently of the framework (see
 * getExpectedFrameworkVersion: jar Implementation-Version, pom.properties, -Djbosgi.framework.version, or the parent
 * version in the itest pom.xml), so all methods fail in both modes.
 *
 * <h3>Observed</h3>
 * <pre>
 * testBundleVersionHeader: The Bundle-Version header must be the framework implementation version
 *   expected:&lt;6.8.0.Final&gt; but was:&lt;0.0.0&gt;   (installed core jar)
 * testBundleVersionHeader: Bundle.getVersion() of the system bundle must be the framework implementation version
 *   expected:&lt;6.8.0.Final&gt; but was:&lt;0.0.0&gt;   (reactor build, core/target/classes without a manifest)
 * testRequireBundleWithSystemBundleVersion: Require-Bundle: system.bundle;bundle-version="[6.8.0.Final,6.8.0.Final]" must resolve
 * testSystemPackageBundleVersion: bundle-version of the org.osgi.framework export must be the framework implementation version
 *   expected:&lt;6.8.0.Final&gt; but was:&lt;0.0.0&gt;
 * testSystemPackageBundleSymbolicName: bundle-symbolic-name of the org.osgi.framework export must contain the
 *   implementation symbolic name org.jboss.osgi.framework, but was system.bundle
 * </pre>
 *
 * <h3>Expected</h3>
 * Headers, capabilities and package export attributes carry the framework's symbolic name (with system.bundle as an
 * alias) and the same version that getVersion() returns; the version is also available when the framework comes
 * from the -all jar (e.g. from its Bundle-Version header).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0: getVersion() and the Bundle-Version header are both 3.21.0.v20240717-2103; the org.osgi.framework
 * export has bundle-symbolic-name=[org.eclipse.osgi, system.bundle] and bundle-version=3.21.0.v20240717-2103;
 * Require-Bundle on system.bundle with the exact version resolves. JBoss OSGi (core jar): getVersion 6.8.0.Final,
 * header 0.0.0, bundle-symbolic-name=system.bundle, bundle-version=0.0.0, the Require-Bundle does not resolve.
 *
 * <h3>Suggested fix</h3>
 * Build the system metadata with {@code Constants.JBOSGI_FRAMEWORK_SYMBOLIC_NAME} and
 * {@code BundleManagerPlugin.getFrameworkVersion()}, add "system.bundle" as an alias value of the bundle-symbolic-name
 * / osgi.wiring.bundle / osgi.wiring.host attributes, and let getFrameworkVersion fall back to the Bundle-Version
 * of the jar manifest when Implementation-Version is absent.
 *
 * Checked with a partial fix (metadata version = getFrameworkVersion()) against the installed core jar: the three
 * version methods pass; testSystemPackageBundleSymbolicName additionally needs the alias support and passes on Equinox.
 * In a reactor build (core/target/classes, no manifest) getFrameworkVersion() itself must also stop depending on the
 * jar manifest (e.g. read the version from a resource filtered with ${project.version}) for the version methods to pass.
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 has the same createMetaData).
 */
public class SystemBundleVersionIssueTestCase extends OSGiFrameworkTest {

    /**
     * The implementation version of jbosgi-framework-core, determined independently of the framework: the
     * Implementation-Version or the Maven pom.properties of the jar that holds the framework classes,
     * the system property jbosgi.framework.version, or the parent version in the pom.xml of the itest module (the
     * working directory of the test, used when the framework classes come from core/target/classes in a reactor build).
     */
    static Version getExpectedFrameworkVersion() throws Exception {
        Class<?> frameworkClass = Class.forName("org.jboss.osgi.framework.internal.BundleManagerPlugin");
        String version = frameworkClass.getPackage().getImplementationVersion();
        if (version == null) {
            URL props = frameworkClass.getClassLoader().getResource("META-INF/maven/org.jboss.osgi.framework/jbosgi-framework-core/pom.properties");
            if (props != null) {
                Properties properties = new Properties();
                try (InputStream in = props.openStream()) {
                    properties.load(in);
                }
                version = properties.getProperty("version");
            }
        }
        if (version == null)
            version = System.getProperty("jbosgi.framework.version");
        if (version == null) {
            File pom = new File("pom.xml");
            if (pom.isFile()) {
                String text = new String(Files.readAllBytes(pom.toPath()), StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("<parent>.*?<artifactId>jbosgi-framework-parent</artifactId>\\s*<version>([^<]+)</version>", Pattern.DOTALL).matcher(text);
                if (matcher.find())
                    version = matcher.group(1).trim();
            }
        }
        assertNotNull("The implementation version of jbosgi-framework-core cannot be determined (no Implementation-Version, no pom.properties, no -Djbosgi.framework.version, no itest pom.xml)", version);
        return Version.parseVersion(version);
    }

    @Test
    public void testBundleVersionHeader() throws Exception {
        Version expected = getExpectedFrameworkVersion();
        Bundle sysbundle = getSystemContext().getBundle(0);
        assertEquals("Bundle.getVersion() of the system bundle must be the framework implementation version", expected, sysbundle.getVersion());
        assertEquals("The Bundle-Version header must be the framework implementation version", expected.toString(),
                sysbundle.getHeaders().get(Constants.BUNDLE_VERSION));
    }

    @Test
    public void testRequireBundleWithSystemBundleVersion() throws Exception {
        String version = getExpectedFrameworkVersion().toString();
        String requirement = "system.bundle;bundle-version=\"[" + version + "," + version + "]\"";
        Bundle bundle = installBundle(getBundleArchive("require-system-version", Constants.REQUIRE_BUNDLE, requirement));
        try {
            assertTrue("Require-Bundle: " + requirement + " must resolve", resolveBundles(Arrays.asList(bundle)));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testSystemPackageBundleVersion() throws Exception {
        Bundle sysbundle = getSystemContext().getBundle(0);
        Object version = getFrameworkExport(sysbundle).getAttributes().get(PackageNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE);
        assertEquals("bundle-version of the org.osgi.framework export must be the framework implementation version", getExpectedFrameworkVersion(), version);
    }

    @Test
    public void testSystemPackageBundleSymbolicName() throws Exception {
        Bundle sysbundle = getSystemContext().getBundle(0);
        Object name = getFrameworkExport(sysbundle).getAttributes().get(PackageNamespace.CAPABILITY_BUNDLE_SYMBOLICNAME_ATTRIBUTE);
        Collection<?> names = name instanceof Collection ? (Collection<?>) name : Collections.singleton(name);
        assertTrue("bundle-symbolic-name of the org.osgi.framework export must contain the implementation symbolic name "
                + sysbundle.getSymbolicName() + ", but was " + name, names.contains(sysbundle.getSymbolicName()));
    }

    private BundleCapability getFrameworkExport(Bundle sysbundle) {
        for (BundleCapability cap : sysbundle.adapt(BundleRevision.class).getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            if ("org.osgi.framework".equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                return cap;
        }
        throw new AssertionError("The system bundle does not export org.osgi.framework");
    }

    private JavaArchive getBundleArchive(final String name, final String header, final String value) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader(header, value);
                return builder.openStream();
            }
        });
        return archive;
    }
}
