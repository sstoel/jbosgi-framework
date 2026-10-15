package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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

import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * A ServiceReference or Bundle of another framework instance is accepted by BundleContext.getService/ungetService/
 * getServiceObjects, ServiceReference.compareTo and ServiceReference.isAssignableTo, although their javadoc requires
 * an IllegalArgumentException: getService even returns the other framework's service object.
 *
 * <h3>Severity</h3>
 * minor. Only matters when several framework instances run in one JVM (embedding, tests). There a reference that
 * leaks from one framework gives access to the other framework's service and creates use counts in the wrong
 * framework; compareTo returns 0 for two different services that happen to have the same service.id.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.AbstractBundleContext (getService, ungetService,
 * getServiceObjects), ServiceStateImpl (compareTo, isAssignableTo), ServiceReferenceComparator
 *
 * <h3>Found by</h3>
 * services workstream round 2 (foreign framework objects), Equinox / Felix differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 javadoc (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html):
 * BundleContext.getService / ungetService / getServiceObjects: "@throws IllegalArgumentException If the specified
 * ServiceReference was not created by the same framework instance as this BundleContext."
 * ServiceReference.compareTo: "@throws IllegalArgumentException If the specified ServiceReference was not created by
 * the same framework instance as this ServiceReference."
 * ServiceReference.isAssignableTo: "@throws IllegalArgumentException If the specified Bundle was not created by the
 * same framework instance as this ServiceReference."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:120-126 ({@code assertServiceState}
 * only unwraps), :501-508 (isAssignableTo), :626-633 (compareTo); AbstractBundleContext getService/ungetService/
 * getServiceObjects use {@code assertServiceState} without an owner check. Original code, already on WF41.
 *
 * <h3>Root cause</h3>
 * Both framework instances use the same implementation classes, so the instanceof/cast checks pass; nothing
 * compares the ServiceManager / BundleManager of the reference or bundle with the one of the receiver.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ForeignServiceReferenceIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testGetServiceWithForeignReference: getService must reject a reference of another framework, but returned ...TestService@...
 * testCompareToWithForeignReference: compareTo must reject a reference of another framework, but returned 0
 * testIsAssignableToWithForeignBundle: isAssignableTo must reject a bundle of another framework, but returned true
 * </pre>
 *
 * <h3>Expected</h3>
 * IllegalArgumentException in all three cases.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5 behave like JBoss OSGi here (no IllegalArgumentException; getService returns the
 * foreign object, compareTo 0, isAssignableTo true), so this is a deviation that the reference implementations
 * share; the javadoc is explicit.
 *
 * <h3>Suggested fix</h3>
 * Give ServiceStateImpl a reference to its ServiceManager (it has one) and let assertServiceState take the expected
 * manager and throw IllegalArgumentException on a mismatch; for bundles compare the BundleManager of the XBundle.
 *
 * <h3>Regression vs WF41</h3>
 * no (original code).
 */
public class ForeignServiceReferenceIssueTestCase extends OSGiTest {

    static class TestService implements Runnable {
        @Override
        public void run() {
        }
    }

    private Framework first;
    private Framework second;

    @Before
    public void setUp() throws Exception {
        first = newFramework("first");
        second = newFramework("second");
    }

    @After
    public void tearDown() throws Exception {
        for (Framework framework : new Framework[] { first, second }) {
            if (framework != null) {
                framework.stop();
                framework.waitForStop(10000);
            }
        }
    }

    private Framework newFramework(String name) throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/foreign-reference-test", name).getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        Framework framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        return framework;
    }

    @Test
    public void testGetServiceWithForeignReference() throws Exception {
        BundleContext context = first.getBundleContext();
        ServiceRegistration<Runnable> foreign = second.getBundleContext().registerService(Runnable.class, new TestService(), null);
        Object result;
        try {
            result = context.getService(foreign.getReference());
        } catch (IllegalArgumentException ex) {
            return;
        }
        fail("getService must reject a reference of another framework, but returned " + result);
    }

    @Test
    public void testCompareToWithForeignReference() throws Exception {
        ServiceRegistration<Runnable> own = first.getBundleContext().registerService(Runnable.class, new TestService(), null);
        ServiceRegistration<Runnable> foreign = second.getBundleContext().registerService(Runnable.class, new TestService(), null);
        ServiceReference<Runnable> ref = own.getReference();
        int result;
        try {
            result = ref.compareTo(foreign.getReference());
        } catch (IllegalArgumentException ex) {
            return;
        }
        fail("compareTo must reject a reference of another framework, but returned " + result);
    }

    @Test
    public void testIsAssignableToWithForeignBundle() throws Exception {
        ServiceRegistration<Runnable> own = first.getBundleContext().registerService(Runnable.class, new TestService(), null);
        boolean result;
        try {
            result = own.getReference().isAssignableTo(second.getBundleContext().getBundle(), Runnable.class.getName());
        } catch (IllegalArgumentException ex) {
            return;
        }
        fail("isAssignableTo must reject a bundle of another framework, but returned " + result);
    }
}
