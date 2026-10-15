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

import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Framework.getDataFile throws IllegalStateException when the framework is not running (INSTALLED, or RESOLVED
 * after stop), although Bundle.getDataFile may only throw it for an uninstalled bundle.
 *
 * <h2>Severity</h2>
 * minor (edge-case spec deviation: a launcher that looks up a data file of the system bundle before init or after
 * stop gets an exception instead of a File or null).
 *
 * <h2>Component</h2>
 * jbosgi-framework, module core, class org.jboss.osgi.framework.internal.FrameworkProxy (the {@link Framework}
 * object returned by the FrameworkFactory).
 *
 * <h2>Found by</h2>
 * Tests workstream, coverage audit: FrameworkProxy.getDataFile is new in r8 (it threw UnsupportedOperationException
 * on WF41) and no test calls it. Equinox / Felix differential (see below).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, org.osgi.framework.Bundle.getDataFile(String) javadoc
 * (https://docs.osgi.org/javadoc/osgi.core/8.0.0/org/osgi/framework/Bundle.html#getDataFile-java.lang.String-):
 * "Creates a File object for a file in the persistent storage area provided for this bundle by the Framework.
 * This method will return null if the platform does not have file system support or this bundle is a fragment
 * bundle. ... Throws: IllegalStateException - If this bundle has been uninstalled."
 * Framework (org.osgi.framework.launch.Framework) does not override this contract, and the system bundle can never
 * be uninstalled (Core R8 4.6: "The system bundle ... cannot be uninstalled").
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java:448-451 ({@code getDataFile} calls
 * {@code assertNotStopped()}, :436-439, which throws when {@code bundleManager == null} (before init) or the
 * framework has stopped). Introduced by commit 5ceeef0b "Validate the syntax of the manifest headers, implement the
 * missing Bundle methods". The other read-only methods of FrameworkProxy (getHeaders, getResource, getEntry,
 * findEntries, loadClass, ... :348-418) share the same guard; that part is pre-existing and not covered here.
 *
 * <h2>Root cause</h2>
 * The new method copies the guard of the other FrameworkProxy delegations, which treat "framework not running" as
 * an illegal state. The storage area of the system bundle (FRAMEWORK_STORAGE/bundle-0) does not depend on the
 * framework running.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=FrameworkDataFileStateIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full only works around issue tests/01, LoggerImplementationMissingIssueTestCase.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testDataFileBeforeInit: Expected a File or null from getDataFile of a framework in state INSTALLED, but it threw
 *   java.lang.IllegalStateException: JBOSGI011221: Framework already stopped
 * testDataFileAfterStop: Expected a File or null from getDataFile of a stopped framework (state RESOLVED), but it
 *   threw java.lang.IllegalStateException: JBOSGI011221: Framework already stopped
 * </pre>
 *
 * <h2>Expected</h2>
 * getDataFile returns the File in the storage area of the system bundle (as it does while the framework is
 * STARTING/ACTIVE), or null; it does not throw.
 *
 * <h2>Reference behaviour</h2>
 * Equinox 3.21.0: returns {@code <storage>/org.eclipse.osgi/0/data/x.txt} in INSTALLED, STARTING, ACTIVE and after
 * stop (RESOLVED). Felix 7.0.5: returns null in INSTALLED and after stop, the File while STARTING/ACTIVE. Neither
 * throws.
 *
 * <h2>Suggested fix</h2>
 * In FrameworkProxy.getDataFile do not call assertNotStopped(). While the framework runs, delegate to the system
 * bundle as now; otherwise build the File from the FRAMEWORK_STORAGE property of the FrameworkBuilder
 * ({@code new File(new File(storage, "bundle-0"), filename)}, the layout of StorageManagerImpl), or return null
 * before init as Felix does. Note that bundleManager.getFrameworkState() cannot be used after stop. Checked: with that
 * change both test methods pass (JDK 25).
 *
 * <h2>Regression vs WF41</h2>
 * no (WF41 threw UnsupportedOperationException in every state; r8 fixed the running states only).
 */
public class FrameworkDataFileStateIssueTestCase {

    private Framework framework;

    private Framework newFramework() {
        Map<String, String> props = new HashMap<String, String>();
        File storage = new File("target/issues-datafile-storage");
        props.put(Constants.FRAMEWORK_STORAGE, storage.getAbsolutePath());
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

    @Test
    public void testDataFileBeforeInit() throws Exception {
        Framework fw = newFramework();
        try {
            fw.getDataFile("data.txt");
        } catch (IllegalStateException ex) {
            fail("Expected a File or null from getDataFile of a framework in state INSTALLED, but it threw " + ex);
        }
    }

    @Test
    public void testDataFileAfterStop() throws Exception {
        Framework fw = newFramework();
        fw.start();
        if (fw.getDataFile("data.txt") == null)
            fail("Expected a File from getDataFile of an active framework, but it returned null");
        fw.stop();
        fw.waitForStop(10000);
        try {
            fw.getDataFile("data.txt");
        } catch (IllegalStateException ex) {
            fail("Expected a File or null from getDataFile of a stopped framework (state RESOLVED), but it threw " + ex);
        }
    }
}
