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

import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;
import static org.jboss.osgi.framework.internal.InternalConstants.NATIVE_LIBRARY_METADATA_KEY;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.NativeCode;
import org.jboss.osgi.framework.spi.NativeLibraryProvider;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.metadata.NativeLibrary;
import org.jboss.osgi.metadata.NativeLibraryMetaData;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.Parameter;
import org.jboss.osgi.metadata.ParameterizedAttribute;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.vfs.VFSUtils;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

/**
 * The bundle native code plugin
 *
 * @author thomas.diesler@jboss.com
 * @author David Bosschaert
 * @since 11-Aug-2010
 */
public final class NativeCodeImpl implements NativeCode {

    /**
     * The string that is to be replaced with the absolute path of the native library as specified by the core spec with the
     * org.osgi.framework.command.execpermission framework property.
     */
    private static final String ABSPATH_VARIABLE = "${abspath}";

    /** The OSGi processor names with their aliases, see https://docs.osgi.org/reference/osnames.html */
    private static final List<List<String>> PROCESSOR_GROUPS = Arrays.asList(
            Arrays.asList("68k"),
            Arrays.asList("AArch64", "arm64"),
            Arrays.asList("ARM_BE", "arm_be"),
            Arrays.asList("ARM_LE", "arm_le"),
            Arrays.asList("Alpha"),
            Arrays.asList("Ignite", "psc1k"),
            Arrays.asList("Mips"),
            Arrays.asList("PARisc"),
            Arrays.asList("PowerPC", "power", "ppc"),
            Arrays.asList("PowerPC-64", "ppc64"),
            Arrays.asList("PowerPC-64-LE", "ppc64le"),
            Arrays.asList("S390"),
            Arrays.asList("S390x"),
            Arrays.asList("Sparc"),
            Arrays.asList("V850E"),
            Arrays.asList("x86", "pentium", "i386", "i486", "i586", "i686"),
            Arrays.asList("x86-64", "amd64", "em64t", "x86_64"));

    /** The OSGi operating system names with their aliases, see https://docs.osgi.org/reference/osnames.html */
    private static final List<List<String>> OSNAME_GROUPS = Arrays.asList(
            Arrays.asList("AIX"),
            Arrays.asList("DigitalUnix"),
            Arrays.asList("Embos"),
            Arrays.asList("Epoc32", "SymbianOS"),
            Arrays.asList("FreeBSD"),
            Arrays.asList("HPUX", "hp-ux"),
            Arrays.asList("IRIX"),
            Arrays.asList("Linux"),
            Arrays.asList("MacOS", "Mac OS"),
            Arrays.asList("MacOSX", "Mac OS X"),
            Arrays.asList("NetBSD"),
            Arrays.asList("Netware"),
            Arrays.asList("OpenBSD"),
            Arrays.asList("OS2", "OS/2"),
            Arrays.asList("QNX", "procnto"),
            Arrays.asList("Solaris"),
            Arrays.asList("SunOS"),
            Arrays.asList("VxWorks"),
            Arrays.asList("Windows95", "Windows 95", "Win95", "Win32"),
            Arrays.asList("Windows98", "Windows 98", "Win98", "Win32"),
            Arrays.asList("WindowsNT", "Windows NT", "WinNT", "Win32"),
            Arrays.asList("WindowsCE", "Windows CE", "WinCE"),
            Arrays.asList("Windows2000", "Windows 2000", "Win2000", "Win32"),
            Arrays.asList("Windows2003", "Windows 2003", "Win2003", "Windows Server 2003", "Win32"),
            Arrays.asList("WindowsXP", "Windows XP", "WinXP", "Win32"),
            Arrays.asList("WindowsVista", "Windows Vista", "WinVista", "Win32"),
            Arrays.asList("Windows7", "Windows 7", "Win7", "Win32"),
            Arrays.asList("Windows8", "Windows 8", "Win8", "Win32"),
            Arrays.asList("Windows10", "Windows 10", "Win10", "Win32"),
            Arrays.asList("Windows11", "Windows 11", "Win11", "Win32"),
            Arrays.asList("WindowsServer2008", "Windows Server 2008", "Win32"),
            Arrays.asList("WindowsServer2012", "Windows Server 2012", "Win32"),
            Arrays.asList("WindowsServer2016", "Windows Server 2016", "Win32"),
            Arrays.asList("WindowsServer2019", "Windows Server 2019", "Win32"),
            Arrays.asList("WindowsServer2022", "Windows Server 2022", "Win32"));

