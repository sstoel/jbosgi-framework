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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Framework extensions that are resolved together are not appended in ascending bundle id order: an extension that
 * depends on a later installed extension is attached (and its content appended) after that extension, so the later
 * installed extension's content comes first.
 *
 * <h3>Severity</h3>
 * minor - only matters for extensions that are resolved together, depend on each other and contain the same class or
 * resource; then the wrong one is found first.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkExtensions.resolve} (new in this branch).
 *
 * <h3>Found by</h3>
 * resolver workstream, extension bundle scenarios ($SP/explore/resolver/sc/ext/y1, y2): without a dependency the
 * lower id wins, with a dependency of the lower id extension on the higher id one the higher id wins.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.15.3 Class Path Treatment
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.extensionbundles):
 * "A extension bundle's JAR is appended to the class path of the Framework. Extension bundles that are resolved
 * together must have their JAR appended in the order in which the extension bundles are installed: that is,
 * ascending bundle ID order."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkExtensions.java:193-223}: the
 * candidates are sorted by bundle id, but each is attached ({@code attach}, which calls
 * {@code FrameworkModuleProvider.addExtensionContent}) as soon as its requirements are satisfied by the system bundle
 * and the already attached extensions; an extension whose requirement is provided by a later extension is skipped in
 * the first pass and attached in a later pass, after that extension. Introduced with the class in commit 8d863429
 * "Implement OSGi Core R7".
 *
 * <h3>Root cause</h3>
 * Resolving and appending happen in the same loop; the append order is the order in which the requirements become
 * satisfiable, not the install order.
 *
 * <h3>Observability</h3>
 * The test uses the default launch path of the itest (the framework is not loaded by a URLClassLoader, so the
 * extension content is added as resource loaders of the framework module in attach order) and looks at the resource
 * that both extensions contain through the system bundle. With {@code --add-opens java.base/java.net=ALL-UNNAMED} and a
 * URLClassLoader framework loader (the TCK launch path) {@code addExtensionContent} appends the JARs with
 * {@code URLClassLoader.addURL} in the same attach order, so the order is wrong there too. Which loader is used is a
 * different question (tck/05); this issue is only about the order.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ExtensionClassPathOrderIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testDependentExtensionsAppendedInInstallOrder: The extensions are resolved together, the content of the first
 *   installed extension must come first expected:&lt;[first]&gt; but was:&lt;[second]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The first installed extension (lower bundle id) is appended first, whether or not it depends on the second one.
 *
 * <h3>Reference behaviour</h3>
 * Not usable as a reference: Equinox 3.21 returns the resource of the later extension in both the dependent and the
 * independent case.
 *
 * <h3>Suggested fix</h3>
 * In {@code FrameworkExtensions.resolve}, first determine (iteratively) the set of extensions whose requirements can
 * be satisfied, then attach that set in ascending bundle id order.
 *
 * <h3>Regression vs WF41</h3>
 * no (new feature) - WF41 had no extension support.
 *
 * @since 13-Oct-2026
 */
public class ExtensionClassPathOrderIssueTestCase extends AbstractFrameworkLaunchTest {

    private static final String RESOURCE = "org/acme/extension/order.txt";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testDependentExtensionsAppendedInInstallOrder() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();

        // The first extension imports a package of the second one
        Bundle first = install("first", Constants.IMPORT_PACKAGE, "org.acme.extension.second");
        Bundle second = install("second", Constants.EXPORT_PACKAGE, "org.acme.extension.second");
        assertTrue(first.getBundleId() < second.getBundleId());
        assertTrue("Both extensions resolve", framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(first, second)));

        URL url = framework.getBundleContext().getBundle(0).getResource(RESOURCE);
        assertNotNull("The extension resource is visible through the system bundle", url);
        assertEquals("The extensions are resolved together, the content of the first installed extension must come first", "first", read(url));
    }

    private Bundle install(String name, String key, String value) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "order.extension." + name);
        attributes.putValue(Constants.FRAGMENT_HOST, "system.bundle; extension:=framework");
        attributes.putValue(key, value);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos, manifest);
        jos.putNextEntry(new JarEntry(RESOURCE));
        jos.write(name.getBytes("UTF-8"));
        jos.closeEntry();
        jos.putNextEntry(new JarEntry("org/acme/extension/" + name + "/marker.txt"));
        jos.write(name.getBytes("UTF-8"));
        jos.closeEntry();
        jos.close();
        return framework.getBundleContext().installBundle(name, new ByteArrayInputStream(baos.toByteArray()));
    }

    private String read(URL url) throws Exception {
        InputStream input = url.openStream();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            for (int len = input.read(buffer); len > 0; len = input.read(buffer))
                baos.write(buffer, 0, len);
            return new String(baos.toByteArray(), "UTF-8");
        } finally {
            input.close();
        }
    }
}
