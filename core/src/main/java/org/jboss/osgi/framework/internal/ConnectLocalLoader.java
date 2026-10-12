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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.jboss.modules.LocalLoader;
import org.jboss.modules.Resource;

/**
 * Loads the classes and resources of a connect bundle from the class loader that its content provides.
 */
final class ConnectLocalLoader implements LocalLoader {

    private final ClassLoader classLoader;

    ConnectLocalLoader(ClassLoader classLoader) {
        assert classLoader != null : "Null classLoader";
        this.classLoader = classLoader;
    }

    @Override
    public Class<?> loadClassLocal(String name, boolean resolve) {
        // The class is not initialized here, a class that cannot be linked is reported and not taken for a missing class
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    @Override
    public Package loadPackageLocal(String name) {
        return classLoader.getDefinedPackage(name);
    }

    @Override
    public List<Resource> loadResourceLocal(String name) {
        // A class loader does not find a resource by a name that starts with a slash
        name = ConnectVirtualFile.normalize(name);
        Enumeration<URL> urls;
        try {
            urls = classLoader.getResources(name);
        } catch (IOException ex) {
            return Collections.emptyList();
        }
        List<Resource> result = new ArrayList<Resource>();
        while (urls != null && urls.hasMoreElements())
            result.add(new URLResource(name, urls.nextElement()));

        // Not every class loader finds with getResources what it finds with getResource
        if (result.isEmpty()) {
            URL url = classLoader.getResource(name);
            if (url != null)
                result.add(new URLResource(name, url));
        }
        return result;
    }

    /**
     * A loader that falls back to this loader for what the given loader does not find. The connect class loader is
     * not limited to the entries of the connect content.
     */
    LocalLoader after(final LocalLoader first) {
        return new LocalLoader() {
            @Override
            public Class<?> loadClassLocal(String name, boolean resolve) {
                Class<?> result = first.loadClassLocal(name, resolve);
                return result != null ? result : ConnectLocalLoader.this.loadClassLocal(name, resolve);
            }

            @Override
            public Package loadPackageLocal(String name) {
                return ConnectLocalLoader.this.loadPackageLocal(name);
            }

            @Override
            public List<Resource> loadResourceLocal(String name) {
                List<Resource> result = first.loadResourceLocal(name);
                return result != null && result.isEmpty() == false ? result : ConnectLocalLoader.this.loadResourceLocal(name);
            }
        };
    }

    private static final class URLResource implements Resource {

        private final String name;
        private final URL url;

        URLResource(String name, URL url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public URL getURL() {
            return url;
        }

        @Override
        public InputStream openStream() throws IOException {
            return url.openStream();
        }

        @Override
        public long getSize() {
            try {
                URLConnection connection = url.openConnection();
                return connection.getContentLengthLong();
            } catch (IOException ex) {
                return 0;
            }
        }
    }
}
