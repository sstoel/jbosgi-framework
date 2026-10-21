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

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;
import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;

/**
 * An abstraction of a bundle persistent storage.
 *
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 */
public class StorageState {

    public static final String PROPERTY_BUNDLE_FILE = "BundleFile";
    public static final String PROPERTY_BUNDLE_ID = "BundleId";
    public static final String PROPERTY_BUNDLE_LOCATION = "Location";
    public static final String PROPERTY_BUNDLE_REV = "BundleRev";
    public static final String PROPERTY_LAST_MODIFIED = "LastModified";
    public static final String PROPERTY_PERSISTENTLY_STARTED = "PersistentlyStarted";
    public static final String PROPERTY_ACTIVATION_POLICY_USED = "ActivationPolicyUsed";
    public static final String PROPERTY_START_LEVEL = "StartLevel";
    public static final String PROPERTY_JAVA_VERSION = "JavaVersion";
    public static final String PROPERTY_CONNECT_BUNDLE = "ConnectBundle";
    public static final String PROPERTY_SYMBOLIC_NAME = "SymbolicName";
    public static final String BUNDLE_PERSISTENT_PROPERTIES = "bundle-persistent.properties";
    // The suffix of a file that is written before it replaces the file of its name
    private static final String TMP_SUFFIX = ".tmp";
    public static final String BUNDLE_DIRECTORY_PREFIX = "bundle-";

    private final File storageDir;
    private final VirtualFile rootFile;
    private final Properties props;
    private final String location;
    private final long bundleId;
    private final int revision;

    static Set<String> requiredProps = new HashSet<String>();
    static {
        requiredProps.add(PROPERTY_BUNDLE_ID);
        requiredProps.add(PROPERTY_BUNDLE_REV);
        requiredProps.add(PROPERTY_BUNDLE_LOCATION);
        requiredProps.add(PROPERTY_START_LEVEL);
        requiredProps.add(PROPERTY_LAST_MODIFIED);
    }

    public static StorageState createStorageState(File storageDir) throws IOException {
        VirtualFile rootFile = null;
        Properties props = loadProperties(storageDir);
        String vfsLocation = props.getProperty(PROPERTY_BUNDLE_FILE);
        if (vfsLocation != null) {
            File revFile = new File(storageDir + "/" + vfsLocation);
            if (revFile.isFile() == false)
                throw new IllegalStateException("Bundle content missing: " + revFile);
            rootFile = AbstractVFS.toVirtualFile(revFile.toURI());
        }
        StorageState storageState = new StorageState(storageDir, rootFile, props);
        LOGGER.debugf("Restored storage state: %s", storageState);
        return storageState;
    }

    public static StorageState createStorageState(File storageDir, VirtualFile rootFile, Properties props) throws IOException {
        return createStorageState(storageDir, rootFile, props, true);
    }

    /**
     * Create the storage state of a bundle revision.
     *
     * @param copyContent false when the content of the root file must not be stored. It is then not restored either.
     */
    public static StorageState createStorageState(File storageDir, VirtualFile rootFile, Properties props, boolean copyContent) throws IOException {
        StorageState storageState = new StorageState(storageDir, rootFile, props);
        LOGGER.debugf("Created storage state: %s", storageState);
        if (rootFile != null && copyContent) {
            String bundleId = props.getProperty(StorageState.PROPERTY_BUNDLE_ID);
            String revision = props.getProperty(StorageState.PROPERTY_BUNDLE_REV);
            File revFile = new File(storageDir + File.separator + StorageState.BUNDLE_DIRECTORY_PREFIX + bundleId + "-rev-" + revision + ".jar");
            // The content is complete on disk before the properties name it, the properties are written last (connect/24)
            storageDir.mkdirs();
            File tmpFile = new File(storageDir, revFile.getName() + TMP_SUFFIX);
            try {
                InputStream input = rootFile.openStream();
                try {
                    FileOutputStream output = new FileOutputStream(tmpFile);
                    try {
                        VFSUtils.copyStream(input, output);
                        output.getFD().sync();
                    } finally {
                        output.close();
                    }
                } finally {
                    input.close();
                }
                moveAtomically(tmpFile, revFile);
            } finally {
                tmpFile.delete();
            }
            props.put(StorageState.PROPERTY_BUNDLE_FILE, revFile.getName());
        }
        storageState.storeProperties();
        return storageState;
    }

