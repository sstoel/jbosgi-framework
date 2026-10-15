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
import static org.junit.Assert.fail;

import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.bundle.CollisionHook;
import org.osgi.framework.hooks.weaving.WeavingHook;

/**
 * A CollisionHook or WeavingHook service that the framework cannot obtain (BundleContext.getService returns null: its
 * ServiceFactory returned null, or the service was unregistered between lookup and use) is called anyway:
 * installBundle throws NullPointerException, and the class load fails with ClassFormatError (the null "hook" is
 * treated like a hook that threw and is deny-listed).
 *
 * <h3>Severity</h3>
 * minor - needs a hook service that cannot be obtained, i.e. a misbehaving ServiceFactory or a hook bundle that is
 * being stopped while bundles are installed / classes are loaded (a plain race in a running system); the result is an
 * undeclared NullPointerException from installBundle, or a ClassFormatError for an ordinary class of an unrelated
 * bundle.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin#checkUniqunessPolicy} and
 * {@code org.jboss.osgi.framework.internal.WeavingContext} (constructor).
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, targets 1 and 3): hook robustness probe {@code fa.ProbeNullHook} (Collision,
 * Find, Event, Weaving hooks and ResolverHookFactory registered as a ServiceFactory returning null) against Equinox
 * 3.21 and Felix 7.0.5: jbosgi fails the install (NullPointerException) and the class load (ClassFormatError);
 * Equinox and Felix ignore the hook (Felix fires the ServiceException ERROR event as jbosgi does) and the operations
 * succeed. Find and Event hooks are skipped by jbosgi as well (their NullPointerException is caught and logged).
 *
 * <h3>Spec reference</h3>
 * BundleContext.getService javadoc (OSGi Core R8): "This method will always return null when the service associated
 * with the specified reference has been unregistered." and "If the service object returned by the ServiceFactory
 * object is null, ... null is returned and a Framework event of type FrameworkEvent.ERROR containing a
 * ServiceException describing the error is fired."<br>
 * BundleContext.installBundle javadoc: "@throws BundleException If the installation failed. BundleException types
 * thrown by this method include: READ_ERROR, DUPLICATE_BUNDLE_ERROR, MANIFEST_ERROR, and REJECTED_BY_HOOK." (no
 * unchecked exception for a collision).<br>
 * 56.3.2 Error Handling: the deny listing and the ClassFormatError apply "In the case that a weaving hook throws an
 * unexpected exception" - a hook that could not be obtained has not been called and has not thrown.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:762-763}
 * ({@code CollisionHook hook = syscontext.getService(sref); hook.filterCollisions(...)} - no null check, no catch);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/WeavingContext.java:109-110}
 * ({@code weavingHooks.add(new HookRegistration(sref, hook))} with {@code hook == null}), then
 * {@code WeavingHookProcessor.java:80} ({@code hook.weave(wovenClass)} throws NullPointerException, handled as a hook
 * failure). Pre-existing (ad958ae2 / c9f92f3b, 2013); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The result of getService for hook services is used without a null check.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=UnobtainableHookServiceIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testUnobtainableCollisionHook: A collision with an unobtainable CollisionHook must fail with
 *   BundleException(DUPLICATE_BUNDLE_ERROR), but installBundle threw java.lang.NullPointerException: Cannot invoke
 *   "org.osgi.framework.hooks.bundle.CollisionHook.filterCollisions(int, org.osgi.framework.Bundle,
 *   java.util.Collection)" because "hook" is null
 * testUnobtainableWeavingHook: Loading a class while an unobtainable WeavingHook is registered must work, but failed
 *   with java.lang.ClassFormatError: Failed to link org/jboss/test/osgi/framework/classloader/support/a/A (...):
 *   Cannot invoke "org.osgi.framework.hooks.weaving.WeavingHook.weave(...)" because "hook" is null
 * </pre>
 *
 * <h3>Expected</h3>
 * Hooks that cannot be obtained are skipped: the collision is reported as BundleException DUPLICATE_BUNDLE_ERROR (no
 * other hook removed the candidate), the class loads normally.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: BundleException for the collision ("A bundle is already installed with the name..."
 * / "Bundle symbolic name and version are not unique"), the class loads.
 *
 * <h3>Suggested fix</h3>
 * Skip a hook whose getService returned null in checkUniqunessPolicy and in the WeavingContext constructor (as
 * AbstractBundleContext.callFindHooks/FrameworkEventsImpl effectively do by catching the failure).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class UnobtainableHookServiceIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUnobtainableCollisionHook() throws Exception {
        ServiceRegistration<?> reg = getSystemContext().registerService(CollisionHook.class.getName(), new NullFactory<CollisionHook>(), null);
        Bundle first = installBundle(getBundle("unobtainable-collision-1", "unobtainable.collision", false));
        Bundle second = null;
        try {
            try {
                second = installBundle(getBundle("unobtainable-collision-2", "unobtainable.collision", false));
                fail("The second bundle with the same symbolic name and version must not install (bsnversion=managed)");
            } catch (BundleException ex) {
                assertEquals(BundleException.DUPLICATE_BUNDLE_ERROR, ex.getType());
            } catch (RuntimeException ex) {
                fail("A collision with an unobtainable CollisionHook must fail with BundleException(DUPLICATE_BUNDLE_ERROR), but installBundle threw " + ex);
            }
        } finally {
            reg.unregister();
            if (second != null)
                second.uninstall();
            first.uninstall();
        }
    }

    @Test
    public void testUnobtainableWeavingHook() throws Exception {
        ServiceRegistration<?> reg = getSystemContext().registerService(WeavingHook.class.getName(), new NullFactory<WeavingHook>(), null);
        Bundle bundle = installBundle(getBundle("unobtainable-weaving", "unobtainable.weaving", true));
        try {
            Throwable failure = null;
            try {
                bundle.loadClass(A.class.getName());
            } catch (Throwable th) {
                failure = th;
            }
            assertNull("Loading a class while an unobtainable WeavingHook is registered must work, but failed with " + failure, failure);
        } finally {
            reg.unregister();
            bundle.uninstall();
        }
    }

    static class NullFactory<T> implements ServiceFactory<T> {
        public T getService(Bundle bundle, ServiceRegistration<T> registration) {
            return null;
        }

        public void ungetService(Bundle bundle, ServiceRegistration<T> registration, T service) {
        }
    }

    private JavaArchive getBundle(String name, final String symbolicName, boolean withClass) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (withClass)
            archive.addClasses(A.class);
        archive.setManifest(new Asset() {
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
