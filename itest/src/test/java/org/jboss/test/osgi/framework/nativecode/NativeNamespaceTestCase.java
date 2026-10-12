package org.jboss.test.osgi.framework.nativecode;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.NativeNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Namespace;

/**
 * Test the osgi.native namespace.
 *
 * @since 11-Oct-2026
 */
public class NativeNamespaceTestCase extends OSGiFrameworkTest {

    @Test
    public void testSystemCapability() throws Exception {
        BundleRevision sysrev = getSystemContext().getBundle(0).adapt(BundleRevision.class);
        List<BundleCapability> caps = sysrev.getDeclaredCapabilities(NativeNamespace.NATIVE_NAMESPACE);
        assertEquals(1, caps.size());

        Map<String, Object> atts = caps.get(0).getAttributes();
        Collection<?> osnames = (Collection<?>) atts.get(NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE);
        assertTrue(osnames.contains(getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME)));
        Collection<?> processors = (Collection<?>) atts.get(NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE);
        assertTrue(processors.contains(getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR)));
        assertTrue(atts.get(NativeNamespace.CAPABILITY_OSVERSION_ATTRIBUTE) instanceof Version);
        assertEquals(getSystemContext().getProperty(Constants.FRAMEWORK_LANGUAGE), atts.get(NativeNamespace.CAPABILITY_LANGUAGE_ATTRIBUTE));
    }

    @Test
    public void testRequirementFromNativeCodeHeader() throws Exception {
        String header = "lib/a.so; osname=Linux; processor=x86-64; language=zz, lib/b.dll; osname=Win32; processor=x86; osversion=\"[1.0,2.0)\"";
        Bundle bundle = installBundle(getNativeCodeArchive("native-header", header));
        try {
            List<BundleRequirement> reqs = bundle.adapt(BundleRevision.class).getDeclaredRequirements(NativeNamespace.NATIVE_NAMESPACE);
            assertEquals(1, reqs.size());

            Map<String, String> dirs = reqs.get(0).getDirectives();
            assertNull("Mandatory by default", dirs.get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE));

            // One alternative per native code clause
            Map<String, Object> linux = new HashMap<String, Object>();
            linux.put(NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE, "linux");
            linux.put(NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE, "X86-64");
            linux.put(NativeNamespace.CAPABILITY_LANGUAGE_ATTRIBUTE, "zz");
            Map<String, Object> windows = new HashMap<String, Object>();
            windows.put(NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE, "win32");
            windows.put(NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE, "x86");
            windows.put(NativeNamespace.CAPABILITY_OSVERSION_ATTRIBUTE, Version.parseVersion("1.5"));

            org.osgi.framework.Filter filter = getSystemContext().createFilter(dirs.get(Namespace.REQUIREMENT_FILTER_DIRECTIVE));
            assertTrue(filter.matches(linux));
            assertTrue(filter.matches(windows));

            linux.put(NativeNamespace.CAPABILITY_LANGUAGE_ATTRIBUTE, "en");
            assertTrue("Language does not match", !filter.matches(linux));
            windows.put(NativeNamespace.CAPABILITY_OSVERSION_ATTRIBUTE, Version.parseVersion("2.0"));
            assertTrue("Version does not match", !filter.matches(windows));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testOptionalRequirement() throws Exception {
        Bundle bundle = installBundle(getNativeCodeArchive("native-optional", "lib/a.so; osname=NoSuchOS; processor=x86, *"));
        try {
            List<BundleRequirement> reqs = bundle.adapt(BundleRevision.class).getDeclaredRequirements(NativeNamespace.NATIVE_NAMESPACE);
            assertEquals(1, reqs.size());
            assertEquals(Namespace.RESOLUTION_OPTIONAL, reqs.get(0).getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE));

            // The bundle resolves although no clause matches
            bundle.start();
            assertBundleState(Bundle.ACTIVE, bundle.getState());
            assertTrue(bundle.adapt(BundleWiring.class).getRequiredWires(NativeNamespace.NATIVE_NAMESPACE).isEmpty());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testClauseWithoutSelection() throws Exception {
        // The second clause has no selection attributes, it matches every environment
        Bundle bundle = installBundle(getNativeCodeArchive("native-unconditional", "lib/a.so; osname=NoSuchOS; processor=x86, lib/b.so"));
        try {
            List<BundleRequirement> reqs = bundle.adapt(BundleRevision.class).getDeclaredRequirements(NativeNamespace.NATIVE_NAMESPACE);
            assertEquals(1, reqs.size());
            assertNull("No filter", reqs.get(0).getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE));
            assertNull("Mandatory", reqs.get(0).getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE));

            Collection<BundleCapability> providers = getSystemContext().getBundle().adapt(FrameworkWiring.class).findProviders(reqs.get(0));
            assertEquals("The system bundle provides it", 1, providers.size());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testNoMatchingClause() throws Exception {
        Bundle bundle = installBundle(getNativeCodeArchive("native-nomatch", "lib/a.so; osname=NoSuchOS; processor=x86"));
        try {
            bundle.start();
            fail("BundleException expected");
        } catch (BundleException ex) {
            assertBundleState(Bundle.INSTALLED, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testSelectionFilterWithLateSystemProperty() throws Exception {
        String osname = getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME);
        String processor = getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR);
        String header = "lib/libtest.so; osname=\"" + osname + "\"; processor=\"" + processor + "\"; selection-filter=\"(native.namespace.test=foo)\"";

        // The selection filter sees a system property that is set after the framework has started
        System.setProperty("native.namespace.test", "foo");
        try {
            JavaArchive archive = getNativeCodeArchive("native-selection", header);
            archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/libtest.so");
            Bundle bundle = installBundle(archive);
            try {
                bundle.start();
                assertBundleState(Bundle.ACTIVE, bundle.getState());
                List<BundleWire> wires = bundle.adapt(BundleWiring.class).getRequiredWires(NativeNamespace.NATIVE_NAMESPACE);
                assertEquals(1, wires.size());
                assertEquals("foo", wires.get(0).getCapability().getAttributes().get("native.namespace.test"));
            } finally {
                bundle.uninstall();
            }
        } finally {
            System.clearProperty("native.namespace.test");
        }

        // Without the property the clause is not selected
        JavaArchive archive = getNativeCodeArchive("native-selection", header);
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/libtest.so");
        Bundle bundle = installBundle(archive);
        try {
            bundle.start();
            fail("BundleException expected");
        } catch (BundleException ex) {
            assertBundleState(Bundle.INSTALLED, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testInvalidOSVersion() throws Exception {
        try {
            Bundle bundle = installBundle(getNativeCodeArchive("native-osversion", "lib/a.so; osname=Linux; processor=x86; osversion=\"[not.a.version\""));
            bundle.uninstall();
            fail("BundleException expected");
        } catch (BundleException ex) {
            assertEquals(BundleException.MANIFEST_ERROR, ex.getType());
        }
    }

    @Test
    public void testRequireCapability() throws Exception {
        // A requirement on the native capability of the system bundle that every platform satisfies
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "native-require");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                Map<String, String> dirs = new HashMap<String, String>();
                dirs.put("filter", "(&(osgi.native.osname=*)(osgi.native.processor=*))");
                builder.addRequiredCapability(NativeNamespace.NATIVE_NAMESPACE, null, dirs);
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            bundle.start();
            assertBundleState(Bundle.ACTIVE, bundle.getState());

            List<BundleWire> wires = bundle.adapt(BundleWiring.class).getRequiredWires(NativeNamespace.NATIVE_NAMESPACE);
            assertEquals(1, wires.size());
            assertEquals("Provided by the system bundle", 0, wires.get(0).getProvider().getBundle().getBundleId());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testProvideCapabilityRejected() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "native-provide");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addProvidedCapabilities(NativeNamespace.NATIVE_NAMESPACE + ";foo=bar");
                return builder.openStream();
            }
        });
        try {
            Bundle bundle = installBundle(archive);
            bundle.uninstall();
            fail("BundleException expected");
        } catch (BundleException ex) {
            assertEquals(BundleException.MANIFEST_ERROR, ex.getType());
        }
    }

    private JavaArchive getNativeCodeArchive(String name, final String nativeCode) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_NATIVECODE, nativeCode);
                return builder.openStream();
            }
        });
        assertNotNull(archive);
        return archive;
    }
}
