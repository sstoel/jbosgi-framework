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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.log.LogService;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

/**
 * A Logger obtained with {@code LoggerFactory.getLogger(Bundle, String, Class)} for another bundle is cached by the
 * requesting LoggerFactory forever, so the other bundle stays in memory after it is uninstalled.
 *
 * <h3>Severity</h3>
 * major - unbounded memory leak in the running server for every DS bundle: Felix SCR (ScrLogManager) asks its
 * LoggerFactory for a logger of each DS bundle it manages; every redeployed DS bundle stays referenced by SCR's
 * LoggerFactory instance.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.log.LogServiceImpl} ({@code loggers}), new in the R8
 * branch (Log Service 1.5 in the framework).
 *
 * <h3>Found by</h3>
 * wildfly workstream, round 2 of the redeploy leak analysis (after wildfly/06): HPROF root path
 * {@code UserBundleState <- .bundle log.LoggerImpl <- ConcurrentHashMap .loggers <- LogServiceImpl <- ...
 * org.apache.felix.scr.impl.logger.ScrLogManager}.
 * In the server (patched WildFly 41.0.1.Final + jbosgi R8 overlay, Docker eclipse-temurin:25-jdk, with trial patches for
 * resolver/19 and resolver/20 applied so that the other leaks are out of the way) 10 redeploys ({@code deploy --force})
 * of each smoke bundle retained:
 * smoke.ds 10 of 10 together with the ServiceFactory holder of ServiceFactoryUserRetainedIssueTestCase; with both trial
 * fixes 0.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 101 Log Service, LoggerFactory.getLogger(Bundle, String, Class) javadoc: "bundle - The bundle associated
 * with the Logger ... Throws IllegalArgumentException - If the specified Bundle is not a resolved bundle." - a logger is
 * tied to a resolved bundle; an uninstalled bundle has no loggers. BundleWiring.isInUse: stale wirings are discarded.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/service.log.html
 *
 * <h3>Code location</h3>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/log/LogServiceImpl.java:43 ({@code loggers}),
 * :71-93 (getLogger(Bundle, ...) and getLoggerInternal cache a LoggerImpl per bundle id and name, never removed);
 * introduced by 3680cfcc "Implement OSGi Core R8".
 *
 * <h3>Root cause</h3>
 * The logger cache of a LoggerFactory instance is keyed by bundle id and is not cleaned when the logged bundle is
 * uninstalled.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=LoggerForOtherBundleRetainedIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests -Dmaven.compiler.proc=full}).
 * The bundles of the test import nothing, so the provided-wire leak (wildfly/06, resolver/19) and the
 * initialRevision retention (resolver/20) do not interfere: the test fails for this root cause alone.
 *
 * <h3>Observed</h3>
 * <pre>
 * testLoggedBundleCollectable: Expected an uninstalled and refreshed bundle for which another bundle got a Logger to be
 *   garbage collectable, but it is still reachable
 * </pre>
 *
 * <h3>Expected</h3>
 * The loggers of a bundle are dropped when it is uninstalled (or held weakly), so the bundle is collectable.
 *
 * <h3>Reference behaviour</h3>
 * Not checked in a test (Equinox keeps loggers in per-bundle LoggerContextTargetMap entries removed on uninstall).
 *
 * <h3>Suggested fix</h3>
 * Let LogServices track BUNDLE UNINSTALLED events and remove the loggers of that bundle id from all LogServiceImpl
 * instances (or key the cache weakly by Bundle). Verified with a trial patch (LogServices keeps its LogServiceImpl
 * instances and a SynchronousBundleListener removes the loggers of an UNINSTALLED bundle): the test passes; in the
 * server the DS redeploy loop retains nothing.
 *
 * <h3>Regression vs WF41</h3>
 * yes - the framework Log Service (and this cache) is new on the R8 branch; WF41 used the Felix Log Service 1.2.6,
 * which has no Logger API.
 *
 * @since 13-Oct-2026
 */
public class LoggerForOtherBundleRetainedIssueTestCase extends AbstractFrameworkLaunchTest {

    private Framework framework;

    @Before
    public void setUp() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
    }

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.ACTIVE | Bundle.STARTING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    /** A bundle without Import-Package, Require-* and classes: it has no wires, so the provided-wire leak (resolver/19) cannot hold it */
    private Bundle installPlainBundle(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return framework.getBundleContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }

    private void uninstallAndRefresh(Bundle bundle) throws Exception {
        bundle.uninstall();
        final CountDownLatch latch = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(bundle), new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        assertTrue("Refresh completes", latch.await(10, TimeUnit.SECONDS));
    }

    /** The Log Service keeps the last 100 entries, which reference the bundles; push them out, then GC */
    private boolean isCollected(WeakReference<?> ref) throws Exception {
        BundleContext syscontext = framework.getBundleContext();
        ServiceReference<LogService> sref = syscontext.getServiceReference(LogService.class);
        LogService log = syscontext.getService(sref);
        for (int i = 0; i < 300; i++)
            log.log(LogService.LOG_INFO, "filler " + i);
        syscontext.ungetService(sref);
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return ref.get() == null;
    }

    @Test
    public void testLoggedBundleCollectable() throws Exception {
        Bundle bundle = installPlainBundle("logged-bundle");
        bundle.start();
        BundleContext syscontext = framework.getBundleContext();
        ServiceReference<LoggerFactory> sref = syscontext.getServiceReference(LoggerFactory.class);
        LoggerFactory factory = syscontext.getService(sref);
        factory.getLogger(bundle, "component.logger", Logger.class).info("hello");
        bundle.stop();
        uninstallAndRefresh(bundle);
        WeakReference<Bundle> ref = new WeakReference<Bundle>(bundle);
        bundle = null;
        assertTrue("Expected an uninstalled and refreshed bundle for which another bundle got a Logger to be garbage collectable, but it is still reachable",
                isCollected(ref));
        syscontext.ungetService(sref);
    }
}
