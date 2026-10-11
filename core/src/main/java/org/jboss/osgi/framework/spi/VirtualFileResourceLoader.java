package org.jboss.osgi.framework.spi;

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

import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jboss.modules.ClassSpec;
import org.jboss.modules.IterableResourceLoader;
import org.jboss.modules.PackageSpec;
import org.jboss.modules.Resource;
import org.jboss.modules.ResourceLoader;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;

/**
 * An {@link ResourceLoader} that is backed by a {@link VirtualFile} pointing to an archive.
 *
 * @author thomas.diesler@jboss.com
 * @author <a href="david@redhat.com">David Bosschaert</a>
 * @since 29-Jun-2010
 */
public final class VirtualFileResourceLoader implements IterableResourceLoader {

    private static final String META_INF = "META-INF";
    private static final String VERSIONS_PATH = META_INF + "/versions";

    private final VirtualFile virtualFile;
    private final List<VirtualFile> versionedRoots;
    private final boolean multiRelease;
    private final Set<String> localPaths;

    public VirtualFileResourceLoader(VirtualFile virtualFile) {
        this(virtualFile, Collections.<VirtualFile> emptyList(), false);
    }

    /**
     * Create a resource loader for a multi-release container.
     *
     * @param virtualFile The root of the container
     * @param versionedRoots The versioned directories that apply to the running Java platform, highest version first
     * @param multiRelease True if the versioned directories of the container are not part of its content
     */
    public VirtualFileResourceLoader(VirtualFile virtualFile, List<VirtualFile> versionedRoots, boolean multiRelease) {
        if (virtualFile == null)
            throw MESSAGES.illegalArgumentNull("virtualFile");
        if (versionedRoots == null)
            throw MESSAGES.illegalArgumentNull("versionedRoots");
        this.virtualFile = virtualFile;
        this.versionedRoots = versionedRoots;
        this.multiRelease = multiRelease;
        this.localPaths = getLocalPaths();
    }

    /**
     * Find a child of the container. The versioned directories take precedence, except for the META-INF directory.
     */
    private VirtualFile findChild(String path) throws IOException {
        if (isVersionedPath(path)) {
            for (VirtualFile versionedRoot : versionedRoots) {
                VirtualFile child = versionedRoot.getChild(path);
                if (child != null)
                    return child;
            }
        }
        return virtualFile.getChild(path);
    }

    private static boolean isVersionedPath(String path) {
        while (path.startsWith("/"))
            path = path.substring(1);
        return !(path.equals(META_INF) || path.startsWith(META_INF + "/"));
    }

    private static String getRelativePath(VirtualFile root, VirtualFile descendant) {
        String path = descendant.getPathName().substring(root.getPathName().length());
        return path.startsWith("/") ? path.substring(1) : path;
    }

    @Override
    public String getRootName() {
        return virtualFile.getPathName();
    }

    @Override
    public ClassSpec getClassSpec(String fileName) throws IOException {
        VirtualFile child = findChild(fileName);
        if (child == null)
            return null;

        ClassSpec classSpec = new ClassSpec();
        InputStream is = child.openStream();
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
            VFSUtils.copyStream(is, os);
            classSpec.setBytes(os.toByteArray());
        } finally {
            safeClose(is);
        }

        CodeSigner[] codeSigners = child.getCodeSigners();
        classSpec.setCodeSource(new CodeSource(new URL("jar", null, -1, child.getName()), codeSigners));

