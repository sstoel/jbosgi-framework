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
import static org.junit.Assert.fail;

import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * Bundle.start(START_TRANSIENT) of a bundle whose start level is above the framework's active start level throws a
 * BundleException of type UNSPECIFIED (0) instead of START_TRANSIENT_ERROR (10).
 *
 * <h3>Severity</h3>
 * minor - callers that start bundles transiently (management agents, console "start -t", deployers) cannot tell
 * "not started because of the start level" (a normal, expected situation) from a real failure; the exception type is
 * the only machine-readable reason.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState} (also
 * {@code org.jboss.osgi.framework.spi.AbstractBundleAdaptor} for module bundles) and
 * {@code org.jboss.osgi.framework.FrameworkMessages#cannotStartBundleDueToStartLevel}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 6/7): seeded persistence/start-level differential
 * {@code fa.PersistModel} (random install/update/uninstall, start with all four option combinations, stop, bundle and
 * framework start levels, initial start level, data files, framework restarts on the same storage) against Equinox
 * 3.21 and Felix 7.0.5, seeds 1-6 x 150 steps: all 5 transient starts of a bundle above the active start level threw
 * type 0 on jbosgi, type 10 on Equinox and Felix (e.g. seed 6 step 62).
 *
 * <h3>Spec reference</h3>
 * Bundle.start(int) javadoc (OSGi Core R8, 10.1.5.36,
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.start-int-): "If
 * the current start level is less than this bundle's start level: If the START_TRANSIENT option is set, then a
 * BundleException is thrown indicating this bundle cannot be started due to the Framework's current start level."
 * and "@throws BundleException ... BundleException types thrown by this method include: START_TRANSIENT_ERROR, ...".
 * BundleException.START_TRANSIENT_ERROR: "The start transient operation failed because the start level of the bundle
 * is greater than the current framework start level" (since 1.5).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/FrameworkMessages.java:212-213}
 * ({@code @Message(id = 11249, ...) BundleException cannotStartBundleDueToStartLevel();} - no type, so
 * UNSPECIFIED), thrown at {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:771-772}
 * and {@code core/src/main/java/org/jboss/osgi/framework/spi/AbstractBundleAdaptor.java:260-261}. Pre-existing
 * (2c04f383, 2013); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The message method creates the BundleException with the default type.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=StartTransientErrorTypeIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testStartTransientAboveStartLevel: BundleException type of a transient start above the framework start level
 *   expected:&lt;10&gt; but was:&lt;0&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * BundleException with getType() == BundleException.START_TRANSIENT_ERROR.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: type 10 (START_TRANSIENT_ERROR).
 *
 * <h3>Suggested fix</h3>
 * Create the exception with the type, e.g. {@code new BundleException(msg, BundleException.START_TRANSIENT_ERROR)}
 * (jboss-logging: a message method returning a BundleException built with {@code @Param int type}).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class StartTransientErrorTypeIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testStartTransientAboveStartLevel() throws Exception {
        Bundle bundle = installBundle(getBundle("start-transient-error"));
        try {
            int frameworkLevel = getFramework().adapt(FrameworkStartLevel.class).getStartLevel();
            bundle.adapt(BundleStartLevel.class).setStartLevel(frameworkLevel + 1);
            try {
                bundle.start(Bundle.START_TRANSIENT);
                fail("A transient start above the framework start level must fail");
            } catch (BundleException ex) {
                assertEquals("BundleException type of a transient start above the framework start level", BundleException.START_TRANSIENT_ERROR, ex.getType());
            }
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive getBundle(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                return builder.openStream();
            }
        });
        return archive;
    }
}