    /**
     * The given name followed by the names of every group that contains it, compared without case
     */
    private static List<String> getAliases(String name, List<List<String>> groups) {
        List<String> result = new ArrayList<String>();
        if (name != null) {
            result.add(name);
            for (List<String> group : groups) {
                if (containsIgnoreCase(group, name)) {
                    for (String alias : group) {
                        if (containsIgnoreCase(result, alias) == false)
                            result.add(alias);
                    }
                }
            }
        }
        return result;
    }

    private static boolean containsIgnoreCase(Collection<String> values, String value) {
        for (String aux : values) {
            if (aux.equalsIgnoreCase(value))
                return true;
        }
        return false;
    }

    /**
     * The values of the osgi.native.osname attribute for the given framework os name
     */
    static List<String> getOSNames(String fwOSName) {
        return getAliases(fwOSName, OSNAME_GROUPS);
    }

    /**
     * The values of the osgi.native.processor attribute for the given framework processor
     */
    static List<String> getProcessors(String fwProcessor) {
        return getAliases(fwProcessor, PROCESSOR_GROUPS);
    }

    private final BundleManagerPlugin bundleManager;

    public NativeCodeImpl(BundleManager bundleManager) {
        this.bundleManager = (BundleManagerPlugin) bundleManager;
    }

    @Override
    public void deployNativeCode(Deployment dep) {
        // Core 4.2 spec section 3.9: a bundle can be installed if the Bundle-NativeCode code header
        // doesn't match. Errors are reported during the resolution phase.

        // Add NativeLibraryMetaData to the deployment as a marker that the deployment contains
        // Native Code which needs to be processed. The actual processing of it happens in the
        // resolveNativeCode() method.
        NativeLibraryMetaData nativeLibraries = new NativeLibraryMetaData();
        dep.putAttachment(NATIVE_LIBRARY_METADATA_KEY, nativeLibraries);
    }

    @Override
    public void resolveNativeCode(XBundleRevision brev) throws BundleException {
        UserBundleRevision userRev = UserBundleRevision.assertBundleRevision(brev);
        OSGiMetaData metaData = userRev.getAttachment(IntegrationConstants.OSGI_METADATA_KEY);
        List<ParameterizedAttribute> params = metaData.getBundleNativeCode();
        if (params.isEmpty())
            throw MESSAGES.cannotFindNativeCodeHeader(userRev);

        // Find the matching parameters
        List<ParameterizedAttribute> matchedParams = new ArrayList<ParameterizedAttribute>();
        for (ParameterizedAttribute param : params) {
            // The optional marker is not a clause
            if ("*".equals(param.getAttribute()))
                continue;
            if (matchParameter(param))
                matchedParams.add(param);
        }

        Deployment dep = userRev.getDeployment();
        NativeLibraryMetaData nativeLibraries = dep.getAttachment(NATIVE_LIBRARY_METADATA_KEY);

        // If no native clauses were selected in step 1, this algorithm is terminated
        // and a BundleException is thrown if the optional clause is not present
        if (matchedParams.size() == 0) {
            if (params.size() > 0 && "*".equals(params.get(params.size() - 1).getAttribute())) {
                // This Bundle-NativeCode clause is optional but we're not selecting any native code clauses
                // so remove the marker deployment attachment
                dep.removeAttachment(NATIVE_LIBRARY_METADATA_KEY);

                return;
            }

            throw MESSAGES.noNativeCodeClauseSelected(params);
        }

        // The selected clauses are now sorted in the following priority order:
        // * osversion: floor of the osversion range in descending order, osversion not specified
        // * language: language specified, language not specified
        // * Position in the Bundle-NativeCode manifest header: lexical left to right
        // [TODO] sort the clauses

        for (ParameterizedAttribute param : matchedParams) {
            String libpath = param.getAttribute();
            NativeLibrary library = new NativeLibrary(libpath);
            nativeLibraries.addNativeLibrary(library);
        }
    }

