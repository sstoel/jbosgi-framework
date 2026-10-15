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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Pattern;

import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectContent.ConnectEntry;
import org.osgi.framework.connect.ConnectModule;

/**
 * The content of a connect bundle as a {@link VirtualFile}.
 *
 * The {@link ConnectContent} is opened when it is first accessed and it is closed with the root file.
 * When the content provides the bundle manifest headers, they are available as META-INF/MANIFEST.MF
 * instead of a manifest entry that the content may have.
 */
final class ConnectVirtualFile implements VirtualFile {

    static final String PROTOCOL_NAME = "connect";

    private final Root root;
    private final String path;
    private final boolean directory;

    /**
     * Get the root file for the content of the given module.
     *
     * The revisions of a bundle can have the same content, the open contents tell when it is opened and closed.
     */
    static ConnectVirtualFile create(String location, ConnectModule module, OpenContents openContents) throws IOException {
        ConnectContent content = module.getContent();
        if (content == null)
            throw new IOException("No content for connect module: " + location);
        return new Root(location, content, openContents).rootFile;
    }

    static boolean isConnectFile(VirtualFile file) {
        return file instanceof ConnectVirtualFile;
    }

    /**
     * The headers that the connect content of the given root file provides, or null when it provides none (the bundle
     * headers then come from the manifest entry of the content). They are the bundle's headers as they are (60.4.3)
     */
    static Map<String, String> getConnectHeaders(VirtualFile file) throws IOException {
        if (file instanceof ConnectVirtualFile == false)
            return null;
        ConnectVirtualFile connectFile = (ConnectVirtualFile) file;
        return connectFile == connectFile.root.rootFile ? connectFile.root.getHeaders() : null;
    }

    /**
     * Open the connect content, before it is used by the install
     */
    void openContent() throws IOException {
        root.open();
    }

    private ConnectVirtualFile(Root root, String path, boolean directory) {
        this.root = root;
        this.path = path;
        this.directory = directory;
    }

    /**
     * The class loader that the connect content provides for the bundle, or null when the framework
     * has to load the classes from the content.
     */
    ClassLoader getClassLoader() throws IOException {
        Optional<ClassLoader> loader = root.open().getClassLoader();
        return loader != null && loader.isPresent() ? loader.get() : null;
    }

    /**
     * The paths of the directories that have files in the content. The root directory is the empty path.
     */
    Set<String> getFilePaths() throws IOException {
        Set<String> result = new HashSet<String>();
        for (Map.Entry<String, ConnectVirtualFile> entry : root.getFiles().entrySet()) {
            if (entry.getValue().directory == false) {
                String filePath = entry.getKey();
                int index = filePath.lastIndexOf('/');
                result.add(index < 0 ? "" : filePath.substring(0, index));
            }
        }
        return result;
    }

    /**
     * The files that match the pattern, like {@link #findEntries(String, String, boolean)}
     */
    List<VirtualFile> findFiles(String basePath, String pattern, boolean recurse) throws IOException {
        ConnectVirtualFile base = root.rootFile.getChild(basePath);
        if (base == null || base.directory == false)
            return Collections.emptyList();

        Pattern regex = toRegex(pattern);
        List<VirtualFile> result = new ArrayList<VirtualFile>();
        for (VirtualFile child : recurse ? base.getChildrenRecursively() : base.getChildren()) {
            if (regex.matcher(child.getName()).matches())
                result.add(child);
        }
        return result;
    }

