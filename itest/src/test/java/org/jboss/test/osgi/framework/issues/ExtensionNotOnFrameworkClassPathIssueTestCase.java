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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

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
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The content of a framework extension bundle is not appended to the class path of the framework: its classes are
 * defined by the class loader of the jboss-modules framework module ("jbosgi.framework"), not by the class loader
 * that loaded the framework implementation, and they are not visible from that class loader.
 *
 * <h3>Severity</h3>
 * minor. Framework extensions that are meant to extend the framework implementation (or the class path it runs
 * on, e.g. a provider found with ServiceLoader or Class.forName from the framework's class loader) do not work.
 * Plain fragments of the system bundle that only export packages to bundles work. The framework only uses the
 * specified behaviour when it was loaded by a URLClassLoader AND java.net is opened to it
 * ({@code --add-opens java.base/java.net=ALL-UNNAMED}); never when it runs on the application class path or in a
 * jboss-modules module (WildFly).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.FrameworkModuleProviderImpl#addExtensionContent /
 * #appendToFrameworkClassPath
 *
 * <h3>Found by</h3>
 * TCK workstream: OSGi Core R8 TCK org.osgi.test.cases.framework.launch 8.0.0,
 * {@code ExtensionBundleTests.testFrameworkExtensionDefault} and {@code ExtensionBundleTests.testFrameworkExtensionBundle}
 * fail on JBoss OSGi (JDK 25, macOS and linux/amd64) with
 * {@code junit.framework.AssertionFailedError: expected class to be loaded by the framework classloader
 * expected:<BundleReferenceClassLoader for Module "jbosgi.framework" from FrameworkModuleLoader> but
 * was:<org.osgi.test.cases.framework.launch.junit.LaunchTest$FrameworkClassLoader@411cd732>}. They pass on Equinox
 * 3.18.600 and Felix 7.0.5, and on JBoss OSGi only when the TCK JVM runs with
 * {@code --add-opens java.base/java.net=ALL-UNNAMED}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.15.3 Class Path Treatment (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "A extension bundle's JAR is appended to the class path of the Framework. Extension bundles that are resolved
 * together must have their JAR appended in the order in which the extension bundles are installed: that is,
 * ascending bundle ID order." and 3.15: "The contents of extension bundles cannot be provided by the normal bundles
 * because they need to be loaded by the framework implementation."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkModuleProviderImpl.java:161-176
 * ({@code addExtensionContent}: falls back to {@code extensionLoaders.add(...)} / {@code refreshResourceLoaders} of
 * the framework module when {@code appendToFrameworkClassPath} returns false, lines 170-172) and :178-191
 * ({@code appendToFrameworkClassPath}: {@code if (classLoader instanceof URLClassLoader == false) return false;}
 * and reflective {@code URLClassLoader.addURL}, which needs java.net opened). Introduced by commit 8d863429
 * ("Implement OSGi Core R7": "Its content becomes part of the framework module, or of the class path of the
 * framework class loader when that is possible").
 *
 * <h3>Root cause</h3>
 * The framework can only append to a URLClassLoader through deep reflection; on Java 9+ the application class loader
 * is not a URLClassLoader and java.net is closed by default, so the extension content is put into the framework
 * module's own class loader instead.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ExtensionNotOnFrameworkClassPathIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The class of a framework extension must be loaded by the class loader of the framework
 *   implementation expected same:&lt;jdk.internal.loader.ClassLoaders$AppClassLoader@14dad5dc&gt; was not:&lt;BundleReferenceClassLoader
 *   for Module "jbosgi.framework" from FrameworkModuleLoader&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The extension's classes are loaded by (and visible from) the class loader that loaded the framework
 * implementation, as if the JAR was on the framework's class path.
 *
 * <h3>Reference behaviour</h3>
 * Standalone program, JDK 25, no --add-opens, the TCK's fragments.tb21.jar installed as extension and resolved,
 * framework on the application class path and in a child-first URLClassLoader: Equinox 3.18.600 and Felix 7.0.5
 * load FooTB21 with the framework implementation's class loader in both cases (also visible with Class.forName);
 * JBoss OSGi WF41-osgi-r8 loads it with the "jbosgi.framework" module class loader in both cases (visible from the
 * implementation loader only with --add-opens java.base/java.net=ALL-UNNAMED and a URLClassLoader). The WF41 build
 * (jbosgi-framework-core 6.6.0.Final) cannot load the extension class at all (ClassNotFoundException).
 *
 * <h3>Suggested fix</h3>
 * Append the extension content to the framework's class loader also when it is the application class loader,
 * as the reference frameworks do without extra JVM options. Felix 7.0.5 calls the application class loader's
 * {@code appendToClassPathForInstrumentation(String)} (org/apache/felix/framework/ext/ClassPathExtenderFactory.java:54)
 * and uses {@code URLClassLoader.addURL} made accessible through {@code sun.misc.Unsafe} /
 * {@code MethodHandles.Lookup.IMPL_LOOKUP} (org/apache/felix/framework/util/SecureAction.java:1141-1175); verified to
 * work on JDK 25 without --add-opens. java.lang.instrument would need an agent. Alternatively document that framework extensions need a URLClassLoader-based launcher with
 * --add-opens java.base/java.net=ALL-UNNAMED and report the limitation (FrameworkEvent WARNING) instead of silently
 * using another class loader.
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 could not load extension classes at all; the R7 commit improved it partially).
 */
public class ExtensionNotOnFrameworkClassPathIssueTestCase extends OSGiFrameworkTest {

    private static final String CLASS_NAME = "org.jboss.test.osgi.framework.issues.extension.ExtensionContent";

    @Test
    public void testExtensionClassLoadedByFrameworkClassLoader() throws Exception {
        BundleContext context = getSystemContext();
        Bundle extension = installBundle(getExtensionArchive());
        try {
            assertEquals("Precondition: extensions are supported", "true", context.getProperty(Constants.SUPPORTS_FRAMEWORK_EXTENSION));
            context.getBundle(0).adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(extension));
            assertEquals("Precondition: the extension is resolved", Bundle.RESOLVED, extension.getState());

            ClassLoader frameworkLoader = context.getClass().getClassLoader();
            Class<?> loaded = context.getBundle(0).loadClass(CLASS_NAME);
            assertSame("The class of a framework extension must be loaded by the class loader of the framework implementation",
                    frameworkLoader, loaded.getClassLoader());
            try {
                Class.forName(CLASS_NAME, false, frameworkLoader);
            } catch (ClassNotFoundException ex) {
                fail("The class of a framework extension must be visible from the class loader of the framework implementation: " + ex);
            }
        } finally {
            extension.uninstall();
        }
    }

    private JavaArchive getExtensionArchive() throws IOException {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "framework-extension-content");
        archive.add(new ByteArrayAsset(createEmptyClass(CLASS_NAME.replace('.', '/'))), CLASS_NAME.replace('.', '/') + ".class");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addFragmentHost(Constants.SYSTEM_BUNDLE_SYMBOLICNAME + ";" + Constants.EXTENSION_DIRECTIVE + ":=" + Constants.EXTENSION_FRAMEWORK);
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
        out.writeShort(0);
        out.writeShort(52);
        out.writeShort(5);
        out.writeByte(1);
        out.writeUTF(internalName);
        out.writeByte(7);
        out.writeShort(1);
        out.writeByte(1);
        out.writeUTF("java/lang/Object");
        out.writeByte(7);
        out.writeShort(3);
        out.writeShort(0x0021);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.flush();
        return bytes.toByteArray();
    }
}
