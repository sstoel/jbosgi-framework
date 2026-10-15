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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * registerService and setProperties write the framework properties (service.id, objectClass, service.bundleid,
 * service.scope) into the Dictionary passed by the caller, so a read-only Dictionary such as one from the R8
 * FrameworkUtil.asDictionary(Map) fails with UnsupportedOperationException, and a case variant of one of these keys
 * makes the call fail with IllegalArgumentException instead of being ignored.
 *
 * <h3>Severity</h3>
 * major. {@code context.registerService(Foo.class, foo, FrameworkUtil.asDictionary(Map.of("key", "value")))} is the
 * idiom that R8 added {@code asDictionary} for ("can be helpful when working with service properties"); it throws
 * UnsupportedOperationException on this framework. Registration with a case variant of a framework key
 * (e.g. properties copied from elsewhere with "objectclass") throws IllegalArgumentException although the
 * specification says such properties are ignored. As a side effect the caller's own Hashtable is modified.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.ServiceStateImpl (constructor and setProperties)
 *
 * <h3>Found by</h3>
 * services workstream, code review of the R7/R8 service property handling, confirmed with an Equinox / Felix
 * differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 5.2.3 Registering Services (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.service.html):
 * "The methods asMap(Dictionary) and asDictionary(Map) can be helpful when working with service properties to
 * provide Map views over Dictionaries and Dictionary views over Maps."
 * BundleContext.registerService(String[], Object, Dictionary): "The Framework adds the following service properties
 * to the service properties from the specified Dictionary (which may be null): ... service.id ... objectClass ...
 * service.scope ... service.bundleid. Properties with these names in the specified Dictionary will be ignored."
 * and "@throws IllegalArgumentException If ... properties contains case variants of the same key name."
 * ServiceRegistration.setProperties(Dictionary): "The objectClass, service.bundleid, service.id and service.scope
 * keys cannot be modified by this method." ServiceReference.getProperty: "Property keys are case-insensitive."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:108-112 (constructor) and
 * :419-423 (setProperties): {@code properties.put(Constants.SERVICE_ID, ...)} etc. on the caller's Dictionary,
 * then {@code new CaseInsensitiveDictionary(properties)}. Present since commit 4049e658 (2010), already on WF41;
 * the service.bundleid / service.scope lines were added for R6.
 *
 * <h3>Root cause</h3>
 * The framework properties are put into the caller's Dictionary before it is copied. With a read-only Dictionary
 * the put throws UnsupportedOperationException. The put is case-sensitive, so a caller key "Service.Id" or
 * "OBJECTCLASS" stays next to the added "service.id" / "objectClass", and the copy into the
 * CaseInsensitiveDictionary then rejects the two case variants with IllegalArgumentException
 * ("Duplicates with varying case for key ...").
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ServicePropertiesWrittenIntoCallerDictionaryIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testRegisterWithAsDictionary: Registering with FrameworkUtil.asDictionary(map) must work, but got
 *   java.lang.UnsupportedOperationException
 * testSetPropertiesWithAsDictionary: setProperties with FrameworkUtil.asDictionary(map) must work, but got
 *   java.lang.UnsupportedOperationException
 * testCaseVariantOfFrameworkKeyIgnoredOnRegister: A case variant of a framework property must be ignored, but got
 *   java.lang.IllegalArgumentException: Duplicates with varying case for key [OBJECTCLASS] : {...}
 * testCaseVariantOfFrameworkKeyIgnoredOnSetProperties: (same IllegalArgumentException)
 * testCallerDictionaryNotModified: The caller's dictionary must keep its only key
 *   expected:&lt;[a]&gt; but was:&lt;[service.id, objectClass, a, service.scope, service.bundleid]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The framework copies the caller's properties into its own case-insensitive dictionary, drops every key that is
 * a case variant of objectClass / service.id / service.bundleid / service.scope, and sets these four itself. The
 * caller's Dictionary is only read.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5: registration and setProperties with asDictionary(singletonMap) work, the case
 * variants "Service.Id" / "OBJECTCLASS" are ignored (service.id and objectClass have the framework values), and the
 * caller's Hashtable still has only its own key.
 *
 * <h3>Suggested fix</h3>
 * Build the new properties in a fresh case-insensitive map: copy the caller's entries, skipping keys that equal one
 * of the four framework keys ignoring case (and rejecting real case-variant duplicates among the caller's own keys
 * with IllegalArgumentException), then add the framework values; create the CaseInsensitiveDictionary from that
 * copy. Do the same in setProperties.
 *
 * <h3>Regression vs WF41</h3>
 * no for the mechanism (present since 2010); FrameworkUtil.asDictionary only exists since R8, so the
 * asDictionary failure is new for code written against this R8 framework.
 */
