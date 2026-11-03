/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;

/**
 * A synchronous bundle listener sees a bundle RESOLVED while it gets the UNRESOLVED event of the bundle, on an uninstall
 * and on a refresh. A BundleTracker with a RESOLVED mask then keeps tracking it: its customizer gets the bundle with a
 * wiring of null, and bnd's tester throws a NullPointerException for each.
 *
 * <h2>Severity</h2>
 * minor. Equinox and Felix give the state INSTALLED, the spec does not name the state.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code UserBundleState.refresh}, {@code UserBundleState.uninstallInternal}.
 *
 * <h2>Found by</h2>
 * R8 release check, TCK-2 of the TCK run (component7 and http7 logs).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.4.4 and BundleEvent.UNRESOLVED: "The bundle has been unresolved", the event of the transition to
 * INSTALLED.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:549-550, :605-610 (84ac5c2e): the event was
 * fired before the state was changed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=UnresolvedEventStateIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The state of the bundle is INSTALLED while the listeners get its UNRESOLVED event.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class UnresolvedEventStateIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testStateDuringUnresolvedEvent() throws Exception {
        Bundle bundle = installBundle(getBundle("unresolved-event-state"));
        final List<Integer> states = Collections.synchronizedList(new ArrayList<Integer>());
        SynchronousBundleListener listener = new SynchronousBundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getType() == BundleEvent.UNRESOLVED)
                    states.add(event.getBundle().getState());
            }
        };
        getSystemContext().addBundleListener(listener);
        try {
            assertTrue(resolveBundles(Collections.singleton(bundle)));
            refreshBundles(Collections.singleton(bundle));
            assertEquals("The state during the UNRESOLVED event of the refresh", Collections.singletonList(Bundle.INSTALLED), states);

            states.clear();
            assertTrue(resolveBundles(Collections.singleton(bundle)));
            bundle.uninstall();
            assertEquals("The state during the UNRESOLVED event of the uninstall", Collections.singletonList(Bundle.INSTALLED), states);
        } finally {
            getSystemContext().removeBundleListener(listener);
            if (bundle.getState() != Bundle.UNINSTALLED)
                bundle.uninstall();
        }
    }

    private JavaArchive getBundle(final String symbolicName) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                return builder.openStream();
            }
        });
        return archive;
    }
}
