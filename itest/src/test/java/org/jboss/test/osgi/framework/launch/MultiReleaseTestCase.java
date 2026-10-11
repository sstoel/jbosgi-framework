package org.jboss.test.osgi.framework.launch;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.support.VersionedType;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.resource.Namespace;

/**
 * Test multi-release bundles.
 *
 * The Java version that the framework assumes is given by the java.specification.version framework property.
 *
 * @since 11-Oct-2026
 */
public class MultiReleaseTestCase extends AbstractFrameworkLaunchTest {

    private static final String LOCATION = "multi-release-bundle";
    private static final String VERSIONED_TYPE = VersionedType.class.getName();

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && framework.getState() == Bundle.ACTIVE) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testVersionedResources() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();

        // The highest versioned directory that applies to the Java version wins
        assertEquals("base", readResource(bundle, "res/base.txt"));
        assertEquals("eleven", readResource(bundle, "res/over.txt"));
        assertEquals("eleven", readResource(bundle, "res/added.txt"));
        assertEquals("nine", readResource(bundle, "res/nine.txt"));
        assertNull("Version in the future", bundle.getResource("res/future.txt"));
        assertNull("Version before Java 9", bundle.getResource("res/eight.txt"));

        List<URL> urls = Collections.list(bundle.getResources("res/over.txt"));
        assertEquals(1, urls.size());
        assertEquals("eleven", readURL(urls.get(0)));

        // A resource URL can be recreated from its external form
        URL url = bundle.getResource("res/over.txt");
        assertEquals("eleven", readURL(new URL(url.toExternalForm())));
    }

    @Test
    public void testVersionedClassPathEntries() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();

        // A jar and a directory on the bundle class path are multi-release containers on their own
        assertEquals("base", readResource(bundle, "jarres/base.txt"));
        assertEquals("nine", readResource(bundle, "jarres/over.txt"));
        assertEquals("base", readResource(bundle, "dirres/base.txt"));
        assertEquals("nine", readResource(bundle, "dirres/over.txt"));

        // A directory without a multi-release manifest is not a multi-release container
        assertEquals("base", readResource(bundle, "plainres/over.txt"));
    }

    @Test
    public void testVersionedClass() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();

        Class<?> clazz = bundle.loadClass(VERSIONED_TYPE);
        assertEquals("versioned", clazz.getConstructor().newInstance().toString());
        assertEquals("Loaded from the bundle", bundle.adapt(BundleWiring.class).getClassLoader(), clazz.getClassLoader());
    }

    @Test
    public void testEntriesAreNotVersioned() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();

        assertEquals("base", readURL(bundle.getEntry("res/over.txt")));
        assertNull(bundle.getEntry("res/added.txt"));
        assertEquals("eleven", readURL(bundle.getEntry("META-INF/versions/11/res/over.txt")));

        List<String> paths = Collections.list(bundle.getEntryPaths("res"));
        assertEquals(new TreeSet<String>(java.util.Arrays.asList("res/base.txt", "res/over.txt")), new TreeSet<String>(paths));
    }

    @Test
    public void testListResources() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();

        BundleWiring wiring = bundle.adapt(BundleWiring.class);
        Collection<String> resources = wiring.listResources("res", "*.txt", 0);
        assertEquals(new TreeSet<String>(java.util.Arrays.asList("res/added.txt", "res/base.txt", "res/nine.txt", "res/over.txt")), new TreeSet<String>(resources));
        assertEquals("Listed once", 4, resources.size());

        // The versioned directories cannot add to the META-INF directory
        assertTrue(wiring.listResources("META-INF", "hidden.txt", BundleWiring.LISTRESOURCES_RECURSE).isEmpty());
        assertNull(bundle.getResource("META-INF/hidden.txt"));
    }

    @Test
    public void testVersionedMetaInfIsNotAccessible() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();

        URL url = bundle.getResource("res/added.txt");
        assertNotNull(url);
        try {
            new URL(url, "/META-INF/hidden.txt").openStream().close();
            fail("IOException expected");
        } catch (IOException ex) {
            // expected
        }
    }

    @Test
    public void testNotActiveBeforeJava9() throws Exception {
        Bundle bundle = installBundle("1.8", true);
        bundle.start();

        assertEquals("base", readResource(bundle, "res/over.txt"));
        assertNull(bundle.getResource("res/added.txt"));
        assertEquals("base", readResource(bundle, "jarres/over.txt"));
        try {
            bundle.loadClass(VERSIONED_TYPE);
            fail("ClassNotFoundException expected");
        } catch (ClassNotFoundException ex) {
            // expected
        }

        // The supplemental manifest is not used
        assertEquals("cap.base", bundle.getHeaders().get(Constants.REQUIRE_CAPABILITY));
        assertEquals(1, bundle.adapt(BundleRevision.class).getDeclaredRequirements("cap.base").size());
    }

    @Test
    public void testSupplementalManifest() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();

        // Import-Package and Require-Capability are replaced, all other headers are ignored
        assertEquals("cap.eleven", bundle.getHeaders().get(Constants.REQUIRE_CAPABILITY));
        assertEquals("pkg.eleven", bundle.getHeaders().get(Constants.IMPORT_PACKAGE));
        assertEquals("multi-release", bundle.getSymbolicName());
        assertEquals("multi-release", bundle.getHeaders().get(Constants.BUNDLE_SYMBOLICNAME));
        assertNull(bundle.getHeaders().get("Supplemental-Header"));

        BundleRevision brev = bundle.adapt(BundleRevision.class);
        assertEquals(1, brev.getDeclaredRequirements("cap.eleven").size());
        assertTrue(brev.getDeclaredRequirements("cap.base").isEmpty());
        List<BundleRequirement> pkgreqs = brev.getDeclaredRequirements(PackageNamespace.PACKAGE_NAMESPACE);
        assertEquals(1, pkgreqs.size());
        assertTrue(pkgreqs.get(0).getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE).contains("pkg.eleven"));
        assertEquals(1, bundle.adapt(BundleWiring.class).getRequiredWires("cap.eleven").size());
    }

    @Test
    public void testFirstSupplementalManifestIsUsed() throws Exception {
        // Java 10 has no supplemental manifest of its own, the one for Java 9 does not have Import-Package
        Bundle bundle = installBundle("10", true);
        bundle.start();

        assertEquals("cap.nine", bundle.getHeaders().get(Constants.REQUIRE_CAPABILITY));
        assertEquals("pkg.base", bundle.getHeaders().get(Constants.IMPORT_PACKAGE));
        assertEquals("nine", readResource(bundle, "res/over.txt"));
    }

    @Test
    public void testSupplementalManifestAfterUpdate() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();
        bundle.update(new ByteArrayInputStream(getMultiReleaseBundle()));

        assertEquals(Bundle.ACTIVE, bundle.getState());
        assertEquals("cap.eleven", bundle.getHeaders().get(Constants.REQUIRE_CAPABILITY));
        assertEquals(1, bundle.adapt(BundleRevision.class).getDeclaredRequirements("cap.eleven").size());
        assertEquals("eleven", readResource(bundle, "res/over.txt"));
    }

    @Test
    public void testMultiReleaseFragment() throws Exception {
        framework = newFramework(getInitProperties("17", true));
        framework.start();

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
        headers.put(Constants.BUNDLE_SYMBOLICNAME, "host");
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("hostres/base.txt", text("base"));
        Bundle host = framework.getBundleContext().installBundle("host", new ByteArrayInputStream(createJar(headers, false, entries)));

        headers.put(Constants.BUNDLE_SYMBOLICNAME, "fragment");
        headers.put(Constants.FRAGMENT_HOST, "host");
        entries = new LinkedHashMap<String, byte[]>();
        entries.put("fragres/over.txt", text("base"));
        entries.put("META-INF/versions/9/fragres/over.txt", text("nine"));
        framework.getBundleContext().installBundle("fragment", new ByteArrayInputStream(createJar(headers, true, entries)));

        // The fragment is a multi-release container on its own
        host.start();
        assertEquals("nine", readResource(host, "fragres/over.txt"));
        assertEquals("base", readResource(host, "hostres/base.txt"));
    }

    @Test
    public void testPackageOnlyInVersionedDirectory() throws Exception {
        framework = newFramework(getInitProperties("17", true));
        framework.start();

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
        headers.put(Constants.BUNDLE_SYMBOLICNAME, "exporter");
        headers.put(Constants.EXPORT_PACKAGE, VersionedType.class.getPackage().getName());
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("res/base.txt", text("base"));
        entries.put("META-INF/versions/9/" + VERSIONED_TYPE.replace('.', '/') + ".class", getClassBytes());
        Bundle exporter = framework.getBundleContext().installBundle("exporter", new ByteArrayInputStream(createJar(headers, true, entries)));

        headers.put(Constants.BUNDLE_SYMBOLICNAME, "importer");
        headers.remove(Constants.EXPORT_PACKAGE);
        headers.put(Constants.IMPORT_PACKAGE, VersionedType.class.getPackage().getName());
        entries = new LinkedHashMap<String, byte[]>();
        entries.put("res/other.txt", text("other"));
        Bundle importer = framework.getBundleContext().installBundle("importer", new ByteArrayInputStream(createJar(headers, false, entries)));

        importer.start();
        assertSame(exporter.loadClass(VERSIONED_TYPE), importer.loadClass(VERSIONED_TYPE));
    }

    @Test
    public void testUnknownJavaVersion() throws Exception {
        // The versioned directories are not used when the Java version cannot be determined
        Bundle bundle = installBundle("unknown", true);
        bundle.start();
        assertEquals("base", readResource(bundle, "res/over.txt"));
        assertEquals("cap.base", bundle.getHeaders().get(Constants.REQUIRE_CAPABILITY));
    }

    @Test
    public void testJavaVersionChange() throws Exception {
        Bundle bundle = installBundle("17", true);
        bundle.start();
        long lastModified = bundle.getLastModified();
        framework.stop();
        framework.waitForStop(10000);
        Thread.sleep(50);

        // The same Java version does not modify the bundle
        framework = newFramework(getInitProperties("17", false));
        framework.start();
        bundle = framework.getBundleContext().getBundle(LOCATION);
        assertNotNull(bundle);
        assertEquals(lastModified, bundle.getLastModified());
        assertEquals("cap.eleven", bundle.getHeaders().get(Constants.REQUIRE_CAPABILITY));
        framework.stop();
        framework.waitForStop(10000);
        Thread.sleep(50);

        // Another Java version does
        framework = newFramework(getInitProperties("9", false));
        framework.start();
        bundle = framework.getBundleContext().getBundle(LOCATION);
        assertNotNull(bundle);
        assertFalse(lastModified == bundle.getLastModified());
        assertEquals(Bundle.ACTIVE, bundle.getState());
        assertEquals("cap.nine", bundle.getHeaders().get(Constants.REQUIRE_CAPABILITY));
        assertEquals("nine", readResource(bundle, "res/over.txt"));
    }

    private Map<String, String> getInitProperties(String javaVersion, boolean clean) {
        Map<String, String> initprops = getFrameworkInitProperties(clean);
        initprops.put("java.specification.version", javaVersion);
        return initprops;
    }

    private Bundle installBundle(String javaVersion, boolean clean) throws Exception {
        framework = newFramework(getInitProperties(javaVersion, clean));
        framework.start();
        return framework.getBundleContext().installBundle(LOCATION, new ByteArrayInputStream(getMultiReleaseBundle()));
    }

    private String readResource(Bundle bundle, String path) throws IOException {
        URL url = bundle.getResource(path);
        assertNotNull("Resource found: " + path, url);
        return readURL(url);
    }

    private String readURL(URL url) throws IOException {
        assertNotNull(url);
        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "UTF-8"));
        try {
            return reader.readLine();
        } finally {
            reader.close();
        }
    }

    private byte[] getMultiReleaseBundle() throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
        headers.put(Constants.BUNDLE_SYMBOLICNAME, "multi-release");
        headers.put(Constants.BUNDLE_VERSION, "1.0.0");
        headers.put(Constants.BUNDLE_CLASSPATH, "., lib/nested.jar, dir, plain");
        headers.put(Constants.EXPORT_PACKAGE, "pkg.base, pkg.eleven");
        headers.put(Constants.IMPORT_PACKAGE, "pkg.base");
        headers.put(Constants.PROVIDE_CAPABILITY, "cap.base, cap.nine, cap.eleven");
        headers.put(Constants.REQUIRE_CAPABILITY, "cap.base");

        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("res/base.txt", text("base"));
        entries.put("res/over.txt", text("base"));
        entries.put("META-INF/versions/8/res/over.txt", text("eight"));
        entries.put("META-INF/versions/8/res/eight.txt", text("eight"));
        entries.put("META-INF/versions/9/res/over.txt", text("nine"));
        entries.put("META-INF/versions/9/res/nine.txt", text("nine"));
        entries.put("META-INF/versions/9/OSGI-INF/MANIFEST.MF", manifest("Require-Capability: cap.nine\n"));
        entries.put("META-INF/versions/9/" + VERSIONED_TYPE.replace('.', '/') + ".class", getClassBytes());
        entries.put("META-INF/versions/11/res/over.txt", text("eleven"));
        entries.put("META-INF/versions/11/res/added.txt", text("eleven"));
        entries.put("META-INF/versions/11/META-INF/hidden.txt", text("hidden"));
        entries.put("META-INF/versions/11/OSGI-INF/MANIFEST.MF",
                manifest("Require-Capability: cap.eleven\nImport-Package: pkg.eleven\nBundle-SymbolicName: other\nSupplemental-Header: ignored\n"));
        entries.put("META-INF/versions/9999/res/over.txt", text("future"));
        entries.put("META-INF/versions/9999/res/future.txt", text("future"));

        // A multi-release jar on the bundle class path
        Map<String, byte[]> nested = new LinkedHashMap<String, byte[]>();
        nested.put("jarres/base.txt", text("base"));
        nested.put("jarres/over.txt", text("base"));
        nested.put("META-INF/versions/9/jarres/over.txt", text("nine"));
        entries.put("lib/nested.jar", createJar(Collections.<String, String> emptyMap(), true, nested));

        // A multi-release directory on the bundle class path
        entries.put("dir/META-INF/MANIFEST.MF", manifest("Multi-Release: true\n"));
        entries.put("dir/dirres/base.txt", text("base"));
        entries.put("dir/dirres/over.txt", text("base"));
        entries.put("dir/META-INF/versions/9/dirres/over.txt", text("nine"));

        // A directory on the bundle class path that is not a multi-release container
        entries.put("plain/plainres/over.txt", text("base"));
        entries.put("plain/META-INF/versions/9/plainres/over.txt", text("nine"));

        return createJar(headers, true, entries);
    }

    private byte[] createJar(Map<String, String> headers, boolean multiRelease, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        if (multiRelease)
            attributes.putValue("Multi-Release", "true");
        for (Map.Entry<String, String> entry : headers.entrySet())
            attributes.putValue(entry.getKey(), entry.getValue());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos, manifest);
        List<String> directories = new ArrayList<String>();
        directories.add("META-INF/");
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            // Add the parent directories
            String name = entry.getKey();
            for (int index = name.indexOf('/'); index > 0; index = name.indexOf('/', index + 1)) {
                String directory = name.substring(0, index + 1);
                if (directories.contains(directory) == false) {
                    directories.add(directory);
                    jos.putNextEntry(new JarEntry(directory));
                    jos.closeEntry();
                }
            }
            jos.putNextEntry(new JarEntry(name));
            jos.write(entry.getValue());
            jos.closeEntry();
        }
        jos.close();
        return baos.toByteArray();
    }

    private byte[] text(String value) throws IOException {
        return value.getBytes("UTF-8");
    }

    private byte[] manifest(String headers) throws IOException {
        return ("Manifest-Version: 1.0\n" + headers + "\n").getBytes("UTF-8");
    }

    private byte[] getClassBytes() throws IOException {
        InputStream input = VersionedType.class.getResourceAsStream(VersionedType.class.getSimpleName() + ".class");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int len = input.read(buffer); len > 0; len = input.read(buffer))
                baos.write(buffer, 0, len);
            return baos.toByteArray();
        } finally {
            input.close();
        }
    }
}
