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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * When several threads uninstall the same bundle at the same time, every call returns normally: the calls that run
 * after the bundle has been uninstalled return silently instead of throwing IllegalStateException.
 *
 * <h3>Severity</h3>
 * minor - concurrent callers cannot tell which of them uninstalled the bundle; the API contract of Bundle.uninstall is
 * violated under concurrency.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState#uninstallInternal}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful multi-threaded API fuzzer (event anomalies "UNRESOLVED after UNINSTALLED" on worker
 * threads), minimized to concurrent uninstall calls; compared with Equinox 3.21 and WF41.
 *
 * <h3>Spec reference</h3>
 * Bundle.uninstall javadoc (OSGi Core R8, 10.1.5.37): "The following steps are required to uninstall a bundle: If
 * this bundle's state is UNINSTALLED then an IllegalStateException is thrown." and "Throws IllegalStateException - If
 * this bundle has been uninstalled or this bundle tries to change its own state."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleState.java:538-541}
 * checks {@code assertNotUninstalled()} before {@code BundleManagerPlugin.uninstallBundle} takes the UNINSTALL lock;
 * once the lock is obtained, {@code UserBundleState.java:445-447} returns silently
 * ({@code if (state == Bundle.UNINSTALLED) return;}) for the threads that lost the race.
 *
 * <h3>Root cause</h3>
 * The UNINSTALLED check that throws is done outside the lock; the check under the lock swallows the condition.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ConcurrentUninstallIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testOnlyOneConcurrentUninstallSucceeds: Exactly one of 4 concurrent uninstall calls may return, results:
 *   [returned, returned, returned, returned] expected:&lt;1&gt; but was:&lt;4&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Exactly one of the concurrent uninstall calls returns, the others throw IllegalStateException.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: exactly one call returns, the others throw IllegalStateException (200 of 200 rounds).
 *
 * <h3>Suggested fix</h3>
 * Throw {@code MESSAGES.illegalStateBundleAlreadyUninstalled(this)} in uninstallInternal when the state under the
 * lock is UNINSTALLED.
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class ConcurrentUninstallIssueTestCase extends OSGiFrameworkTest {

    private static final int THREADS = 4;

    @Test
    public void testOnlyOneConcurrentUninstallSucceeds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < 10; round++) {
                final Bundle bundle = install("concurrent-uninstall-" + round);
                final CyclicBarrier barrier = new CyclicBarrier(THREADS);
                List<Future<String>> futures = new ArrayList<Future<String>>();
                for (int i = 0; i < THREADS; i++) {
                    futures.add(executor.submit(new Callable<String>() {
                        public String call() throws Exception {
                            barrier.await();
                            try {
                                bundle.uninstall();
                                return "returned";
                            } catch (IllegalStateException ex) {
                                return "IllegalStateException";
                            }
                        }
                    }));
                }
                List<String> results = new ArrayList<String>();
                int returned = 0;
                for (Future<String> future : futures) {
                    String result = future.get();
                    results.add(result);
                    if (result.equals("returned"))
                        returned++;
                }
                assertEquals("Exactly one of " + THREADS + " concurrent uninstall calls may return, results: " + results, 1, returned);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Bundle install(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