    private static Pattern toRegex(String pattern) {
        if (pattern == null)
            pattern = "*";
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (regex.length() > 0)
                regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    @Override
    public String getName() {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    @Override
    public String getPathName() {
        return path;
    }

    @Override
    public boolean isFile() {
        return directory == false;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public URL toURL() throws IOException {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) throws IOException {
                return new EntryConnection(url, ConnectVirtualFile.this);
            }
        };
        return new URL(PROTOCOL_NAME, root.host, -1, "/" + path + (directory && path.length() > 0 ? "/" : ""), handler);
    }

    @Override
    public URL getStreamURL() throws IOException {
        return toURL();
    }

    @Override
    public VirtualFile getParent() throws IOException {
        if (path.length() == 0)
            return null;
        int index = path.lastIndexOf('/');
        return index < 0 ? root.rootFile : root.rootFile.getChild(path.substring(0, index));
    }

    @Override
    public ConnectVirtualFile getChild(String childPath) throws IOException {
        if (childPath == null)
            return null;
        String fullPath = path.length() > 0 ? path + "/" + normalize(childPath) : normalize(childPath);
        if (fullPath.endsWith("/"))
            fullPath = fullPath.substring(0, fullPath.length() - 1);
        if (fullPath.length() == 0)
            return root.rootFile;
        Map<String, ConnectVirtualFile> files = root.getFiles();
        ConnectVirtualFile result = files.get(fullPath);
        if (result == null) {
            // A directory that the content has no entry for
            String prefix = fullPath + "/";
            for (String key : files.keySet()) {
                if (key.startsWith(prefix))
                    return new ConnectVirtualFile(root, fullPath, true);
            }
        }
        return result;
    }

    static String normalize(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/')
            start++;
        return path.substring(start);
    }

    @Override
    public List<VirtualFile> getChildrenRecursively() throws IOException {
        List<VirtualFile> result = new ArrayList<VirtualFile>();
        String prefix = path.length() > 0 ? path + "/" : "";
        for (Map.Entry<String, ConnectVirtualFile> entry : root.getFiles().entrySet()) {
            if (entry.getKey().startsWith(prefix))
                result.add(entry.getValue());
        }
        return result;
    }

    @Override
    public List<VirtualFile> getChildren() throws IOException {
        List<VirtualFile> result = new ArrayList<VirtualFile>();
        String prefix = path.length() > 0 ? path + "/" : "";
        for (Map.Entry<String, ConnectVirtualFile> entry : root.getFiles().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(prefix) && key.indexOf('/', prefix.length()) < 0)
                result.add(entry.getValue());
        }
        return result;
    }

    @Override
    public Enumeration<URL> findEntries(String basePath, String pattern, boolean recurse) throws IOException {
        Vector<URL> result = new Vector<URL>();
        for (VirtualFile file : findFiles(basePath, pattern, recurse))
            result.add(file.toURL());
        return result.elements();
    }

    @Override
    public Enumeration<String> getEntryPaths(String basePath) throws IOException {
        Vector<String> result = new Vector<String>();
        ConnectVirtualFile base = getChild(basePath);
        if (base != null && base.directory) {
            for (VirtualFile child : base.getChildren())
                result.add(child.getPathName() + (child.isDirectory() ? "/" : ""));
        }
        return result.elements();
    }

    @Override
    public InputStream openStream() throws IOException {
        if (directory)
            throw new IOException("Cannot open a stream for a connect content directory: " + this);
        return getEntry().getInputStream();
    }

    private ConnectEntry getEntry() throws IOException {
        ConnectContent content = root.open();
        Optional<ConnectEntry> entry = content.getEntry(path);
        if (entry == null || entry.isPresent() == false)
            throw new FileNotFoundException(toString());
        return entry.get();
    }

    @Override
    public Certificate[] getCertificates() {
        return null;
    }

    @Override
    public CodeSigner[] getCodeSigners() {
        return null;
    }

    /**
     * Close the connect content. It is opened again when it is accessed after this.
     */
    @Override
    public void close() throws IOException {
        if (this == root.rootFile)
            root.close();
    }

    @Override
    public String toString() {
        return "connect[" + root.location + "]/" + path;
    }

    /**
     * The connect contents of a framework that are open, with the number of root files that use them.
     *
     * A module connector may return the same content for each updated revision of a bundle. The content is opened
     * for the first root file that uses it, and it is closed when the last of them is closed.
     */
    static final class OpenContents {

