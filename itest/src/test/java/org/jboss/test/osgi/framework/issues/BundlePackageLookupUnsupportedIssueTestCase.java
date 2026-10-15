/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Package.getPackage(String) and Package.getPackages() called by bundle code throw UnsupportedOperationException
 * when a system package (e.g. org.osgi.framework) is visible to the bundle.
 *
 * <h2>Severity</h2>
 * major (any library inside a bundle that uses the JDK package lookup, e.g. {@code Package.getPackage(name)} to read
 * implementation/specification versions, or {@code Package.getPackages()} to enumerate packages, fails with an
 * unexpected UnsupportedOperationException; Equinox and Felix return normally).
 *
 * <h2>Component</h2>
 * jbosgi-framework, module core, class org.jboss.osgi.framework.internal.FrameworkModuleProviderImpl, inner class
 * FrameworkClassLoaderLoader (the JBoss Modules LocalLoader for the system packages of the framework module).
 *
 * <h2>Found by</h2>
 * Tests workstream, zero-coverage audit of the methods changed in r8: FrameworkClassLoaderLoader.loadPackageLocal
 * (refactored into a named class by r8 commit 8d863429 "Implement OSGi Core R7"; the throwing body is unchanged since 2012) is never executed by the suite. Reading JBoss Modules 2.1.6
 * (Module.getPackage/getPackages call LocalLoader.loadPackageLocal for every local loader of a path) and probing
 * from a bundle activator; Equinox/Felix differential.
 *
 * <h2>Spec reference</h2>
 * Java SE 25 API, java.lang.ClassLoader.getPackage(String) / getPackages() and java.lang.Package.getPackage(String)
 * / getPackages(): "Returns ... the Package of the given name defined by the caller's class loader and its
 * ancestors, or null if not found" / "Returns all of the Packages defined by the caller's class loader and its
 * ancestors"; neither declares UnsupportedOperationException. OSGi Core R8 3.9.4 (Bundle class loader): the bundle
 * class loader is a normal java.lang.ClassLoader to the code of the bundle; nothing in the OSGi spec restricts these
 * JDK methods.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkModuleProviderImpl.java:211-214
 * ({@code FrameworkClassLoaderLoader.loadPackageLocal} throws {@code new UnsupportedOperationException()}). The
 * loader is registered for the system package paths (:136, and :141 for class-path extensions). A bundle module that
 * sees a system package inherits that LocalLoader for the path, so JBoss Modules
 * {@code Module.getPackage(name)} / {@code Module.getPackages()} (jboss-modules 2.1.6 Module.java:1741-1765, reached
 * from ModuleClassLoader.getPackageByName/getPackages) call it. Same code on WF41 (introduced 2012, commit 7dfb6fdc).
 *
 * <h2>Root cause</h2>
 * The LocalLoader contract (org.jboss.modules.LocalLoader.loadPackageLocal: "Load a package from this loader ...
 * returns the package or null") is implemented as "unsupported".
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=BundlePackageLookupUnsupportedIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full only works around tests/01, LoggerImplementationMissingIssueTestCase.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testPackageGetPackage: Expected Package.getPackage("org.osgi.framework") from bundle code to return normally,
 *   but it threw java.lang.UnsupportedOperationException
 * testPackageGetPackages: Expected Package.getPackages() from bundle code to return normally, but it threw
 *   java.lang.UnsupportedOperationException
 * </pre>
 *
 * <h2>Expected</h2>
 * Both calls return normally (a Package or null; an array).
 *
 * <h2>Reference behaviour</h2>
 * Same bundle (activator calling the JDK methods) on Equinox 3.21.0: getPackage(org.osgi.framework)=null,
 * getPackages().length=115; Felix 7.0.5: null, 105. No exception.
 *
 * <h2>Suggested fix</h2>
 * In FrameworkClassLoaderLoader.loadPackageLocal return {@code classLoader.getDefinedPackage(name)} (or
 * {@code classLoader.getPackage(name)} semantics via the framework class loader), never throw; same for
 * FallbackLoader.loadPackageLocal (:186), which is not on a path today but has the same contract violation.
 * Checked: with {@code return classLoader.getDefinedPackage(name);} both test methods pass (JDK 25).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; verified with the same probe on origin/WF41: same UnsupportedOperationException). The WildFly
 * integration (umbrella FrameworkModuleIntegration) uses its own provider and was not checked.
 */
public class BundlePackageLookupUnsupportedIssueTestCase extends OSGiFrameworkTest {

    static final String RESULT_GET_PACKAGE = "issues.packagelookup.getPackage";
    static final String RESULT_GET_PACKAGES = "issues.packagelookup.getPackages";

    public static class PackageLookupActivator implements BundleActivator {
        @Override
        @SuppressWarnings("deprecation")
        public void start(BundleContext context) {
            try {
                Package.getPackage("org.osgi.framework");
                System.setProperty(RESULT_GET_PACKAGE, "ok");
            } catch (Throwable th) {
                System.setProperty(RESULT_GET_PACKAGE, th.toString());
            }
            try {
                Package.getPackages();
                System.setProperty(RESULT_GET_PACKAGES, "ok");
            } catch (Throwable th) {
                System.setProperty(RESULT_GET_PACKAGES, th.toString());
            }
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    @After
    public void tearDown() throws Exception {
        System.getProperties().remove(RESULT_GET_PACKAGE);
        System.getProperties().remove(RESULT_GET_PACKAGES);
        super.tearDown();
    }

    private void runProbeBundle() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "package-lookup-probe");
        archive.addClasses(PackageLookupActivator.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleActivator(PackageLookupActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            bundle.start();
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testPackageGetPackage() throws Exception {
        runProbeBundle();
        assertEquals("Expected Package.getPackage(\"org.osgi.framework\") from bundle code to return normally, but it threw "
                + System.getProperty(RESULT_GET_PACKAGE), "ok", System.getProperty(RESULT_GET_PACKAGE));
    }

    @Test
    public void testPackageGetPackages() throws Exception {
        runProbeBundle();
        assertEquals("Expected Package.getPackages() from bundle code to return normally, but it threw "
                + System.getProperty(RESULT_GET_PACKAGES), "ok", System.getProperty(RESULT_GET_PACKAGES));
    }
}
