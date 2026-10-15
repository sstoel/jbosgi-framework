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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * A bundle whose install fails while its resource is added to the environment stays installed: after the
 * BundleException the bundle is listed by getBundles(), returned for its location, and blocks every later install
 * of the same symbolic name and version. A capability with a List-valued attribute named after its namespace
 * triggers this (ClassCastException in the environment cache).
 *
 * <h3>Severity</h3>
 * major - the framework state is corrupted by a failed install: the "not installed" bundle is visible to all
 * bundles, a corrected bundle cannot be installed under the same name/version or location until the framework is
 * restarted. The trigger (typed list attribute on the namespace attribute) is legal manifest syntax that the RI
 * accepts.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractEnvironment#installResources} and
 * {@code AbstractEnvironment.CacheKey#create(Capability)}; jbosgi-framework core
 * {@code UserBundleRevisionFactory#create} / {@code handleCreateException}.
 *
 * <h3>Found by</h3>
 * fuzz workstream: Equinox differential replay of the Jazzer manifest corpus. After corpus input
 * {@code Provide-Capability: osgi.service;osgi.service:List<String>="org.acme.S";...} every following install of
 * the default symbolic name failed with DUPLICATE_BUNDLE_ERROR (the "bundle count changed after failed install"
 * oracle flagged the input). Minimized to {@code Provide-Capability: acme.ns;acme.ns:List<String>="a,b"}.
 *
 * <h3>Spec reference</h3>
 * BundleContext.installBundle(String, InputStream) javadoc (OSGi Core R8, 10.1.7.19): "Postconditions, when an
 * exception is thrown: Bundle is not installed. If there was an existing bundle for the specified location, then
 * that bundle must still be in the state it was prior to calling this method."
 * OSGi Core R8, 3.3.6 / 1.3.2: attributes of Provide-Capability may be typed ({@code List<String>} etc.); nothing
 * restricts the type of the attribute that has the name of the namespace.
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:409-411}
 * ({@code String nsvalue = (String) cap.getAttributes().get(namespace);} - ClassCastException for a List) called
 * from {@code installResources} at {@code :128}, after {@code :112-124} already put the resource into
 * {@code resourceIndexCache}, the type cache and the capability caches; there is no rollback.
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleRevisionFactory.java:104-123}:
 * {@code handleCreateException} deletes the storage state but does not uninstall the resource from the
 * environment; {@code BundleManagerPlugin.getBundles()} (:410) derives the installed bundles from the environment,
 * so the half-installed bundle is visible (state INSTALLED). Code from 2012/2013 (e5848e0, c951a99), on WF41 too.
 *
 * <h3>Root cause</h3>
 * (1) CacheKey assumes the namespace attribute is a String. (2) The install path has no compensation for a failure
 * after the resource was (partly) installed into the environment.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FailedInstallLeavesBundleInstalledIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testListValuedNamespaceAttributeInstalls: Provide-Capability: acme.ns;acme.ns:List&lt;String&gt;="a,b" must install, but:
 *   BundleException: JBOSGI011231: Cannot create bundle revision from: [failed-install-list:0.0.0,location=failed-install-list]
 *   caused by java.lang.ClassCastException: class java.util.ArrayList cannot be cast to class java.lang.String
 * testNoBundleLeftAfterFailedInstall: installBundle threw BundleException: JBOSGI011231: Cannot create bundle revision from:
 *   [failed-install-leak:0.0.0,location=failed-install-leak], but a bundle is still installed at location
 *   failed-install-leak (id=2,state=2)
 * (A standalone probe also showed: installing the same location again returns the half-installed bundle with the
 * bad manifest; installing the same name/version at another location fails with JBOSGI011240: Bundle name and
 * version already installed.)
 * </pre>
 *
 * <h3>Expected</h3>
 * The bundle with the List-valued namespace attribute installs (as on Equinox). Independently of that trigger: when
 * installBundle throws, no bundle is left installed for that location, and a valid bundle with the same symbolic
 * name and version can be installed afterwards.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 installs {@code Provide-Capability: acme.ns;acme.ns:List<String>="a,b"} (state INSTALLED, the
 * attribute is a List). Equinox removes a bundle whose install failed.
 *
 * <h3>Suggested fix</h3>
 * In CacheKey.create use the String value only ({@code value instanceof String ? (String) value : null}, or index
 * every element of a List). In UserBundleRevisionFactory.create / handleCreateException uninstall the revision from
 * the environment ({@code env.uninstallResources(brev)}) and detach the bundle when anything fails after
 * {@code installResources} was entered; make installResources itself atomic.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 13-Oct-2026
 */
public class FailedInstallLeavesBundleInstalledIssueTestCase extends OSGiFrameworkTest {

    private static final String LIST_NAMESPACE_ATTRIBUTE = "acme.ns;acme.ns:List<String>=\"a,b\"";

    @Test
    public void testListValuedNamespaceAttributeInstalls() throws Exception {
        try {
            Bundle bundle = install("failed-install-list", "failed-install-list", LIST_NAMESPACE_ATTRIBUTE);
            bundle.uninstall();
        } catch (BundleException ex) {
            fail("Provide-Capability: " + LIST_NAMESPACE_ATTRIBUTE + " must install, but: " + ex + " caused by " + ex.getCause());
        }
    }

    @Test
    public void testNoBundleLeftAfterFailedInstall() throws Exception {
        String location = "failed-install-leak";
        Bundle bundle;
        try {
            bundle = install(location, "failed-install-leak", LIST_NAMESPACE_ATTRIBUTE);
        } catch (BundleException ex) {
            // The install failed, nothing may be left behind
            Bundle leftOver = getSystemContext().getBundle(location);
            if (leftOver != null) {
                String state = "id=" + leftOver.getBundleId() + ",state=" + leftOver.getState();
                leftOver.uninstall();
                fail("installBundle threw " + ex + ", but a bundle is still installed at location " + location + " (" + state + ")");
            }
            for (Bundle aux : getSystemContext().getBundles())
                assertNull("No bundle of the failed install is listed: " + aux, location.equals(aux.getLocation()) ? aux : null);

            // A correct bundle with the same name and version can be installed
            try {
                install(location + "-fixed", "failed-install-leak", null).uninstall();
            } catch (BundleException ex2) {
                fail("After the failed install, a bundle with the same symbolic name and version must install, but: " + ex2);
            }
            return;
        }
        // The install succeeded (the trigger is fixed): nothing to check
        bundle.uninstall();
    }

    private Bundle install(String location, String symbolicName, String providedCapability) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        if (providedCapability != null)
            attributes.putValue(Constants.PROVIDE_CAPABILITY, providedCapability);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return getSystemContext().installBundle(location, new ByteArrayInputStream(baos.toByteArray()));
    }
}
