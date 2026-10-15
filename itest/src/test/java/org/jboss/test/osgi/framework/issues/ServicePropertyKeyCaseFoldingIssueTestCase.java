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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Dictionary;
import java.util.Hashtable;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * Service property keys are compared case-insensitively by lower-casing the whole key with Locale.ENGLISH instead
 * of the per-character String.equalsIgnoreCase used by the Filter reference implementation, so for keys with
 * characters like the Greek capital sigma or the Turkish dotted capital I the service registry disagrees with
 * Filter.match(Dictionary): a property is not found under a case variant of its key, a filter does not select the
 * service, and two case variants of the same key are accepted.
 *
 * <h3>Severity</h3>
 * minor - only non-ASCII property keys are affected (ASCII keys behave the same in both definitions).
 *
 * <h3>Component</h3>
 * jbosgi-metadata, {@code org.jboss.osgi.metadata.CaseInsensitiveDictionary} (used by jbosgi-framework core
 * {@code ServiceStateImpl} for the service properties).
 *
 * <h3>Found by</h3>
 * fuzz workstream: seeded differential of service registration / getProperty / getServiceReferences against
 * Equinox 3.21 ({@code ServiceDiff}, 2000 cases, keys drawn from a pool with Unicode case-folding traps). 1541
 * getProperty and 176 filter results differed, all on keys with Σ/σ/ς, İ, ı or ſ.
 *
 * <h3>Spec reference</h3>
 * ServiceReference.getProperty javadoc (OSGi Core R8, 10.1.29.5): "Property keys are case-insensitive."
 * BundleContext.registerService javadoc (10.1.7.21): "Throws IllegalArgumentException - ... or if the properties
 * contain case variants of the same key name." Filter.match(Dictionary) javadoc (10.1.16.4):
 * "The keys are looked up in a case insensitive manner." - the osgi.core 8.0.0 reference implementation
 * ({@code org.osgi.framework.FilterImpl.CaseInsensitiveMap}) defines that as {@code String.equalsIgnoreCase}.
 *
 * <h3>Code location</h3>
 * jbosgi-metadata {@code src/main/java/org/jboss/osgi/metadata/CaseInsensitiveDictionary.java:76} (constructor:
 * {@code key.toLowerCase(Locale.ENGLISH)}) and {@code :113} ({@code get}). {@code "ΑΣ".toLowerCase()} is
 * {@code "ας"} (final sigma) but {@code "ασ"} stays as is; {@code "İd".toLowerCase()} is the
 * three-character {@code "i̇d"}. Old code (also on WF41); the R8 branch reworked this class (commits 20fb3dd,
 * efe88ce) without changing the folding.
 *
 * <h3>Root cause</h3>
 * Case-insensitivity implemented as equality of {@code toLowerCase(Locale.ENGLISH)} of the whole string, which is
 * neither symmetric with {@code equalsIgnoreCase} nor stable for context-sensitive/length-changing mappings.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ServicePropertyKeyCaseFoldingIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testGetPropertyWithCaseVariant: getProperty("\u03b1\u03c3") must find the property registered as "\u0391\u03a3"
 *   expected:&lt;value&gt; but was:&lt;null&gt;
 * testFilterMatchesLikeDictionaryMatch: getServiceReferences((&amp;(objectClass=...$Marker)(\u03b1\u03c3=value))) must select
 *   the service with property \u0391\u03a3=value
 * testCaseVariantKeysRejected: registerService must throw IllegalArgumentException for the case variants \u0130d and id
 * </pre>
 *
 * <h3>Expected</h3>
 * The registry uses the same case-insensitive key matching as Filter.match(Dictionary): the property is found under
 * any case variant, the filter selects the service, and registering two case variants fails with an
 * IllegalArgumentException.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 behaves as expected in all three tests.
 *
 * <h3>Suggested fix</h3>
 * Store the entries in a {@code TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER)} (as the filter
 * implementation does) instead of a Hashtable keyed by the lower-cased key.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 13-Oct-2026
 */
public class ServicePropertyKeyCaseFoldingIssueTestCase extends OSGiFrameworkTest {

    // Greek capital alpha + capital sigma, and the same in small letters (non-final sigma)
    private static final String UPPER = "ΑΣ";
    private static final String LOWER = "ασ";

    @Test
    public void testGetPropertyWithCaseVariant() throws Exception {
        assertTrue("Precondition: the keys are case variants per String.equalsIgnoreCase", UPPER.equalsIgnoreCase(LOWER));
        ServiceRegistration<?> reg = register(UPPER, "value");
        try {
            assertEquals("getProperty(\"" + LOWER + "\") must find the property registered as \"" + UPPER + "\"", "value",
                    reg.getReference().getProperty(LOWER));
        } finally {
            reg.unregister();
        }
    }

    @Test
    public void testFilterMatchesLikeDictionaryMatch() throws Exception {
        String filter = "(&(objectClass=" + Marker.class.getName() + ")(" + LOWER + "=value))";
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(UPPER, "value");
        assertTrue("Precondition: Filter.match(Dictionary) of the reference implementation selects these properties",
                FrameworkUtil.createFilter("(" + LOWER + "=value)").match(props));
        ServiceRegistration<?> reg = register(UPPER, "value");
        try {
            BundleContext context = getSystemContext();
            ServiceReference<?>[] refs = context.getServiceReferences((String) null, filter);
            assertNotNull("getServiceReferences(" + filter + ") must select the service with property " + UPPER + "=value", refs);
            assertTrue("Filter.match(ServiceReference) must agree with Filter.match(Dictionary)", context.createFilter(filter).match(reg.getReference()));
        } finally {
            reg.unregister();
        }
    }

    @Test
    public void testCaseVariantKeysRejected() throws Exception {
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put("İd", "one");
        props.put("id", "two");
        assertTrue("Precondition: the keys are case variants per String.equalsIgnoreCase", "İd".equalsIgnoreCase("id"));
        ServiceRegistration<?> reg;
        try {
            reg = getSystemContext().registerService(Marker.class.getName(), new Marker(), props);
        } catch (IllegalArgumentException ex) {
            return;
        }
        reg.unregister();
        fail("registerService must throw IllegalArgumentException for the case variants İd and id");
    }

    private ServiceRegistration<?> register(String key, Object value) throws Exception {
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(key, value);
        return getSystemContext().registerService(Marker.class.getName(), new Marker(), props);
    }

    public static class Marker {
    }
}