        private final Map<ConnectContent, Integer> users = new IdentityHashMap<ConnectContent, Integer>();

        synchronized void open(ConnectContent content) throws IOException {
            Integer count = users.get(content);
            if (count == null)
                content.open();
            users.put(content, count == null ? 1 : count + 1);
        }

        synchronized void close(ConnectContent content) throws IOException {
            Integer count = users.get(content);
            if (count == null)
                return;
            if (count > 1) {
                users.put(content, count - 1);
            } else {
                users.remove(content);
                content.close();
            }
        }
    }

    /** The state that the files of a connect content share */
    private static final class Root {

        private final String location;
        private final String host;
        private final ConnectContent content;
        private final OpenContents openContents;
        private final ConnectVirtualFile rootFile;
        private Map<String, ConnectVirtualFile> files;
        private Map<String, String> headers;
        private boolean headersRead;
        private boolean open;

        Root(String location, ConnectContent content, OpenContents openContents) {
            this.location = location;
            this.content = content;
            this.openContents = openContents;
            this.host = Integer.toHexString(System.identityHashCode(content));
            this.rootFile = new ConnectVirtualFile(this, "", true);
        }

        synchronized ConnectContent open() throws IOException {
            if (open == false) {
                openContents.open(content);
                open = true;
            }
            return content;
        }

        synchronized void close() throws IOException {
            if (open) {
                open = false;
                files = null;
                openContents.close(content);
            }
        }

        /** The files and directories of the content by their path, which has no leading and no trailing slash */
        synchronized Map<String, ConnectVirtualFile> getFiles() throws IOException {
            if (files == null) {
                Map<String, ConnectVirtualFile> result = new LinkedHashMap<String, ConnectVirtualFile>();
                Iterable<String> entries = open().getEntries();
                if (entries != null) {
                    for (String name : entries) {
                        boolean isdir = name.endsWith("/");
                        String entryPath = normalize(isdir ? name.substring(0, name.length() - 1) : name);
                        if (entryPath.length() > 0) {
                            // The directories that the content has no entry for are listed as well
                            for (int index = entryPath.indexOf('/'); index > 0; index = entryPath.indexOf('/', index + 1)) {
                                String parentPath = entryPath.substring(0, index);
                                if (!result.containsKey(parentPath))
                                    result.put(parentPath, new ConnectVirtualFile(this, parentPath, true));
                            }
                            result.put(entryPath, new ConnectVirtualFile(this, entryPath, isdir));
                        }
                    }
                }
                files = result;
            }
            return files;
        }

        synchronized Map<String, String> getHeaders() throws IOException {
            if (headersRead == false) {
                Optional<Map<String, String>> optional = open().getHeaders();
                headers = optional != null && optional.isPresent() ? optional.get() : null;
                headersRead = true;
            }
            return headers;
        }

    }

    /** A connection to an entry of the connect content */
    private static final class EntryConnection extends URLConnection {

        private final ConnectVirtualFile file;
        private ConnectEntry entry;

        EntryConnection(URL url, ConnectVirtualFile file) {
            super(url);
            this.file = file;
        }

        @Override
        public void connect() throws IOException {
            if (connected == false) {
                if (file.directory == false)
                    entry = file.getEntry();
                connected = true;
            }
        }

        private ConnectEntry getEntry() {
            try {
                connect();
            } catch (IOException ex) {
                // the entry is not available
            }
            return entry;
        }

        @Override
        public long getContentLengthLong() {
            ConnectEntry connectEntry = getEntry();
            return connectEntry != null ? connectEntry.getContentLength() : -1;
        }

        @Override
        public int getContentLength() {
            long length = getContentLengthLong();
            return length > Integer.MAX_VALUE ? -1 : (int) length;
        }

        @Override
        public long getLastModified() {
            ConnectEntry connectEntry = getEntry();
            return connectEntry != null ? connectEntry.getLastModified() : 0;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            return file.openStream();
        }
    }
}