    // The values of a parameter, a parameter that is given more than once in a clause has a collection of values
    @SuppressWarnings({ "unchecked" })
    private List<String> getCollection(Object value) {
        if (value == null)
            return Collections.emptyList();

        if (value instanceof Collection)
            return new ArrayList<String>((Collection<String>) value);

        return Collections.singletonList(value.toString());
    }

    private boolean matchParameter(ParameterizedAttribute param) throws BundleException {
        // Only select the native code clauses for which the following expressions all evaluate to true
        // ('~=' stands for 'matches').
        // * osname ~= [org.osgi.framework.os.name]
        // * processor ~= [org.osgi.framework.processor]
        // * osversion range includes [org.osgi.framework.os.version] or osversion is not specified
        // * language ~= [org.osgi.framework.language] or language is not specified
        // * selection-filter evaluates to true when using the values of the system properties or selection-filter is not
        // specified

        // osname ~= [org.osgi.framework.os.name] or osname is not specified
        Parameter osnameParam = param.getAttribute(Constants.BUNDLE_NATIVECODE_OSNAME);
        BundleContext systemContext = bundleManager.getSystemBundle().getBundleContext();
        boolean match = true;
        if (osnameParam != null) {
            List<String> fwOSNames = getOSNames(systemContext.getProperty(Constants.FRAMEWORK_OS_NAME));
            boolean osmatch = false;
            for (String osname : getCollection(osnameParam.getValue())) {
                if (containsIgnoreCase(fwOSNames, osname)) {
                    osmatch = true;
                    break;
                }
            }
            match &= osmatch;
        }

        // processor ~= [org.osgi.framework.processor] or processor is not specified
        Parameter procParam = param.getAttribute(Constants.BUNDLE_NATIVECODE_PROCESSOR);
        if (match && procParam != null) {
            List<String> fwProcessors = getProcessors(systemContext.getProperty(Constants.FRAMEWORK_PROCESSOR));
            boolean procmatch = false;
            for (String proc : getCollection(procParam.getValue())) {
                if (containsIgnoreCase(fwProcessors, proc)) {
                    procmatch = true;
                    break;
                }
            }
            match &= procmatch;
        }

        // osversion range includes [org.osgi.framework.os.version] or osversion is not specified
        Parameter osversionParam = param.getAttribute(Constants.BUNDLE_NATIVECODE_OSVERSION);
        if (match && osversionParam != null) {
            String fwOSVersion = systemContext.getProperty(Constants.FRAMEWORK_OS_VERSION);

            boolean versionMatch = false;
            // The same version as the osgi.native capability of the system bundle
            Version currentVersion = BundleManagerPlugin.getOSVersion(fwOSVersion);

            // The ranges of a repeated osversion are ORed
            for (String versionRange : getCollection(osversionParam.getValue())) {
                VersionRange vr = new VersionRange(versionRange);
                if (vr.includes(currentVersion)) {
                    versionMatch = true;
                    break;
                }
            }

            match &= versionMatch;
        }

        // language ~= [org.osgi.framework.language] or language is not specified
        Parameter languageParam = param.getAttribute(Constants.BUNDLE_NATIVECODE_LANGUAGE);
        if (match && languageParam != null) {
            String fwLanguage = systemContext.getProperty(Constants.FRAMEWORK_LANGUAGE);

            boolean languageMatch = false;
            for (String language : getCollection(languageParam.getValue())) {
                if (language.equalsIgnoreCase(fwLanguage)) {
                    languageMatch = true;
                    break;
                }
            }

            match &= languageMatch;
        }

        // selection-filter evaluates to true when using the values of the system properties or selection-filter is not specified
        Parameter filterSelectionParam = param.getAttribute(Constants.SELECTION_FILTER_ATTRIBUTE);
        if (match && filterSelectionParam != null) {
            boolean filterMatch = false;
            Dictionary<String, Object> frameworkProps = new Hashtable<String, Object>(bundleManager.getProperties());
            // The filters of a repeated selection-filter are ORed
            for (String filterSpec : getCollection(filterSelectionParam.getValue())) {
                try {
                    Filter filter = FrameworkUtil.createFilter(filterSpec);
                    if (filter.match(frameworkProps)) {
                        filterMatch = true;
                        break;
                    }
                } catch (InvalidSyntaxException ex) {
                    throw MESSAGES.invalidFilterExpression(ex, filterSpec);
                }
            }

            match &= filterMatch;
        }

        return match;
    }

