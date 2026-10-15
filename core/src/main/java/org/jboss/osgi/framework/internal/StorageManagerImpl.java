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

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;
import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.StartLevelManager;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * A simple implementation of a BundleStorage
 *
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 *
 * @ThreadSafe
 */
public final class StorageManagerImpl implements StorageManager {

    // The highest bundle id that the framework assigned, a bundle id is not used again (4.4.3)
    private static final String LAST_BUNDLE_ID_FILE = "last-bundle-id";
    // The data area of a bundle, the bundle directory also holds the files of the framework
    private static final String DATA_DIRECTORY = "data";

    private final BundleManagerPlugin bundleManager;
    private final Map<String, StorageState> storageStates = new ConcurrentHashMap<String, StorageState>();
    private final File storageArea;
    private volatile long lastBundleId;

    public StorageManagerImpl(BundleManager bundleManager) {
        this.bundleManager = (BundleManagerPlugin) bundleManager;
        String dirName = (String) bundleManager.getProperty(Constants.FRAMEWORK_STORAGE);
        if (dirName == null) {
            dirName = getDefaultStorageArea();
        }
        this.storageArea = new File(dirName).getAbsoluteFile();
    }

    /**
     * The storage area that is used when {@link Constants#FRAMEWORK_STORAGE} is not configured.
     */
    static String getDefaultStorageArea() {
        try {
            return new File("./osgi-store").getCanonicalPath();
        } catch (IOException ex) {
            throw MESSAGES.illegalStateCannotCreateStorageArea(ex);
        }
    }

