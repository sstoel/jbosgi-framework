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
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.FindHook;

/**
 * Service find hooks are not called when a lookup finds no service, and they are obtained through the calling
 * bundle's context: the calling bundle (not the system bundle) shows up as user of every FindHook, its use count
 * grows with every lookup and is never released.
 *
 * <h3>Severity</h3>
 * major - a FindHook is the hook a bundle registers to see what other bundles look up (55.1.3: "the Find Hook is
 * notified with the list of discovered services"); a hook that reacts to lookups (on-demand import or creation of a
 * service, lookup auditing) never sees the lookups that need it most, the ones that find nothing. The hook usage
 * leak makes getUsingBundles/getServicesInUse report wrong data and, for a ServiceFactory hook, creates one hook
 * instance per looking-up bundle instead of the single framework (system bundle) instance.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ServiceManagerImpl#processFindHooks}.
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 4): seeded stateful service-registry differential
 * {@code fa.ServiceModel} (seeds 11, 12, 13; 1500 steps each; flags rmListeners, hooks) against Felix 7.0.5 and
 * Equinox 3.21. First divergence in seed 12, step 3: {@code getServiceReferences(null, "(P=2)")} by a bundle, no
 * match - Felix and Equinox call the FindHook, jbosgi does not. A follow-up probe ({@code fa.ProbeFindHook}) showed
 * the hook user difference.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 55.5 Find Hook (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.servicehooks.html):
 * "The Find Hook is called when a target bundle searches the service registry with the getServiceReference or
 * getServiceReferences methods." and "find(BundleContext,String,String,boolean,Collection) - The callback when a
 * bundle calls the getServiceReference, getServiceReferences, or getAllServiceReferences method." (no exception for
 * an empty result; the last parameter is "the set of service references that will be returned", which may be
 * empty).<br>
 * OSGi Core R8, 55.7.2 Ordinary Services: "All service hooks are treated as ordinary services. If the framework uses
 * them, their Service References will show that the system bundle is using them, and if a hook is a Service Factory,
 * then the actual instance will be properly created."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:352-353}
 * ({@code if (serviceStates.isEmpty()) return serviceStates;} before any hook is called) and {@code :369-371}
 * ({@code BundleContext context = bundle.getBundleContext(); ... hooks.add((FindHook) context.getService(hookRef));}
 * - the hook is fetched with the requesting bundle's context on every lookup and never released). Pre-existing
 * (3c8ccc02, 2011; 4c4895e3, 2013), unchanged by the R8 branch. The bundle FindHook
 * ({@code AbstractBundleContext.callFindHooks}, {@code :282}) is obtained the same way (calling bundle shown as
 * user); Felix and Equinox show the system bundle there too (not asserted here: 55.7.2 is worded for service hooks).
 *
 * <h3>Root cause</h3>
 * (1) processFindHooks returns early on an empty candidate list. (2) The hooks are obtained with
 * {@code BundleContext.getService} of the bundle doing the lookup instead of the system bundle context, and the
 * framework never calls ungetService for them.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FindHookInvocationIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testFindHookCalledWhenNothingIsFound: The FindHook must be called for every lookup, also when no service matches
 *   (called with the empty collection) expected:&lt;[org.acme.Missing, org.acme.Missing, (acme=missing)]&gt; but was:&lt;[]&gt;
 * testFindHookUsedBySystemBundle: The framework must use the FindHook through the system bundle, but
 *   getUsingBundles() of the hook is [findhook-client:0.0.0] expected:&lt;[0]&gt; but was:&lt;[1]&gt;
 * testFindHookNotInUseByCallingBundle: The calling bundle must not use the FindHook after three lookups, but
 *   getServicesInUse() returned [ServiceState{service.id=14, objectClass=[org.osgi.framework.hooks.service.FindHook],
 *   service.scope=singleton, service.bundleid=0}] expected null
 * </pre>
 *
 * <h3>Expected</h3>
 * The hook is called once per lookup, with an empty collection when nothing matched. getUsingBundles() of the hook
 * lists the system bundle only (if anything); the bundle that looked up services does not use the hook.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: hook called for all three empty lookups; hook users [system bundle]; client getServicesInUse() null.
 * Felix 7.0.5: hook called for all three empty lookups; hook users none; client getServicesInUse() null.
 *
 * <h3>Suggested fix</h3>
 * Drop the {@code serviceStates.isEmpty()} short-cut (keep the one for "no hooks registered"). Obtain the hooks with
 * the system bundle's context (as FrameworkEventsImpl does for the event hooks), or through a framework-internal
 * usage that is released after the call, and keep passing the requesting bundle's context to {@code find}.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class FindHookInvocationIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFindHookCalledWhenNothingIsFound() throws Exception {
        BundleContext syscontext = getFramework().getBundleContext();
        Bundle client = installBundle(getClientArchive());
        final List<String> calls = new ArrayList<String>();
        ServiceRegistration<FindHook> hookReg = syscontext.registerService(FindHook.class, new FindHook() {
            @Override
            public void find(BundleContext context, String name, String filter, boolean allServices, Collection<ServiceReference<?>> references) {
                if (references.isEmpty())
                    calls.add(name != null ? name : filter);
            }
        }, null);
        try {
            client.start();
            BundleContext context = client.getBundleContext();
            List<String> expected = new ArrayList<String>();
            assertNull(context.getServiceReferences("org.acme.Missing", null));
            expected.add("org.acme.Missing");
            assertNull(context.getServiceReference("org.acme.Missing"));
            expected.add("org.acme.Missing");
            assertNull(context.getAllServiceReferences(null, "(acme=missing)"));
            expected.add("(acme=missing)");
            assertEquals("The FindHook must be called for every lookup, also when no service matches (called with the empty collection)", expected, calls);
        } finally {
            hookReg.unregister();
            client.uninstall();
        }
    }

    @Test
    public void testFindHookUsedBySystemBundle() throws Exception {
        BundleContext syscontext = getFramework().getBundleContext();
        Bundle client = installBundle(getClientArchive());
        ServiceRegistration<FindHook> hookReg = syscontext.registerService(FindHook.class, new NoopFindHook(), null);
        ServiceRegistration<Runnable> serviceReg = syscontext.registerService(Runnable.class, new NoopRunnable(), null);
        try {
            client.start();
            BundleContext context = client.getBundleContext();
            assertEquals(1, context.getServiceReferences(Runnable.class.getName(), null).length);
            Bundle[] users = hookReg.getReference().getUsingBundles();
            List<Long> userIds = new ArrayList<Long>();
            if (users != null)
                for (Bundle user : users)
                    userIds.add(user.getBundleId());
            if (!userIds.isEmpty())
                assertEquals("The framework must use the FindHook through the system bundle, but getUsingBundles() of the hook is "
                        + Arrays.toString(users), Arrays.asList(0L), userIds);
        } finally {
            serviceReg.unregister();
            hookReg.unregister();
            client.uninstall();
        }
    }

    @Test
    public void testFindHookNotInUseByCallingBundle() throws Exception {
        BundleContext syscontext = getFramework().getBundleContext();
        Bundle client = installBundle(getClientArchive());
        ServiceRegistration<FindHook> hookReg = syscontext.registerService(FindHook.class, new NoopFindHook(), null);
        ServiceRegistration<Runnable> serviceReg = syscontext.registerService(Runnable.class, new NoopRunnable(), null);
        try {
            client.start();
            BundleContext context = client.getBundleContext();
            for (int i = 0; i < 3; i++)
                context.getServiceReferences(Runnable.class.getName(), null);
            ServiceReference<?>[] inUse = client.getServicesInUse();
            assertNull("The calling bundle must not use the FindHook after three lookups, but getServicesInUse() returned "
                    + (inUse != null ? Arrays.toString(inUse) : null), inUse);
        } finally {
            serviceReg.unregister();
            hookReg.unregister();
            client.uninstall();
        }
    }

    static class NoopFindHook implements FindHook {
        @Override
        public void find(BundleContext context, String name, String filter, boolean allServices, Collection<ServiceReference<?>> references) {
        }
    }

    static class NoopRunnable implements Runnable {
        @Override
        public void run() {
        }
    }

    private JavaArchive getClientArchive() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "findhook-client");
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
}
