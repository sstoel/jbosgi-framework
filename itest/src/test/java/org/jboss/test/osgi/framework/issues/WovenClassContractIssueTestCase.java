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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.hooks.weaving.WovenClassListener;

/**
 * WovenClass does not implement its API contract: after the TRANSFORMING state getBytes() hands out the array that is
 * then used to define the class (a listener writing into it changes or breaks the defined class), setBytes(null) is
 * accepted, and the dynamic import list accepts malformed clauses.
 *
 * <h3>Severity</h3>
 * minor - the class bytes are the framework's own data after weaving is complete: a WovenClassListener (for
 * example an auditing or byte-code analysing listener that scrambles or reuses the array it got) silently changes
 * the class that the framework defines, or makes it fail with ClassFormatError; the failure is reported for the
 * woven bundle, far away from the listener that caused it. setBytes(null) and malformed dynamic import clauses are
 * accepted silently and only fail the class load later (with an exception that names neither the hook nor the
 * clause), instead of failing the weaving hook call that made the mistake.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.WeavingContext.ContextClass} (the WovenClass
 * implementation) and {@code WeavingHookProcessor#addDynamicWeavingImports}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 3): seeded stateful weaving-hook differential {@code fa.WeaveModel}
 * (weaving hooks and WovenClassListeners registered/unregistered at random, hooks re-weaving byte code generated
 * with the JDK ClassFile API, adding dynamic imports, throwing, re-entrant class loads; class loads, refresh,
 * update, retained-WovenClass probes) against Equinox 3.21 and Felix 7.0.5, seeds 1, 2, 3 (250 steps) with the
 * contract checks enabled. First divergences: seed 3 step 14 (a listener mutating the TRANSFORMED bytes: Equinox
 * defines the class unchanged, jbosgi fails with NoClassDefFoundError/DEFINE_FAILED), seed 3 step 81 (setBytes(null):
 * Equinox throws NullPointerException, jbosgi accepts it and the class load fails with NullPointerException), seed 1
 * step 162 (malformed dynamic import clause added: Felix throws IllegalArgumentException from add, jbosgi accepts it,
 * the class load then fails with ClassFormatError, and the next class load with the same clause succeeds and
 * silently drops it).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 56.3 Weaving Hook (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.weavinghooks.html):
 * "After calling the last Weaving Hook service, the WovenClass object is made complete. The framework must make the
 * WovenClass object immutable when it is complete. [...] After completion, the getBytes() method must return a copy
 * of the byte array that was used to define the class."<br>
 * 56.6.4.7 WovenClass.getBytes(): "While in the TRANSFORMING state, this method returns a reference to the class
 * files byte array contained in this object. After leaving the TRANSFORMING state, this woven class can no longer be
 * transformed and a copy of the class file byte array is returned."<br>
 * 56.6.4.14 WovenClass.setBytes(byte[]): "Throws NullPointerException - If newBytes is null."<br>
 * 56.6.4.10 WovenClass.getDynamicImports(): "This list must throw IllegalArgumentException if a malformed dynamic
 * import package description is added." and 56.3: "The dynamic imports must have a valid syntax, otherwise an
 * Illegal Argument Exception must be thrown."
 *
 * <h3>Code location</h3>
 * (The listener in the test zeroes the whole array: zeroing only the magic number is repaired by the JaCoCo agent
 * that the itest module runs with, which re-instruments the class from its own parse.)<br>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/WeavingContext.java}:
 * {@code :168-170} getBytes() always returns {@code classfileBuffer}; {@code :225-230} freeze() copies the array
 * once and keeps returning that single copy, which {@code WeavingHookProcessor.transform} (:105, {@code return
 * wovenClass.getBytes()}) also returns to jboss-modules for the define. {@code :173-176} setBytes() has no null
 * check. {@code :64} / {@code :179-181} the dynamic import list is a plain ArrayList; the clauses are parsed only in
 * {@code WeavingHookProcessor.addDynamicWeavingImports} (:108-136) after the TRANSFORMED notification, where the
 * IllegalArgumentException fails the class load; a clause that failed is already in {@code processedImports}, so
 * the next woven class with the same clause loads and the clause is dropped. Pre-existing: b294ba91 (2013,
 * WeavingHookTransformer), freeze()/states from 992ee69a (on WF41); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * ContextClass returns its internal array instead of a copy once frozen, does not validate the setBytes argument,
 * and exposes an unvalidated ArrayList as the dynamic import list.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=WovenClassContractIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testBytesAfterTransformingAreACopy: Writing into the array returned by getBytes() in the TRANSFORMED state must
 *   not change the class that is defined, but the class load failed with java.lang.ClassFormatError: Failed to link
 *   org/jboss/test/osgi/framework/classloader/support/a/A (Module "jbosgi.woven-bytes-copy:0.0.0.bid2rev0" from
 *   FrameworkModuleLoaderImpl): Incompatible magic value 0 in class file org/jboss/test/osgi/framework/classloader/support/a/A
 * testSetBytesNullThrowsNullPointerException: WovenClass.setBytes(null) must throw NullPointerException
 *   expected:&lt;[NullPointerException]&gt; but was:&lt;[no exception]&gt;
 * testMalformedDynamicImportRejected: Adding the malformed clause 'org.acme.foo;version="[1,"' to
 *   getDynamicImports() must throw IllegalArgumentException expected:&lt;[IllegalArgumentException]&gt; but
 *   was:&lt;[no exception]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * getBytes() returns a fresh copy after TRANSFORMING; writing into it has no effect on the defined class.
 * setBytes(null) throws NullPointerException. Adding a malformed clause to the dynamic import list throws
 * IllegalArgumentException from the list method, inside the weaving hook.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: getBytes() returns a new copy after TRANSFORMING (the class is defined unchanged); setBytes(null)
 * throws NullPointerException; malformed clauses are accepted by the list (same deviation as jbosgi) but every
 * class load with such a clause fails consistently with ClassFormatError and a FrameworkEvent.ERROR.
 * Felix 7.0.5: getBytes() copy only in the DEFINED state (the TRANSFORMED mutation breaks the define, as in jbosgi);
 * setBytes(null) accepted; malformed clauses rejected with IllegalArgumentException from add/addAll.
 *
 * <h3>Suggested fix</h3>
 * In ContextClass: {@code getBytes()} returns {@code frozen ? classfileBuffer.clone() : classfileBuffer}, and the
 * processor defines the class from the internal array (not from a getBytes() result);
 * {@code setBytes} throws NullPointerException for null before the frozen check; the dynamic import list is a
 * small wrapper whose add/addAll/set parse the clause (as {@code addDynamicWeavingImports} does now) and throw
 * IllegalArgumentException; drop the failed clause from {@code processedImports}.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class WovenClassContractIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testBytesAfterTransformingAreACopy() throws Exception {
        WeavingHook hook = new WeavingHook() {
            public void weave(WovenClass wovenClass) {
            }
        };
        final List<String> sameArray = new ArrayList<String>();
        WovenClassListener listener = new WovenClassListener() {
            public void modified(WovenClass wovenClass) {
                if (A.class.getName().equals(wovenClass.getClassName()) && wovenClass.getState() == WovenClass.TRANSFORMED) {
                    byte[] bytes = wovenClass.getBytes();
                    if (bytes == wovenClass.getBytes())
                        sameArray.add("same array returned twice");
                    // A listener may do what it likes with its copy
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        };
        ServiceRegistration<WeavingHook> hookReg = getSystemContext().registerService(WeavingHook.class, hook, null);
        ServiceRegistration<WovenClassListener> listenerReg = getSystemContext().registerService(WovenClassListener.class, listener, null);
        Bundle bundle = installBundle(getBundleArchive("woven-bytes-copy"));
        try {
            Throwable failure = null;
            try {
                bundle.loadClass(A.class.getName());
            } catch (Throwable th) {
                failure = th;
            }
            assertNull("Writing into the array returned by getBytes() in the TRANSFORMED state must not change the class that is defined, but the class load failed with " + failure, failure);
            assertEquals("getBytes() after TRANSFORMING must return a new copy on every call", new ArrayList<String>(), sameArray);
        } finally {
            bundle.uninstall();
            listenerReg.unregister();
            hookReg.unregister();
        }
    }

    @Test
    public void testSetBytesNullThrowsNullPointerException() throws Exception {
        final List<String> result = new ArrayList<String>();
        WeavingHook hook = new WeavingHook() {
            public void weave(WovenClass wovenClass) {
                if (A.class.getName().equals(wovenClass.getClassName())) {
                    try {
                        wovenClass.setBytes(null);
                        result.add("no exception");
                    } catch (NullPointerException ex) {
                        result.add("NullPointerException");
                    }
                }
            }
        };
        ServiceRegistration<WeavingHook> hookReg = getSystemContext().registerService(WeavingHook.class, hook, null);
        Bundle bundle = installBundle(getBundleArchive("woven-setbytes-null"));
        try {
            try {
                bundle.loadClass(A.class.getName());
            } catch (Throwable th) {
                // jbosgi: the null bytes fail the define
            }
            assertEquals("WovenClass.setBytes(null) must throw NullPointerException", "[NullPointerException]", result.toString());
        } finally {
            bundle.uninstall();
            hookReg.unregister();
        }
    }

    @Test
    public void testMalformedDynamicImportRejected() throws Exception {
        final String malformed = "org.acme.foo;version=\"[1,\"";
        final List<String> result = new ArrayList<String>();
        WeavingHook hook = new WeavingHook() {
            public void weave(WovenClass wovenClass) {
                if (A.class.getName().equals(wovenClass.getClassName())) {
                    try {
                        wovenClass.getDynamicImports().add(malformed);
                        result.add("no exception");
                    } catch (IllegalArgumentException ex) {
                        result.add("IllegalArgumentException");
                    }
                }
            }
        };
        ServiceRegistration<WeavingHook> hookReg = getSystemContext().registerService(WeavingHook.class, hook, null);
        Bundle bundle = installBundle(getBundleArchive("woven-malformed-import"));
        try {
            try {
                bundle.loadClass(A.class.getName());
            } catch (Throwable th) {
                // jbosgi: the clause is parsed after the hooks and fails the class load
            }
            assertEquals("Adding the malformed clause '" + malformed + "' to getDynamicImports() must throw IllegalArgumentException", "[IllegalArgumentException]", result.toString());
        } finally {
            bundle.uninstall();
            hookReg.unregister();
        }
    }

    private JavaArchive getBundleArchive(String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(A.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        return archive;
    }
}
