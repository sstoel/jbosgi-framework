package org.jboss.osgi.framework.internal;
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

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jboss.modules.ClassSpec;
import org.jboss.modules.PackageSpec;
import org.jboss.modules.Resource;
import org.jboss.modules.ResourceLoader;
import org.jboss.osgi.framework.spi.NativeLibraryProvider;

/**
 * @author thomas.diesler@jboss.com
 * @since 29-Jun-2010
 */
final class NativeResourceLoader implements ResourceLoader {

    // List of native library providers, by the file name of the library
    private volatile List<NativeLibraryProvider> nativeLibraries;
    // The extra library file extensions of org.osgi.framework.library.extensions
    private final List<String> libraryExtensions;

    NativeResourceLoader(List<String> libraryExtensions) {
        this.libraryExtensions = libraryExtensions;
    }

    void addNativeLibrary(NativeLibraryProvider libProvider) {
        if (nativeLibraries == null)
            nativeLibraries = new CopyOnWriteArrayList<NativeLibraryProvider>();

        nativeLibraries.add(libProvider);
    }

    @Override
    public String getLibrary(String libname) {
        List<NativeLibraryProvider> list = nativeLibraries;
        if (list == null)
            return null;

        // The library name is mapped with System.mapLibraryName and the configured extensions, the first library of the
        // selected clause with such a file name is used
        Set<String> fileNames = getLibraryFileNames(libname);
        for (NativeLibraryProvider libProvider : list) {
            if (fileNames.contains(libProvider.getLibraryName()) == false)
                continue;
            File libfile;
            try {
                libfile = libProvider.getLibraryLocation();
            } catch (IOException ex) {
                LOGGER.errorCannotProvideNativeLibraryLocation(ex, libname);
                return null;
            }
            if (libfile != null)
                return libfile.getAbsolutePath();
        }
        return null;
    }

    private Set<String> getLibraryFileNames(String libname) {
        Set<String> result = new LinkedHashSet<String>();
        String mapped = System.mapLibraryName(libname);
        result.add(mapped);
        int dot = mapped.lastIndexOf('.');
        String base = dot > 0 ? mapped.substring(0, dot) : mapped;
        // On macOS a JNI library is a .dylib or, as it was named before Java 7, a .jnilib (as in Felix)
        if (mapped.endsWith(".dylib"))
            result.add(base + ".jnilib");
        for (String extension : libraryExtensions) {
            String ext = extension.trim();
            if (ext.startsWith("."))
                ext = ext.substring(1);
            if (ext.isEmpty() == false)
                result.add(base + "." + ext);
        }
        return result;
    }

    @Override
    public String getRootName() {
        return "undefined";
    }

    @Override
    public ClassSpec getClassSpec(String name) throws IOException {
        return null;
    }

    @Override
    public PackageSpec getPackageSpec(String name) throws IOException {
        return null;
    }

    @Override
    public Resource getResource(String name) {
        return null;
    }

    @Override
    public Collection<String> getPaths() {
        return Collections.emptyList();
    }
}