        return classSpec;
    }

    @Override
    public PackageSpec getPackageSpec(String name) throws IOException {
        PackageSpec spec = new PackageSpec();
        Manifest manifest = VFSUtils.getManifest(virtualFile);
        if (manifest == null) {
            return spec;
        }
        Attributes mainAttribute = manifest.getAttributes(name);
        Attributes entryAttribute = manifest.getAttributes(name);
        spec.setSpecTitle(getDefinedAttribute(Attributes.Name.SPECIFICATION_TITLE, entryAttribute, mainAttribute));
        spec.setSpecVersion(getDefinedAttribute(Attributes.Name.SPECIFICATION_VERSION, entryAttribute, mainAttribute));
        spec.setSpecVendor(getDefinedAttribute(Attributes.Name.SPECIFICATION_VENDOR, entryAttribute, mainAttribute));
        spec.setImplTitle(getDefinedAttribute(Attributes.Name.IMPLEMENTATION_TITLE, entryAttribute, mainAttribute));
        spec.setImplVersion(getDefinedAttribute(Attributes.Name.IMPLEMENTATION_VERSION, entryAttribute, mainAttribute));
        spec.setImplVendor(getDefinedAttribute(Attributes.Name.IMPLEMENTATION_VENDOR, entryAttribute, mainAttribute));
        if (Boolean.parseBoolean(getDefinedAttribute(Attributes.Name.SEALED, entryAttribute, mainAttribute))) {
            spec.setSealBase(virtualFile.toURL());
        }
        return spec;
    }

    private static String getDefinedAttribute(Attributes.Name name, Attributes entryAttribute, Attributes mainAttribute) {
        final String value = entryAttribute == null ? null : entryAttribute.getValue(name);
        return value == null ? mainAttribute == null ? null : mainAttribute.getValue(name) : value;
    }

    @Override
    public Resource getResource(String name) {
        try {
            VirtualFile child = findChild(name);
            if (child == null)
                return null;

            return new VirtualFileResource(child, name.startsWith("/") ? name.substring(1) : name);
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public String getLibrary(String name) {
        return null;
    }

    @Override
    public Collection<String> getPaths() {
        return localPaths;
    }

    @Override
    public Iterator<Resource> iterateResources(String startPath, boolean recurse) {
        // An entry in a versioned directory hides the entry with the same path in the container
        Map<String, Resource> result = new LinkedHashMap<String, Resource>();
        try {
            if (isVersionedPath(startPath)) {
                for (VirtualFile versionedRoot : versionedRoots) {
                    collectResources(versionedRoot, startPath, recurse, result);
                }
            }
            collectResources(virtualFile, startPath, recurse, result);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return result.values().iterator();
    }

    private void collectResources(VirtualFile root, String startPath, boolean recurse, Map<String, Resource> result) throws IOException {
        VirtualFile parent = root.getChild(startPath);
        if (parent == null)
            return;

        boolean versioned = root != virtualFile;
        List<VirtualFile> entryPaths = recurse ? parent.getChildrenRecursively() : parent.getChildren();
        for (VirtualFile entry : entryPaths) {
            try {
                if (entry.isFile()) {
                    String name = getRelativePath(root, entry);
                    if (versioned ? isVersionedPath(name) == false : isHiddenPath(name))
                        continue;
                    if (result.containsKey(name) == false)
                        result.put(name, new VirtualFileResource(entry, name));
                }
            } catch (IOException ex) {
                throw MESSAGES.illegalArgumentCannotObtainPaths(ex, virtualFile);
            }
        }
    }

    // The versioned directories are not part of the content of a multi-release container
    private boolean isHiddenPath(String path) {
        return multiRelease && path.startsWith(VERSIONS_PATH + "/");
    }

    private Set<String> getLocalPaths() {
        Set<String> result = new HashSet<String>();
        try {
            collectLocalPaths(virtualFile, result);
            for (VirtualFile versionedRoot : versionedRoots) {
                collectLocalPaths(versionedRoot, result);
            }
        } catch (IOException ex) {
            throw MESSAGES.illegalArgumentCannotObtainPaths(ex, virtualFile);
        }
        if (result.size() == 0)
            throw MESSAGES.illegalArgumentCannotObtainPaths(null, virtualFile);

        return Collections.unmodifiableSet(result);
    }

    private void collectLocalPaths(VirtualFile root, Set<String> result) throws IOException {
        boolean versioned = root != virtualFile;
        for (VirtualFile descendant : root.getChildrenRecursively()) {
            if (descendant.isFile()) {
                String entryPath = getRelativePath(root, descendant);
                if (versioned ? isVersionedPath(entryPath) == false : isHiddenPath(entryPath))
                    continue;

                int inx = entryPath.lastIndexOf("/");
                result.add(inx > 0 ? entryPath.substring(0, inx) : "");
            }
        }
    }

    private void safeClose(final Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    class VirtualFileResource implements Resource {

        final VirtualFile child;
        final String name;

        VirtualFileResource(VirtualFile child, String name) {
            assert child != null : "Null child";
            this.child = child;
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public URL getURL() {
            try {
                return child.toURL();
            } catch (IOException ex) {
                throw MESSAGES.illegalStateCannotObtainURL(child);
            }
        }

        @Override
        public InputStream openStream() throws IOException {
            return child.openStream();
        }

        @Override
        public long getSize() {
            return 0;
        }
    }
}
