/*
 * Copyright (C) 2015 Computer Science Corporation
 * All rights reserved.
 *
 */
package org.jboss.osgi.framework.internal;

import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.jar.Manifest;

import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.Resource;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.metadata.CaseInsensitiveDictionary;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.spi.AbstractBundleRevision;
import org.osgi.framework.Version;

/**
 * @author arcivanov
 */
public abstract class AbstractCommonBundleRevision extends AbstractBundleRevision
{
    @SuppressWarnings("unchecked")
    public Dictionary<String, String> getHeadersFromRaw(Dictionary<String, String> rawHeaders, String locale)
    {

        // If the specified locale is the empty string, this method will return the
        // raw (unlocalized) manifest headers including any leading "%"
        if ("".equals(locale))
            return rawHeaders;

        // If the specified locale is null then the locale
        // returned by java.util.Locale.getDefault is used
        if (locale == null)
            locale = Locale.getDefault().toString();

        // Get the localization base name
        String baseName = rawHeaders.get(Constants.BUNDLE_LOCALIZATION);
        if (baseName == null)
            baseName = Constants.BUNDLE_LOCALIZATION_DEFAULT_BASENAME;

        // Get the resource bundle URLs for the given base and locale, from the most specific to the least specific
        List<URL> entryURLs = getLocalizationEntries(baseName, locale);

        // If the specified locale entry could not be found fall back to the default locale entry
        if (entryURLs.isEmpty()) {
            String defaultLocale = Locale.getDefault().toString();
            entryURLs = getLocalizationEntries(baseName, defaultLocale);
        }

        // Read the resource bundles, a key is translated by the most specific one that defines it (3.11.2)
        List<ResourceBundle> resBundles = new ArrayList<ResourceBundle>();
        for (URL entryURL : entryURLs) {
            try {
                InputStream input = entryURL.openStream();
                try {
                    resBundles.add(new PropertyResourceBundle(input));
                } finally {
                    input.close();
                }
            }
            catch (IOException ex) {
                throw MESSAGES.illegalStateCannotReadResourceBundle(ex, entryURL);
            }
        }

        Dictionary<String, String> locHeaders = new Hashtable<String, String>();
        Enumeration<String> e = rawHeaders.keys();
        while (e.hasMoreElements()) {
            String key = e.nextElement();
            String value = rawHeaders.get(key);
            if (value.startsWith("%"))
                value = value.substring(1);

            for (ResourceBundle resBundle : resBundles) {
                if (resBundle.containsKey(value)) {
                    value = resBundle.getString(value);
                    break;
                }
            }

            locHeaders.put(key, value);
        }

        return new CaseInsensitiveDictionary(locHeaders);
    }

    /**
     * The existing localization entries of the search path for the locale, from the most specific to the base entry
     */
    List<URL> getLocalizationEntries(String baseName, String locale)
    {
        List<URL> result = new ArrayList<URL>();
        String suffix = locale;
        while (true) {
            String entryPath = suffix.isEmpty() ? baseName + ".properties" : baseName + "_" + suffix + ".properties";
            URL entryURL = getLocalizationEntry(entryPath);
            if (entryURL != null)
                result.add(entryURL);
            if (suffix.isEmpty())
                break;
            int lastIndex = suffix.lastIndexOf('_');
            suffix = lastIndex > 0 ? suffix.substring(0, lastIndex) : "";
        }
        return result;
    }

    public URL getLocalizationEntry(String baseName, String locale)
    {
        // The Framework searches for localization entries by appending suffixes to
        // the localization base name according to a specified locale and finally
        // appending the .properties suffix. If a translation is not found, the locale
        // must be made more generic by first removing the variant, then the country
        // and finally the language until an entry is found that contains a valid translation.

        String entryPath = baseName + "_" + locale + ".properties";

        URL entryURL = getLocalizationEntry(entryPath);
        while (entryURL == null) {
            if (entryPath.equals(baseName + ".properties"))
                break;

            int lastIndex = locale.lastIndexOf('_');
            if (lastIndex > 0) {
                locale = locale.substring(0, lastIndex);
                entryPath = baseName + "_" + locale + ".properties";
            }
            else {
                entryPath = baseName + ".properties";
            }

            // The bundle's class loader is not used to search for localization entries. Only
            // the contents of the bundle and its attached fragments are searched.
            entryURL = getLocalizationEntry(entryPath);
        }
        return entryURL;
    }

    /**
     * The framework must search for localization entries using the following search rules based on the bundle type:
     * <p/>
     * fragment bundle - If the bundle is a resolved fragment, then the search for localization data must delegate to the attached
     * host bundle with the highest version. If the fragment is not resolved, then the framework must search the fragment's JAR
     * for the localization entry.
     * <p/>
     * other bundle - The framework must first search in the bundle’s JAR for the localization entry. If the entry is not found
     * and the bundle has fragments, then the attached fragment JARs must be searched for the localization entry.
     */
    public abstract URL getLocalizationEntry(String entryPath);

    public abstract OSGiMetaData getOSGiMetaData();
}
