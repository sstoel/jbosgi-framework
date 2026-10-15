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

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.security.auth.x500.X500Principal;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

/**
 * With the default configuration every bundle sees all packages of the java.base module (javax.net.ssl,
 * javax.crypto, javax.security.auth.x500, ...) through the parent class loader, although it neither imports them nor
 * are they configured for boot delegation. A bundle's own copy of such a package can never be used.
 *
 * <h3>Severity</h3>
 * minor - mostly harmless for well-formed bundles, but bundles that forget to import a javax.* package of java.base
 * work on jbosgi and fail on every other framework, and OSGi TCK FrameworkLaunchTests.testBootDelegation fails with
 * the default configuration. The R8 branch documents the deviation and adds the opt-in property
 * {@code org.jboss.osgi.framework.bootdelegation.strict=true}, which is not the default.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkModuleLoaderImpl} (jboss-modules gives
 * every module an implicit dependency on the java.base module; only removed in strict mode).
 *
 * <h3>Found by</h3>
 * resolver workstream (boot delegation check), Equinox/Felix differential; confirms the TCK workstream's
 * FrameworkLaunchTests.testBootDelegation failure ("Unexpected class" for javax.security.auth.x500.X500Principal with
 * no boot delegation configured; passes with the strict property).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.5 Parent Class Loader
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.parentclassloader):
 * "The set of implicitly visible packages are all java.* packages [...] All other packages visible through the parent
 * class loader must be hidden from executing bundles." 3.9.4 Overall Search Order, step 2: "If the class or resource
 * is from a package included in the boot delegation list (org.osgi.framework.bootdelegation), then the request is
 * delegated to the parent class loader."
 *
 * <h3>Also shown (merged from tck/04, same root cause)</h3>
 * OSGi Core R8 TCK org.osgi.test.cases.framework.launch FrameworkLaunchTests.testBootDelegation fails with the default
 * configuration ("Unexpected class" for javax.security.auth.x500.X500Principal): a bundle that carries its own copy of a
 * javax.* class of java.base gets the JDK class instead, although javax.* is not on org.osgi.framework.bootdelegation
 * (3.9.4 Overall Search Order: only java.* and the boot delegation list go to the parent first).
 * Test method: testOwnCopyOfJavaBasePackageWithoutBootDelegation.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkModuleLoaderImpl.java:84-100,
 * 126-133} (the java.base dependency is removed only when {@code strictBootDelegation}), default from
 * {@code core/src/main/java/org/jboss/osgi/framework/spi/FrameworkModuleLoaderPlugin.java:57-58}; the property and
 * the documented default are from commit 8d863429 "Implement OSGi Core R7". The visibility itself comes from
 * jboss-modules 2.x and is the same on WF41.
 *
 * <h3>Root cause</h3>
 * jboss-modules adds a dependency on the java.base module to every module it loads; bundle modules keep it unless
 * the jbosgi specific strict property is set.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=JavaBasePackagesVisibleIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testJavaBaseClassNotVisibleWithoutImport: javax.net.ssl.SSLContext must not be visible to a bundle that does not
 *   import javax.net.ssl and has no boot delegation for it, but was loaded from the parent class loader
 * testJavaBaseResourceNotVisibleWithoutImport: javax/net/ssl/SSLContext.class must not be visible ... expected null,
 *   but was:&lt;jrt:/java.base/javax/net/ssl/SSLContext.class&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * ClassNotFoundException / null resource unless the package is imported (it is exported by the system bundle) or
 * listed in org.osgi.framework.bootdelegation - i.e. the strict behaviour by default.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: ClassNotFoundException for javax.net.ssl.SSLContext, javax.crypto.Cipher and
 * javax.security.auth.x500.X500Principal from a bundle without imports; resource not found.
 *
 * <h3>Suggested fix</h3>
 * Make strict boot delegation the default (remove the java.base dependency of bundle modules) and keep the property
 * as an opt-out for compatibility; or derive the visible java.base packages from org.osgi.framework.bootdelegation.
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 has the same visibility; the R8 branch only added the opt-in strict mode.
 *
 * @since 13-Oct-2026
 */
public class JavaBasePackagesVisibleIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testJavaBaseClassNotVisibleWithoutImport() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            try {
                Class<?> clazz = bundle.loadClass("javax.net.ssl.SSLContext");
                fail("javax.net.ssl.SSLContext must not be visible to a bundle that does not import javax.net.ssl and has no boot delegation for it, but was loaded from "
                        + (clazz.getClassLoader() == null ? "the parent class loader" : clazz.getClassLoader()));
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testJavaBaseResourceNotVisibleWithoutImport() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            assertNull("javax/net/ssl/SSLContext.class must not be visible to a bundle that does not import javax.net.ssl", bundle.getResource("javax/net/ssl/SSLContext.class"));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive createBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "java-base-visibility");
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

    // Merged from the TCK workstream (tck/04, withdrawn as same root cause): the bundle's own copy of a
    // java.base javax.* class is shadowed by the JDK class although javax.* is not boot delegated.


    private static final String OWN_CLASS_NAME = "javax.security.auth.x500.X500Principal";

    @Test
    public void testOwnCopyOfJavaBasePackageWithoutBootDelegation() throws Exception {
        BundleContext context = getSystemContext();
        String bootDelegation = context.getProperty(Constants.FRAMEWORK_BOOTDELEGATION);
        if (bootDelegation != null && (bootDelegation.contains("javax") || bootDelegation.trim().equals("*")))
            fail("Precondition: javax.* must not be on the boot delegation list: " + bootDelegation);

        Bundle bundle = installBundle(getBundleWithOwnClass());
        try {
            Class<?> loaded = bundle.loadClass(OWN_CLASS_NAME);
            assertNotSame("Without boot delegation for javax.security.auth.x500 the bundle must load its own X500Principal, not the JDK class",
                    X500Principal.class, loaded);
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive getBundleWithOwnClass() throws IOException {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "own-x500principal");
        archive.add(new ByteArrayAsset(createEmptyClass(OWN_CLASS_NAME.replace('.', '/'))), OWN_CLASS_NAME.replace('.', '/') + ".class");
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

    /** The bytes of a public class without members that extends java.lang.Object (class file version 52). */
    private static byte[] createEmptyClass(String internalName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0); // minor
        out.writeShort(52); // major
        out.writeShort(5); // constant pool count
        out.writeByte(1); // #1 Utf8 this class name
        out.writeUTF(internalName);
        out.writeByte(7); // #2 Class #1
        out.writeShort(1);
        out.writeByte(1); // #3 Utf8 super class name
        out.writeUTF("java/lang/Object");
        out.writeByte(7); // #4 Class #3
        out.writeShort(3);
        out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
        out.writeShort(2); // this class
        out.writeShort(4); // super class
        out.writeShort(0); // interfaces
        out.writeShort(0); // fields
        out.writeShort(0); // methods
        out.writeShort(0); // attributes
        out.flush();
        return bytes.toByteArray();
    }
}
