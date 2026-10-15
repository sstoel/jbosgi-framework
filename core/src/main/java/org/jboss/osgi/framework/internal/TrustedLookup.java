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
package org.jboss.osgi.framework.internal;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * The trusted method handle lookup of the JVM. The framework uses it for the few JDK internals it needs without
 * --add-opens: appending framework extensions to the class path of the application class loader, and finding the URL
 * stream handler factory of the JVM. The reference implementation does the same.
 */
final class TrustedLookup {

    private TrustedLookup() {
    }

    static MethodHandles.Lookup get() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, implLookup);
        long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implLookup);
        return (MethodHandles.Lookup) unsafeClass.getMethod("getObject", Object.class, long.class).invoke(unsafe, base, offset);
    }
}
