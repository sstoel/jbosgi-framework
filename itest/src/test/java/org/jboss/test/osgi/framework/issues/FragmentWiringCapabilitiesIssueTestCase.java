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

import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The BundleWiring of a resolved fragment does not follow the Wiring API: it lacks the fragment's osgi.identity
 * capability, contains the package and generic capabilities that the fragment contributes to its host,
 * listResources returns null and findEntries returns the fragment's entries.
 *
 * <h3>Severity</h3>
 * minor - Wiring API introspection (tools, OBR/repository clients, the bundle DTOs) gets wrong data for fragments;
 * class loading is not affected because the host wiring is correct.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractWiring.getResourceCapabilities} (used by
 * {@code AbstractBundleWiring.getCapabilities} in the framework) and
 * {@code org.jboss.osgi.resolver.spi.AbstractBundleWiring.findEntries / listResources}.
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential of randomized resolution scenarios (fragment wirings listed package
 * capabilities on jbosgi but not on Equinox 3.21 / Felix 7.0.5), then a targeted wiring dump.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 7.4 Fragments (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html):
 * "The osgi.identity capability of a fragment is part of the fragment's Bundle Wiring and is not part of a host
 * bundle's Bundle Wiring. [...] Any other requirements and capabilities in a fragment bundle never become part of the
 * fragment's Bundle Wiring; they are treated as part of the host's requirements and capabilities when the fragment
 * is attached to that host." BundleWiring.getCapabilities javadoc: "A bundle wiring for a fragment revision with a
 * symbolic name must provide exactly one identity capability." BundleWiring.listResources javadoc: "@return An
 * unmodifiable collection of resource names for each matching resource, or an empty collection if no matching
 * resource could be found, if this bundle wiring is for a fragment revision [...]". BundleWiring.findEntries
 * javadoc: "@return An unmodifiable list of URL objects for each matching entry, or an empty list if no matching
 * entry could be found, if this bundle wiring is for a fragment revision [...]".
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractWiring.java:153-218}: line 155 starts
 * from all declared capabilities of the resource (also for a fragment), and lines 211-215 ("Remove identity
 * capability for fragments", commit dbd130c, 2012) remove exactly the one capability a fragment wiring must keep.
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractBundleWiring.java:198-210}
 * ({@code findEntries} searches the revision whether or not it is a fragment) and {@code :224-226}
 * ({@code listResources} returns {@code null} for a fragment revision).
 *
 * <h3>Root cause</h3>
 * {@code getResourceCapabilities} does not distinguish host and fragment wirings. For a fragment it should return
 * only the osgi.identity capability; it returns every other declared capability and removes the identity.
 * {@code AbstractBundleWiring.listResources} treats a fragment wiring like a wiring that is not in use (null), and
 * {@code findEntries} does not check for a fragment at all.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FragmentWiringCapabilitiesIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testFragmentWiringHasIdentityCapability: The wiring of fragment frag-wiring-fragment:0.0.0 must provide exactly one
 *   osgi.identity capability expected:&lt;1&gt; but was:&lt;0&gt;
 * testFragmentWiringHasNoHostedCapabilities: The wiring of fragment frag-wiring-fragment:0.0.0 must not provide the
 *   capabilities it contributes to its host, but has [osgi.wiring.package, frag.test.capability]
 * testFragmentWiringListResources: listResources of the fragment wiring must return an empty collection, but
 *   returned null
 * testFragmentWiringFindEntries: findEntries of the fragment wiring must return an empty list, but returned
 *   [.../res/frag.txt]
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code fragment.adapt(BundleWiring.class).getCapabilities(null)} is exactly the fragment's osgi.identity capability
 * (type osgi.fragment); its exported package and Provide-Capability appear only in the host wiring (where they are
 * already listed correctly). {@code listResources} on the fragment wiring returns an empty collection and
 * {@code findEntries} an empty list (the host wiring's findEntries already returns the fragment entries).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: the fragment wiring has only {@code osgi.identity {osgi.identity=frag, type=osgi.fragment}}; the host
 * wiring lists the fragment's package and generic capability; listResources and findEntries of the fragment wiring
 * return empty collections.
 *
 * <h3>Suggested fix</h3>
 * In {@code AbstractWiring.getResourceCapabilities}, when the wiring's resource is a fragment, return only its
 * osgi.identity capability (effective:=resolve filter still applies); drop the "Remove identity capability for
 * fragments" block. The host branch stays as it is. In {@code AbstractBundleWiring}, return an empty unmodifiable
 * collection/list from {@code listResources} and {@code findEntries} when {@code getRevision().isFragment()}.
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 (jbosgi-resolver 7.6.0) behaves the same (checked with the same scenarios); still a spec violation on
 * the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class FragmentWiringCapabilitiesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFragmentWiringHasIdentityCapability() throws Exception {
        Bundle[] bundles = installAndResolve();
        try {
            BundleWiring wiring = bundles[1].adapt(BundleWiring.class);
            assertNotNull("The fragment is resolved and has a wiring", wiring);
            List<BundleCapability> identities = wiring.getCapabilities(IdentityNamespace.IDENTITY_NAMESPACE);
            assertEquals("The wiring of fragment " + bundles[1] + " must provide exactly one osgi.identity capability", 1, identities.size());
            assertEquals("The identity type of the fragment", IdentityNamespace.TYPE_FRAGMENT, identities.get(0).getAttributes().get(IdentityNamespace.CAPABILITY_TYPE_ATTRIBUTE));
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testFragmentWiringHasNoHostedCapabilities() throws Exception {
        Bundle[] bundles = installAndResolve();
        try {
            BundleWiring wiring = bundles[1].adapt(BundleWiring.class);
            assertNotNull("The fragment is resolved and has a wiring", wiring);
            StringBuilder others = new StringBuilder();
            for (BundleCapability cap : wiring.getCapabilities(null)) {
                if (!IdentityNamespace.IDENTITY_NAMESPACE.equals(cap.getNamespace()))
                    others.append(others.length() > 0 ? ", " : "").append(cap.getNamespace());
            }
            assertTrue("The wiring of fragment " + bundles[1] + " must not provide the capabilities it contributes to its host, but has [" + others + "]", others.length() == 0);

            // The host wiring carries them
            BundleWiring hostWiring = bundles[0].adapt(BundleWiring.class);
            boolean hostHasPackage = false;
            for (BundleCapability cap : hostWiring.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
                hostHasPackage |= "org.acme.fragment.pkg".equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE));
            }
            assertTrue("The host wiring provides the package exported by the fragment", hostHasPackage);
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testFragmentWiringListResources() throws Exception {
        Bundle[] bundles = installAndResolve();
        try {
            BundleWiring wiring = bundles[1].adapt(BundleWiring.class);
            assertNotNull("The fragment is resolved and has a wiring", wiring);
            Collection<String> names = wiring.listResources("/", "*", BundleWiring.LISTRESOURCES_RECURSE);
            assertNotNull("listResources of the fragment wiring must return an empty collection, but returned null", names);
            assertTrue("listResources of the fragment wiring must return an empty collection, but returned " + names, names.isEmpty());
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testFragmentWiringFindEntries() throws Exception {
        Bundle[] bundles = installAndResolve();
        try {
            BundleWiring wiring = bundles[1].adapt(BundleWiring.class);
            assertNotNull("The fragment is resolved and has a wiring", wiring);
            List<URL> entries = wiring.findEntries("res", "*", BundleWiring.FINDENTRIES_RECURSE);
            assertNotNull("findEntries of the fragment wiring must return an empty list, but returned null", entries);
            assertTrue("findEntries of the fragment wiring must return an empty list, but returned " + entries, entries.isEmpty());
            // The host wiring finds the fragment entry
            List<URL> hostEntries = bundles[0].adapt(BundleWiring.class).findEntries("res", "*", BundleWiring.FINDENTRIES_RECURSE);
            assertEquals("The host wiring finds the entry of the attached fragment: " + hostEntries, 1, hostEntries.size());
        } finally {
            uninstall(bundles);
        }
    }

    private Bundle[] installAndResolve() throws Exception {
        Bundle host = installBundle(createBundle("frag-wiring-host", null, "org.acme.host.pkg", null));
        Bundle fragment = installBundle(createBundle("frag-wiring-fragment", "frag-wiring-host", "org.acme.fragment.pkg", "frag.test.capability;frag.test.capability=x"));
        assertTrue("Host and fragment resolve", getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(host, fragment)));
        return new Bundle[] { host, fragment };
    }

    private void uninstall(Bundle[] bundles) throws Exception {
        for (int i = bundles.length - 1; i >= 0; i--)
            bundles[i].uninstall();
    }

    private JavaArchive createBundle(final String symbolicName, final String fragmentHost, final String exports, final String capability) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        if (fragmentHost != null)
            archive.add(new StringAsset("fragment"), "res/frag.txt");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                if (fragmentHost != null)
                    builder.addFragmentHost(fragmentHost);
                if (exports != null)
                    builder.addManifestHeader("Export-Package", exports);
                if (capability != null)
                    builder.addManifestHeader("Provide-Capability", capability);
                return builder.openStream();
            }
        });
        return archive;
    }
}