    @Override
    public void initialize(Map<String, Object> props, boolean firstInit) throws IOException {
        // Cleanup the storage area. This is configured for the framework instance, a system property does not apply
        String storageClean = (String) bundleManager.getConfiguredProperty(Constants.FRAMEWORK_STORAGE_CLEAN);
        if (firstInit == true && Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT.equals(storageClean)) {
            File storage = getStorageArea();
            LOGGER.debugf("Deleting storage: %s", storage.getAbsolutePath());
            deleteRecursive(storage);
        }

        // Initialize storage states
        FilenameFilter filter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(StorageState.BUNDLE_DIRECTORY_PREFIX);
            }
        };
        lastBundleId = readLastBundleId();
        File[] storageDirs = getStorageArea().listFiles(filter);
        if (storageDirs != null) {
            for (File storageDir : storageDirs) {
                lastBundleId = Math.max(lastBundleId, getBundleId(storageDir));

                // A directory that cannot be restored, like one that a failed install left behind, is skipped
                StorageState storageState;
                try {
                    storageState = restoreStorageState(storageDir);
                } catch (IOException | RuntimeException ex) {
                    LOGGER.warnf(ex, "Cannot restore the bundle storage, it is skipped: %s", storageDir);
                    continue;
                }
                if (storageState == null)
                    continue;
                if (storageState.getBundleId() != 0) {
                    synchronized (storageStates) {
                        storageStates.put(storageState.getLocation(), storageState);
                    }
                }
            }
        }
    }

    private StorageState restoreStorageState(File storageDir) throws IOException {
        Properties props = StorageState.loadProperties(storageDir);
        if (Boolean.parseBoolean(props.getProperty(StorageState.PROPERTY_CONNECT_BUNDLE)) == false)
            return StorageState.createStorageState(storageDir);

        // A connect bundle exists for as long as the module connector provides its content
        String location = props.getProperty(StorageState.PROPERTY_BUNDLE_LOCATION);
        VirtualFile rootFile = null;
        try {
            rootFile = bundleManager.connect(location);
        } catch (BundleException ex) {
            LOGGER.debugf(ex, "Cannot connect bundle: %s", location);
        }
        if (rootFile == null) {
            LOGGER.debugf("Removing connect bundle that has no content: %s", location);
            deleteRecursive(storageDir);
            return null;
        }
        StorageState storageState = new StorageState(storageDir, rootFile, props);
        LOGGER.debugf("Restored storage state: %s", storageState);
        return storageState;
    }

    @Override
    public synchronized StorageState createStorageState(long bundleId, String location, Integer initialStartlevel, VirtualFile rootFile) throws IOException {
        assert location != null : "Null location";

        int startlevel;
        if (initialStartlevel != null) {
            startlevel = initialStartlevel.intValue();
        } else {
            StartLevelManager startLevelManager = bundleManager.getFrameworkState().getStartLevelManager();
            startlevel = startLevelManager.getInitialBundleStartLevel();
        }

        // Make the bundle's storage dir
        File bundleDir = getStorageDir(bundleId);
        Properties props = StorageState.loadProperties(bundleDir);
        String previousRev = props.getProperty(StorageState.PROPERTY_BUNDLE_REV);
        String previousLocation = props.getProperty(StorageState.PROPERTY_BUNDLE_LOCATION);
        if (bundleId != 0 && previousRev != null && !location.equals(previousLocation)) {
            // A stale directory of another bundle, its data area must not be given to this bundle (4.5.2)
            LOGGER.warnf("Removing the stale storage of bundle %d with location: %s", bundleId, previousLocation);
            StorageState stale = previousLocation != null ? storageStates.get(previousLocation) : null;
            if (stale != null && bundleDir.equals(stale.getStorageDir()))
                storageStates.remove(previousLocation);
            deleteRecursive(bundleDir);
            bundleDir = getStorageDir(bundleId);
            props = new Properties();
            previousRev = null;
        }
        int revision = (bundleId != 0 && previousRev != null ? Integer.parseInt(previousRev) + 1 : 0);

        // Write the bundle properties
        props.put(StorageState.PROPERTY_BUNDLE_LOCATION, location);
        props.put(StorageState.PROPERTY_BUNDLE_ID, new Long(bundleId).toString());
        props.put(StorageState.PROPERTY_BUNDLE_REV, new Integer(revision).toString());
        props.put(StorageState.PROPERTY_START_LEVEL, new Integer(startlevel).toString());
        props.put(StorageState.PROPERTY_LAST_MODIFIED, new Long(System.currentTimeMillis()).toString());

        boolean connect = ConnectVirtualFile.isConnectFile(rootFile);
        if (connect) {
            props.put(StorageState.PROPERTY_CONNECT_BUNDLE, Boolean.TRUE.toString());
            props.remove(StorageState.PROPERTY_BUNDLE_FILE);
        } else {
            props.remove(StorageState.PROPERTY_CONNECT_BUNDLE);
        }
        // A new bundle gets its data area right away, the files of an older storage layout are only moved for existing bundles
        if (previousRev == null)
            new File(bundleDir, DATA_DIRECTORY).mkdirs();

        StorageState storageState;
        try {
            storageState = StorageState.createStorageState(bundleDir, rootFile, props, connect == false);
        } catch (IOException | RuntimeException ex) {
            // The directory of a new bundle would stay without its properties
            if (previousRev == null)
                deleteRecursive(bundleDir);
            throw ex;
        }
        if (storageState.getBundleId() != 0) {
            storageStates.put(storageState.getLocation(), storageState);
        }
        if (bundleId > lastBundleId) {
            lastBundleId = bundleId;
            writeLastBundleId(bundleId);
        }
        return storageState;
    }

    /**
     * The highest bundle id that was assigned, also to bundles that are uninstalled.
     */
    long getLastBundleId() {
        return lastBundleId;
    }

    private long readLastBundleId() {
        File file = new File(getStorageArea(), LAST_BUNDLE_ID_FILE);
        if (file.isFile() == false)
            return 0;
        try {
            return Long.parseLong(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException ex) {
            LOGGER.warnf(ex, "Cannot read the last bundle id: %s", file);
            return 0;
        }
    }

    private void writeLastBundleId(long bundleId) {
        File file = new File(getStorageArea(), LAST_BUNDLE_ID_FILE);
        File tmpFile = new File(getStorageArea(), LAST_BUNDLE_ID_FILE + ".tmp");
        try {
            Files.write(tmpFile.toPath(), Long.toString(bundleId).getBytes(StandardCharsets.UTF_8));
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            LOGGER.warnf(ex, "Cannot write the last bundle id: %s", file);
        }
    }

    private static long getBundleId(File storageDir) {
        try {
            return Long.parseLong(storageDir.getName().substring(StorageState.BUNDLE_DIRECTORY_PREFIX.length()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @Override
    public synchronized void deleteStorageState(StorageState storageState) {
        deleteStorageState(storageState, true);
    }

    /**
     * Delete the storage of a bundle. The content of a revision that is still in use (removal pending) stays open, the
     * revision closes it when it is removed.
     */
    synchronized void deleteStorageState(StorageState storageState, boolean closeContent) {
        LOGGER.debugf("Deleting storage state: %s", storageState);
        if (closeContent)
            VFSUtils.safeClose(storageState.getRootFile());
        deleteRecursive(storageState.getStorageDir());
        storageStates.remove(storageState.getLocation());
    }

    @Override
    public Set<StorageState> getStorageStates() {
        synchronized (storageStates) {
            return Collections.unmodifiableSet(new HashSet<StorageState>(storageStates.values()));
        }
    }

    @Override
    public StorageState getStorageState(String location) {
        if (location == null) {
            return null;
        }
        synchronized (storageStates) {
            return storageStates.get(location);
        }
    }

    @Override
    public File getStorageDir(long bundleId) {
        File bundleDir = new File(getStorageArea() + "/bundle-" + bundleId);
        if (bundleDir.exists() == false)
            bundleDir.mkdirs();

        String filePath = bundleDir.getAbsolutePath();
        try {
            filePath = bundleDir.getCanonicalPath();
        } catch (IOException ex) {
            // ignore
        }
        return new File(filePath);
    }

    @Override
    public File getStorageArea() {
        return storageArea;
    }

    @Override
    public File getDataFile(long bundleId, String filename) {
        File dataDir = getDataArea(bundleId);
        File dataFile = new File(dataDir.getAbsolutePath() + File.separator + filename);
        dataFile.getParentFile().mkdirs();

        String filePath = dataFile.getAbsolutePath();
        try {
            filePath = dataFile.getCanonicalPath();
        } catch (IOException ex) {
            // ignore
        }
        return new File(filePath);
    }

    /**
     * The data area of a bundle. A bundle that used the bundle directory itself as its data area before, gets its files
     * moved to the data area, the files of the framework stay.
     */
    private synchronized File getDataArea(long bundleId) {
        File bundleDir = getStorageDir(bundleId);
        File dataDir = new File(bundleDir, DATA_DIRECTORY);
        if (dataDir.isDirectory() == false) {
            dataDir.mkdirs();
            File[] files = bundleDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    boolean frameworkFile = name.equals(DATA_DIRECTORY) || name.equals(StorageState.BUNDLE_PERSISTENT_PROPERTIES)
                            || (name.startsWith(StorageState.BUNDLE_DIRECTORY_PREFIX) && name.contains("-rev-") && name.endsWith(".jar"));
                    if (frameworkFile == false && file.renameTo(new File(dataDir, name)) == false)
                        LOGGER.warnf("Cannot move %s to the data area of bundle %d", file, bundleId);
                }
            }
        }
        return dataDir;
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            for (File aux : file.listFiles())
                deleteRecursive(aux);
        }
        file.delete();
    }
}