public class ServicePropertiesWrittenIntoCallerDictionaryIssueTestCase extends OSGiFrameworkTest {

    static class TestService implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    public void testRegisterWithAsDictionary() throws Exception {
        BundleContext context = getSystemContext();
        Dictionary<String, Object> props = FrameworkUtil.asDictionary(Collections.<String, Object> singletonMap("key", "value"));
        ServiceRegistration<Runnable> sreg = null;
        try {
            sreg = context.registerService(Runnable.class, new TestService(), props);
        } catch (RuntimeException ex) {
            fail("Registering with FrameworkUtil.asDictionary(map) must work, but got " + ex);
        }
        try {
            assertEquals("The property of the map", "value", sreg.getReference().getProperty("key"));
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testSetPropertiesWithAsDictionary() throws Exception {
        BundleContext context = getSystemContext();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), null);
        try {
            try {
                sreg.setProperties(FrameworkUtil.asDictionary(Collections.<String, Object> singletonMap("key", "value")));
            } catch (RuntimeException ex) {
                fail("setProperties with FrameworkUtil.asDictionary(map) must work, but got " + ex);
            }
            assertEquals("The property of the map", "value", sreg.getReference().getProperty("key"));
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testCaseVariantOfFrameworkKeyIgnoredOnRegister() throws Exception {
        BundleContext context = getSystemContext();
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("OBJECTCLASS", "bogus");
        props.put("Service.Id", Long.valueOf(-99));
        ServiceRegistration<Runnable> sreg = null;
        try {
            sreg = context.registerService(Runnable.class, new TestService(), props);
        } catch (IllegalArgumentException ex) {
            fail("A case variant of a framework property must be ignored, but got " + ex);
        }
        try {
            assertArrayEquals("objectClass is set by the framework", new String[] { Runnable.class.getName() },
                    (String[]) sreg.getReference().getProperty(Constants.OBJECTCLASS));
            assertNotEquals("service.id is set by the framework", Long.valueOf(-99), sreg.getReference().getProperty(Constants.SERVICE_ID));
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testCaseVariantOfFrameworkKeyIgnoredOnSetProperties() throws Exception {
        BundleContext context = getSystemContext();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), null);
        Object serviceId = sreg.getReference().getProperty(Constants.SERVICE_ID);
        try {
            Hashtable<String, Object> props = new Hashtable<String, Object>();
            props.put("Service.Id", Long.valueOf(-99));
            try {
                sreg.setProperties(props);
            } catch (IllegalArgumentException ex) {
                fail("A case variant of a framework property must be ignored, but got " + ex);
            }
            assertEquals("service.id cannot be modified", serviceId, sreg.getReference().getProperty(Constants.SERVICE_ID));
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testCallerDictionaryNotModified() throws Exception {
        BundleContext context = getSystemContext();
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("a", "b");
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), props);
        try {
            assertEquals("The caller's dictionary must keep its only key", Collections.singleton("a"), props.keySet());
        } finally {
            sreg.unregister();
        }
    }
}
