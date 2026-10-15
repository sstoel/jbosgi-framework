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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.bundle.CollisionHook;

/**
 * Bundle CollisionHooks are called in reverse service ranking order (lowest ranking first); all other hooks (bundle
 * Find/Event hooks, weaving hooks, resolver hook factories) are called highest ranking first.
 *
 * <h3>Severity</h3>
 * minor - the ranking is how a management agent puts its collision policy in front of (or behind) another one; with
 * the order reversed, a lower-ranked hook that removes candidates decides before a higher-ranked hook sees them, so
 * the higher-ranked hook (e.g. a region hook that must see every collision to keep its bookkeeping) gets an emptied
 * or reduced collection.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin#checkUniqunessPolicy}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 1): {@code fa.BundleHookModel} (bsnversion=managed, versioned bundles
 * only, seed 3, 300 steps) against Equinox 3.21 and Felix 7.0.5: step 122, two collision hooks (ranking 0 and -1):
 * Equinox/Felix call the ranking-0 hook first, jbosgi the ranking -1 hook, which removed the candidate before the
 * other hook saw it. Probe {@code fa.ProbeHookOrder} (six hooks of each kind, rankings 5, 5, none, 0, -5, -5):
 * jbosgi calls Find/Event/Weaving hooks and ResolverHookFactories in the same order as Equinox/Felix, only the
 * collision hooks exactly reversed.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 54.5 Bundle Collision Hook
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.bundlehooks.html): "In managed mode the framework
 * must call all registered Collision Hook services for each bundle install and update operation. The calling order
 * of the hooks is defined by ranking order, see Service Ranking Order." and 54.2: "Hooks are always called in service
 * ranking order". 5.2.5 Service Ranking Order: the service with the highest ranking first; equal rankings: the
 * lowest service.id first.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:755-764}:
 * iterates {@code syscontext.getServiceReferences(CollisionHook.class, null)} as returned; the other hook call sites
 * (AbstractBundleContext.callFindHooks :279-280, FrameworkEventsImpl.getBundleEventHooks :451-452,
 * WeavingContext :102-103) reverse that list first ({@code Collections.reverse}) because getServiceReferences returns
 * the references lowest ranking first. Pre-existing (ad958ae2, 2013); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The collision hook loop misses the {@code Collections.reverse} (or an explicit sort by ServiceReference.compareTo,
 * descending) that the other hook call sites do.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=CollisionHookOrderIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testCollisionHooksCalledInRankingOrder: Collision hooks must be called in service ranking order (highest first)
 *   expected:&lt;[high(10), default(0), low(-10)]&gt; but was:&lt;[low(-10), default(0), high(10)]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * high(10), default(0), low(-10).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: highest ranking first (ties by service id).
 *
 * <h3>Suggested fix</h3>
 * Sort the references in descending ServiceReference order before calling the hooks (as the other hook call sites
 * do).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class CollisionHookOrderIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testCollisionHooksCalledInRankingOrder() throws Exception {
        final List<String> calls = new ArrayList<String>();
        List<ServiceRegistration<CollisionHook>> regs = new ArrayList<ServiceRegistration<CollisionHook>>();
        regs.add(register("low(-10)", -10, calls));
        regs.add(register("default(0)", null, calls));
        regs.add(register("high(10)", 10, calls));
        Bundle first = installBundle(getArchive("collision-order-1"));
        Bundle second = null;
        try {
            // same symbolic name and version, other location: a collision in managed mode
            second = installBundle(getArchive("collision-order-2"));
        } finally {
            for (ServiceRegistration<CollisionHook> reg : regs)
                reg.unregister();
            if (second != null)
                second.uninstall();
            first.uninstall();
        }
        assertEquals("Collision hooks must be called in service ranking order (highest first)", "[high(10), default(0), low(-10)]", calls.toString());
    }

    private ServiceRegistration<CollisionHook> register(final String name, Integer ranking, final List<String> calls) throws BundleException {
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        if (ranking != null)
            props.put(Constants.SERVICE_RANKING, ranking);
        return getSystemContext().registerService(CollisionHook.class, new CollisionHook() {
            public void filterCollisions(int operationType, Bundle target, Collection<Bundle> candidates) {
                calls.add(name);
                // the last hook lets the install pass
                if (calls.size() == 3)
                    candidates.clear();
            }
        }, props);
    }

    private JavaArchive getArchive(String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("collision.order");
                builder.addBundleVersion("1.0");
                return builder.openStream();
            }
        });
        return archive;
    }
}
