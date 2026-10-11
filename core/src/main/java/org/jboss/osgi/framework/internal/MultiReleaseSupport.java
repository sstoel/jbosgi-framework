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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.Constants;

/**
 * Support for multi-release JARs.
 *
 * A container with a manifest that specifies <code>Multi-Release: true</code> has versioned directories
 * <code>META-INF/versions/N</code>. On Java 9 and later, the entries in the versioned directories that
 * apply to the running Java platform take precedence over the entries of the container.
 *
 * A multi-release bundle can have a supplemental manifest <code>OSGI-INF/MANIFEST.MF</code> in a
 * versioned directory, which replaces the Import-Package and Require-Capability headers.
 *
 * @since 11-Oct-2026
 */
final class MultiReleaseSupport {

    static final String MULTI_RELEASE = "Multi-Release";
    static final String VERSIONS_PATH = "META-INF/versions";
    static final String SUPPLEMENTAL_MANIFEST = "OSGI-INF/MANIFEST.MF";
    static final String JAVA_SPECIFICATION_VERSION = "java.specification.version";

    /** The first Java version that supports multi-release JARs */
    private static final int FIRST_VERSION = 9;

    // Hide ctor
    private MultiReleaseSupport() {
    }

    /**
     * Get the major version of the Java platform from a java.specification.version value.
     *
     * @return zero if the given value cannot be parsed
     */
    static int parseJavaVersion(String specVersion) {
        if (specVersion == null)
            return 0;

        String value = specVersion.trim();
        if (value.startsWith("1."))
            value = value.substring(2);

        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end)))
            end++;

        try {
            return end > 0 ? Integer.parseInt(value.substring(0, end)) : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static boolean isMultiRelease(Manifest manifest) {
        if (manifest == null)
            return false;
        String value = manifest.getMainAttributes().getValue(MULTI_RELEASE);
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    /**
     * True if the given container is a multi-release container on the given Java version.
     */
    static boolean isMultiRelease(VirtualFile rootFile, int javaVersion) {
        if (rootFile == null || javaVersion < FIRST_VERSION)
            return false;
        try {
            return isMultiRelease(VFSUtils.getManifest(rootFile));
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Get the versioned directories of the given container that apply to the given Java version.
     *
     * @return The versioned directories with the highest version first, empty if this is not a multi-release container
     */
    static List<VirtualFile> getVersionedRoots(VirtualFile rootFile, int javaVersion) {
        if (isMultiRelease(rootFile, javaVersion) == false)
            return Collections.emptyList();

        List<Integer> versions = new ArrayList<Integer>();
        try {
            VirtualFile versionsDir = rootFile.getChild(VERSIONS_PATH);
            if (versionsDir == null || versionsDir.isDirectory() == false)
                return Collections.emptyList();

            for (VirtualFile child : versionsDir.getChildren()) {
                int version = parseVersionedDirectory(child.getName());
                if (version >= FIRST_VERSION && version <= javaVersion && child.isDirectory())
                    versions.add(version);
            }
            Collections.sort(versions, Collections.reverseOrder());

            List<VirtualFile> result = new ArrayList<VirtualFile>();
            for (Integer version : versions) {
                VirtualFile versionedRoot = rootFile.getChild(VERSIONS_PATH + "/" + version);
                if (versionedRoot != null)
                    result.add(versionedRoot);
            }
            return Collections.unmodifiableList(result);
        } catch (IOException ex) {
            return Collections.emptyList();
        }
    }

    private static int parseVersionedDirectory(String name) {
        if (name.endsWith("/"))
            name = name.substring(0, name.length() - 1);
        // A versioned directory is named with the decimal major version without leading zeros
        if (name.isEmpty() || name.charAt(0) == '0')
            return -1;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i)) == false)
                return -1;
        }
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * True if a versioned directory may provide an entry with the given path.
     * A versioned directory cannot override the META-INF directory.
     */
    static boolean isVersionedPath(String path) {
        while (path.startsWith("/"))
            path = path.substring(1);
        return !(path.equals("META-INF") || path.startsWith("META-INF/"));
    }

    /**
     * Get the bundle metadata with the headers of the supplemental manifest that applies to the given Java version.
     *
     * @return The given metadata if there is no supplemental manifest
     */
    static OSGiMetaData getSupplementedMetaData(VirtualFile rootFile, OSGiMetaData metadata, int javaVersion) {
        if (rootFile == null || javaVersion < FIRST_VERSION)
            return metadata;

        try {
            Manifest manifest = VFSUtils.getManifest(rootFile);
            if (isMultiRelease(manifest) == false)
                return metadata;

            // The first supplemental manifest that is found is used
            Manifest supplemental = null;
            for (VirtualFile versionedRoot : getVersionedRoots(rootFile, javaVersion)) {
                VirtualFile child = versionedRoot.getChild(SUPPLEMENTAL_MANIFEST);
                if (child != null && child.isFile()) {
                    InputStream input = child.openStream();
                    try {
                        supplemental = new Manifest(input);
                    } finally {
                        VFSUtils.safeClose(input);
                    }
                    break;
                }
            }
            if (supplemental == null)
                return metadata;

            // Only the Import-Package and Require-Capability headers are replaced
            Manifest result = new Manifest(manifest);
            boolean modified = false;
            for (String header : new String[] { Constants.IMPORT_PACKAGE, Constants.REQUIRE_CAPABILITY }) {
                String value = supplemental.getMainAttributes().getValue(header);
                if (value != null) {
                    result.getMainAttributes().put(new Attributes.Name(header), value);
                    modified = true;
                }
            }
            return modified ? OSGiMetaDataBuilder.load(result) : metadata;
        } catch (IOException ex) {
            return metadata;
        }
    }
}