    static class NativeLibraryProviderImpl implements NativeLibraryProvider {
        private final UserBundleState hostBundle;
        private final String libname;
        private final String libpath;
        private final URL libURL;
        private File libraryFile;

        NativeLibraryProviderImpl(HostBundleRevision hostrev, String libname, String libpath) {
            this.hostBundle = hostrev.getBundleState();
            this.libpath = libpath;
            this.libname = libname;

            // If a native code library in a selected native code clause cannot be found
            // within the bundle or its fragments then the bundle must fail to resolve
            String path;
            String filename;
            int idx = libpath.lastIndexOf('/');
            if (idx >= 0) {
                path = libpath.substring(0, idx);
                filename = libpath.substring(idx + 1);
            } else {
                path = "";
                filename = libpath;
            }
            Enumeration<URL> urls = hostrev.findResolvedEntries(path, filename, false);
            if (urls == null || urls.hasMoreElements() == false)
                throw MESSAGES.illegalStateCannotFindNativeLibrary(libpath);

            this.libURL = urls.nextElement();
        }

        @Override
        public String getLibraryName() {
            return libname;
        }

        @Override
        public String getLibraryPath() {
            return libpath;
        }

        @Override
        public File getLibraryLocation() throws IOException {
            if (libraryFile == null) {
                // Create a unique local file location
                libraryFile = getUniqueLibraryFile(hostBundle, libpath);
                libraryFile.deleteOnExit();

                // Copy the native library to the bundle storage area
                FileOutputStream fos = new FileOutputStream(libraryFile);
                VFSUtils.copyStream(libURL.openStream(), fos);
                fos.close();

                handleExecPermission();
            }
            return libraryFile;
        }

        private void handleExecPermission() throws IOException {
            String epProp = hostBundle.getBundleContext().getProperty(Constants.FRAMEWORK_EXECPERMISSION);
            if (epProp == null)
                return;

            StringBuilder command = new StringBuilder(epProp);
            int idx = command.indexOf(ABSPATH_VARIABLE);
            if (idx >= 0) {
                command.replace(idx, idx + ABSPATH_VARIABLE.length(), libraryFile.getAbsolutePath());
            }
            String commandString = command.toString();
            Process process = Runtime.getRuntime().exec(commandString);
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                // Move ahead when interrupted
            }
            int exitCode = process.exitValue();
            if(exitCode != 0) {
                throw new IOException("FRAMEWORK_EXECPERMISSION '" + commandString + "' exited with " + exitCode);
            }
        }

        // A file of the framework in the storage directory of the bundle, not in the data area of the bundle
        private File getUniqueLibraryFile(UserBundleState userBundle, String libpath) {
            StorageManager storagePlugin = userBundle.getFrameworkState().getStorageManager();
            File libraryFile = new File(storagePlugin.getStorageDir(userBundle.getBundleId()), libpath);
            libraryFile.getParentFile().mkdirs();
            return libraryFile;
        }
    }
}