    /**
     * Delete the temporary files that a write left behind when it was interrupted
     */
    public static void deleteTemporaryFiles(File storageDir) {
        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(TMP_SUFFIX))
                    file.delete();
            }
        }
    }

    private static void moveAtomically(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Properties loadProperties(File storageDir) throws FileNotFoundException, IOException {
        Properties props = new Properties();
        File propsFile = new File(storageDir + "/" + BUNDLE_PERSISTENT_PROPERTIES);
        if (propsFile.exists()) {
            FileInputStream input = new FileInputStream(propsFile);
            try {
                props.load(input);
            } finally {
                VFSUtils.safeClose(input);
            }
        }
        return props;
    }

    public StorageState(File storageDir, VirtualFile rootFile, Properties props) {
        assert storageDir != null : "Null storageFile";
        assert props != null : "Null properties";
        assert storageDir.isDirectory() : "Not a directory: " + storageDir;

        for (String key : requiredProps) {
            if (props.get(key) == null)
                throw MESSAGES.illegalArgumentRequiredPropertyMissing(key, storageDir);
        }

        this.storageDir = storageDir;
        this.rootFile = rootFile;
        this.props = props;

        this.location = props.getProperty(PROPERTY_BUNDLE_LOCATION);
        this.bundleId = Long.parseLong(props.getProperty(PROPERTY_BUNDLE_ID));
        this.revision = Integer.parseInt(props.getProperty(PROPERTY_BUNDLE_REV));
    }

    public Properties getProperties() {
        return props;
    }

    public File getStorageDir() {
        return storageDir;
    }

    public String getLocation() {
        return location;
    }

    public VirtualFile getRootFile() {
        return rootFile;
    }

    public long getBundleId() {
        return bundleId;
    }

    public int getRevisionId() {
        return revision;
    }

    /** True for a connect bundle, which gets its content from the module connector */
    public boolean isConnectBundle() {
        return Boolean.parseBoolean(props.getProperty(PROPERTY_CONNECT_BUNDLE));
    }

    public long getLastModified() {
        String value = props.getProperty(PROPERTY_LAST_MODIFIED);
        return Long.parseLong(value);
    }

    public boolean isPersistentlyStarted() {
        String value = props.getProperty(PROPERTY_PERSISTENTLY_STARTED);
        return Boolean.parseBoolean(value);
    }

    public boolean isBundleActivationPolicyUsed() {
        String value = props.getProperty(PROPERTY_ACTIVATION_POLICY_USED);
        return Boolean.parseBoolean(value);
    }

    public int getStartLevel() {
        String value = props.getProperty(PROPERTY_START_LEVEL);
        return Integer.parseInt(value);
    }

    /**
     * The Java version that the content of a multi-release bundle was last used with.
     *
     * @return zero if the version is not recorded
     */
    public int getJavaVersion() {
        String value = props.getProperty(PROPERTY_JAVA_VERSION);
        try {
            return value != null ? Integer.parseInt(value) : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * The symbolic name of the bundle of the revision, null if it has none or it was not recorded: the state of another
     * bundle with the same location is not taken for it
     */
    public String getSymbolicName() {
        return props.getProperty(PROPERTY_SYMBOLIC_NAME);
    }

    public void setSymbolicName(String symbolicName) {
        if (symbolicName == null || symbolicName.equals(getSymbolicName()))
            return;
        getProperties().setProperty(PROPERTY_SYMBOLIC_NAME, symbolicName);
        writeProperties();
    }

    public void setJavaVersion(int version) {
        getProperties().setProperty(PROPERTY_JAVA_VERSION, Integer.toString(version));
        writeProperties();
    }

    public void updateLastModified() {
        getProperties().setProperty(PROPERTY_LAST_MODIFIED, new Long(System.currentTimeMillis()).toString());
        writeProperties();
    }

    public void setPersistentlyStarted(boolean started) {
        getProperties().setProperty(PROPERTY_PERSISTENTLY_STARTED, new Boolean(started).toString());
        writeProperties();
    }

    public void setBundleActivationPolicyUsed(boolean usePolicy) {
        getProperties().setProperty(PROPERTY_ACTIVATION_POLICY_USED, new Boolean(usePolicy).toString());
        writeProperties();
    }

    public void setStartLevel(int level) {
        getProperties().setProperty(PROPERTY_START_LEVEL, new Integer(level).toString());
        writeProperties();
    }

    private void writeProperties() {
        try {
            storeProperties();
        } catch (IOException ex) {
            LOGGER.errorCannotWritePersistentStorage(ex, getStorageDir());
        }
    }

    // The properties file is replaced as a whole: after a crash it has the old or the new properties
    private synchronized void storeProperties() throws IOException {
        File propsFile = new File(getStorageDir() + "/" + BUNDLE_PERSISTENT_PROPERTIES);
        File tmpFile = new File(getStorageDir() + "/" + BUNDLE_PERSISTENT_PROPERTIES + TMP_SUFFIX);
        try {
            FileOutputStream output = new FileOutputStream(tmpFile);
            try {
                getProperties().store(output, "Persistent Bundle Properties");
                output.getFD().sync();
            } finally {
                VFSUtils.safeClose(output);
            }
            moveAtomically(tmpFile, propsFile);
        } finally {
            tmpFile.delete();
        }
    }

    @Override
    public String toString() {
        int startlevel = getStartLevel();
        boolean started = isPersistentlyStarted();
        return "StorageState[id=" + bundleId + ",rev=" + revision + ",startlevel=" + startlevel + ",started=" + started + ",location=" + location + "]";
    }
}