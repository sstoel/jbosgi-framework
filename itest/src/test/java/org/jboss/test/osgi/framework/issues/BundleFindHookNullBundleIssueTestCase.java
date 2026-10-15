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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.bundle.FindHook;

/**
 * BundleContext.getBundle(long) for an id that does not exist calls the bundle FindHooks with a collection that
 * contains a null element.
 *
 * <h3>Severity</h3>
 * minor - a find hook iterating the candidates (the normal way to write one, e.g.
 * {@code bundles.removeIf(b -> hidden(b.getSymbolicName()))}) throws NullPointerException; the framework logs a
 * warning per lookup and skips that hook for the call. Any code that probes bundle ids (bundle trackers after an
 * uninstall, management consoles, JMX {@code getBundle(id)} loops) floods the log with hook failures.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.AbstractBundleContext#getBundle(long)}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 1): {@code fa.BundleHookModel} (all bsnversion modes, seeds 1-4)
 * against Equinox 3.21 and Felix 7.0.5: every {@code getBundle(id)} of a missing id while a FindHook is registered
 * logged {@code find ctx=... in=[null]} on jbosgi only; Equinox and Felix do not call the hooks (nothing to filter).
 * Confirmed with the probe {@code fa.ProbeBundleHooks}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 54.7.4.1 FindHook.find(BundleContext, Collection&lt;Bundle&gt;)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.bundlehooks.html): "bundles - A collection of
 * Bundles to be returned as a result of the find operation." - getBundle(long) of an unknown id returns null, there
 * is no Bundle to return, so the collection must not contain null (and there is nothing to filter).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleContext.java:241-250}:
 * {@code Bundle result = getBundleManager().getBundleById(id); Collection<Bundle> hookBundles = new
 * RemoveOnlyCollection<Bundle>(result); callFindHooks(hookBundles);} - the varargs constructor turns the null result
 * into a one-element list {@code [null]}. Pre-existing (55e034a9); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * No null check of the lookup result before building the candidate collection.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=BundleFindHookNullBundleIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testFindHookNotCalledWithNull: The bundle FindHook must never get a null element (getBundle of a missing id)
 *   expected:&lt;[]&gt; but was:&lt;[[null]]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * getBundle(missing id) returns null without calling the hooks (Equinox, Felix), or calls them with an empty
 * collection.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: hooks not called, getBundle returns null.
 *
 * <h3>Suggested fix</h3>
 * {@code if (result == null) return null;} before the hook call in getBundle(long).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class BundleFindHookNullBundleIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFindHookNotCalledWithNull() throws Exception {
        final List<String> nullCalls = new ArrayList<String>();
        ServiceRegistration<FindHook> reg = getSystemContext().registerService(FindHook.class, new FindHook() {
            public void find(BundleContext context, Collection<Bundle> bundles) {
                if (bundles.contains(null))
                    nullCalls.add(bundles.toString());
            }
        }, null);
        try {
            assertNull(getSystemContext().getBundle(Long.MAX_VALUE));
        } finally {
            reg.unregister();
        }
        assertEquals("The bundle FindHook must never get a null element (getBundle of a missing id)", "[]", nullCalls.toString());
    }
}
