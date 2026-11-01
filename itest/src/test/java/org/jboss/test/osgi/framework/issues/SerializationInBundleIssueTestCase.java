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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.issues.serial.SerializableBean;
import org.junit.Test;
import org.osgi.framework.Bundle;

/**
 * With strict boot delegation (the default) an object of a class from a bundle cannot be serialized on JDK 21.
 *
 * <h3>Severity</h3>
 * major - every in-framework Arquillian test fails on JDK 21 (the JMX test runner serializes the test result), as do
 * bundles that serialize their own objects, e.g. for RMI, JMX or a session.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkModuleLoaderImpl}.
 *
 * <h3>Found by</h3>
 * WF42 upgrade (2026-11-01): the itests of jbosgi-repository, jbosgi-provision and the Arquillian tests of the
 * framework failed on JDK 21.0.11, they pass on JDK 23.
 *
 * <h3>Root cause</h3>
 * The serialization of JDK 21 generates a subclass of jdk.internal.reflect.SerializationConstructorAccessorImpl and
 * defines it in a class loader whose parent is the class loader of the serialized class. The strict boot delegation
 * removed the whole java.base dependency of a bundle module, so the superclass was not found. JDK 22 and later create
 * the constructor with method handles and do not define a class.
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.NoClassDefFoundError: jdk/internal/reflect/SerializationConstructorAccessorImpl
 *   at java.base/jdk.internal.reflect.MethodAccessorGenerator.generateSerializationConstructor(MethodAccessorGenerator.java:107)
 *   at java.base/java.io.ObjectStreamClass.getSerializableConstructor(ObjectStreamClass.java:1445)
 * </pre>
 *
 * <h3>Expected</h3>
 * The object is serialized, the rest of java.base stays hidden from the bundle (see
 * {@link JavaBasePackagesVisibleIssueTestCase}).
 *
 * <h3>Reference behaviour</h3>
 * Felix and Equinox delegate jdk.internal.reflect (sun.reflect before JDK 9) to the parent class loader implicitly.
 *
 * <h3>Regression vs WF41</h3>
 * yes - since commit 54b94267 "Fix the minor TCK issues" made the strict boot delegation the default.
 *
 * @since 01-Nov-2026
 */
public class SerializationInBundleIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testSerializeObjectOfBundleClass() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            Class<?> beanClass = bundle.loadClass(SerializableBean.class.getName());
            assertNotSame("Precondition: the class must be loaded by the bundle", SerializableBean.class, beanClass);
            Object bean = beanClass.getDeclaredConstructor().newInstance();

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(bean);
            } catch (NoClassDefFoundError err) {
                fail("An object of a bundle class must be serializable: " + err);
            }

            Object copy;
            try (ObjectInputStream in = new BundleObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()), bundle)) {
                copy = in.readObject();
            }
            assertEquals(beanClass, copy.getClass());
            assertEquals("bundle", beanClass.getMethod("getValue").invoke(copy));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testJdkInternalReflectResourceNotVisible() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            assertNull("Only the classes of jdk.internal.reflect are visible to a bundle, not its resources",
                    bundle.getResource("jdk/internal/reflect/SerializationConstructorAccessorImpl.class"));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive createBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "serialization-in-bundle");
        archive.addClasses(SerializableBean.class);
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

    private static class BundleObjectInputStream extends ObjectInputStream {

        private final Bundle bundle;

        BundleObjectInputStream(InputStream in, Bundle bundle) throws IOException {
            super(in);
            this.bundle = bundle;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            return bundle.loadClass(desc.getName());
        }
    }
}
