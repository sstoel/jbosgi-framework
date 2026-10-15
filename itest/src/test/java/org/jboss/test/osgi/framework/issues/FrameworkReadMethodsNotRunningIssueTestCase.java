/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.security.AllPermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;

import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * The read-only Bundle methods of the Framework object (getHeaders, getEntry, getResource, loadClass, findEntries,
 * ...) throw IllegalStateException "Framework already stopped" before init() and after stop(), although the system
 * bundle is never uninstalled.
 *
 * <h2>Severity</h2>
 * minor (spec deviation on the launcher-facing Framework object: a launcher that reads the framework's headers,
 * version information from its manifest, or an entry/resource before init or after stop gets an exception;
 * Equinox and Felix answer these calls in every state).
 *
 * <h2>Component</h2>
 * jbosgi-framework, module core, class org.jboss.osgi.framework.internal.FrameworkProxy (the {@link Framework}
 * returned by the FrameworkFactory).
 *
 * <h2>Found by</h2>
 * Tests workstream: follow-up of tests/02 (FrameworkDataFileStateIssueTestCase, Framework.getDataFile), which has
 * the same root cause on a method that is new in r8. Method-by-method probe of jbosgi, Equinox 3.21.0 and
 * Felix 7.0.5 in the states INSTALLED (before init) and RESOLVED (after stop).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, org.osgi.framework.Bundle javadoc (https://docs.osgi.org/javadoc/osgi.core/8.0.0/org/osgi/framework/Bundle.html):
 * <ul>
 * <li>getHeaders() / getHeaders(String): no IllegalStateException at all; "This method must continue to return
 * Manifest header information while this bundle is in the UNINSTALLED state."</li>
 * <li>getLastModified(): no IllegalStateException.</li>
 * <li>getRegisteredServices, getServicesInUse, hasPermission, getResource, getResources, loadClass, getEntryPaths,
 * getEntry, findEntries: "Throws: IllegalStateException - If this bundle has been uninstalled." (only).</li>
 * </ul>
 * The system bundle cannot be uninstalled (Core R8 4.6 "The System Bundle"; Bundle.uninstall of the system bundle
 * throws BundleException). Framework (org.osgi.framework.launch.Framework) does not narrow these contracts.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java:348-416: every read method starts with
 * {@code assertNotStopped()} (:436-439), which throws {@code MESSAGES.illegalStateFrameworkAlreadyStopped()} when
 * {@code bundleManager == null} (before init) or {@code bundleManager.hasStopped()}. The guard dates from commit
 * 3c8ccc02 (2011) and is unchanged on WF41; r8 only added getDataFile with the same guard (tests/02).
 *
 * <h2>Root cause</h2>
 * FrameworkProxy delegates every Bundle method to the SystemBundleState of the running bundle manager and treats
 * "no running bundle manager" as an illegal state, instead of answering from data that does not need a running
 * framework (the system bundle headers, its class loader / content, its storage area).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=FrameworkReadMethodsNotRunningIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full only works around tests/01, LoggerImplementationMissingIssueTestCase.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testBeforeInit: Expected no IllegalStateException from the Bundle methods of a framework in state INSTALLED, but
 *   these threw it: [getHeaders(), getHeaders(String), getLastModified(), getRegisteredServices(), getServicesInUse(),
 *   hasPermission(Object), getResource(String), getResources(String), loadClass(String), getEntryPaths(String),
 *   getEntry(String), findEntries(String,String,boolean)] (JBOSGI011221: Framework already stopped)
 * testAfterStop: same list for a stopped framework (state RESOLVED)
 * </pre>
 *
 * <h2>Expected</h2>
 * No IllegalStateException from any of these methods while the system bundle is not uninstalled (which is never).
 * Returning null / an empty result for content that is not available, or ClassNotFoundException from loadClass, is
 * allowed.
 *
 * <h2>Reference behaviour</h2>
 * Same probe, before init and after stop:
 * Equinox 3.21.0: getHeaders 22 headers, getResource/getResources/getEntry/getEntryPaths/findEntries return results,
 * getLastModified a timestamp, getRegisteredServices/getServicesInUse null, hasPermission true, loadClass
 * ClassNotFoundException; no IllegalStateException.
 * Felix 7.0.5: getHeaders 7 headers, getResource/getResources/loadClass work, entries null, getLastModified 0,
 * hasPermission true; no IllegalStateException.
 *
 * <h2>Suggested fix</h2>
 * Do not call assertNotStopped() in the read methods of FrameworkProxy. Answer getHeaders from the framework's own
 * headers (they do not depend on a running framework; cache them on first init), getLastModified from the cached
 * value, getRegisteredServices/getServicesInUse with null, hasPermission with true, getResource/getResources/loadClass
 * through the framework implementation class loader, and the entry methods with null when the system bundle content
 * is not available. Keep this compatible with connect/16 (StoppedFrameworkRetainsClassLoadersIssueTestCase), which
 * asks to release the bundle manager after stop: cache only small, immutable data.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same guard since 2011).
 */
public class FrameworkReadMethodsNotRunningIssueTestCase {

    private Framework framework;

    private Framework newFramework() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-read-methods-storage").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        return framework = factory.newFramework(props);
    }

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    private static List<String> callAll(final Framework fw) {
        Map<String, Callable<?>> calls = new LinkedHashMap<String, Callable<?>>();
        calls.put("getHeaders()", () -> fw.getHeaders());
        calls.put("getHeaders(String)", () -> fw.getHeaders("en"));
        calls.put("getLastModified()", () -> fw.getLastModified());
        calls.put("getRegisteredServices()", () -> fw.getRegisteredServices());
        calls.put("getServicesInUse()", () -> fw.getServicesInUse());
        calls.put("hasPermission(Object)", () -> fw.hasPermission(new AllPermission()));
        calls.put("getResource(String)", () -> fw.getResource("org/osgi/framework/Bundle.class"));
        calls.put("getResources(String)", () -> fw.getResources("org/osgi/framework/Bundle.class"));
        calls.put("loadClass(String)", () -> fw.loadClass("org.osgi.framework.Bundle"));
        calls.put("getEntryPaths(String)", () -> fw.getEntryPaths("/"));
        calls.put("getEntry(String)", () -> fw.getEntry("/"));
        calls.put("findEntries(String,String,boolean)", () -> fw.findEntries("/", "*", false));
        List<String> failed = new ArrayList<String>();
        for (Map.Entry<String, Callable<?>> entry : calls.entrySet()) {
            try {
                entry.getValue().call();
            } catch (IllegalStateException ex) {
                failed.add(entry.getKey());
            } catch (Exception ex) {
                // allowed, e.g. ClassNotFoundException from loadClass
            }
        }
        return failed;
    }

    @Test
    public void testBeforeInit() throws Exception {
        Framework fw = newFramework();
        List<String> failed = callAll(fw);
        assertTrue("Expected no IllegalStateException from the Bundle methods of a framework in state INSTALLED, but these threw it: " + failed, failed.isEmpty());
    }

    @Test
    public void testAfterStop() throws Exception {
        Framework fw = newFramework();
        fw.start();
        assertTrue("Expected no IllegalStateException from an active framework: " + callAll(fw), callAll(fw).isEmpty());
        fw.stop();
        fw.waitForStop(10000);
        List<String> failed = callAll(fw);
        assertTrue("Expected no IllegalStateException from the Bundle methods of a stopped framework (state RESOLVED), but these threw it: " + failed, failed.isEmpty());
    }
}
