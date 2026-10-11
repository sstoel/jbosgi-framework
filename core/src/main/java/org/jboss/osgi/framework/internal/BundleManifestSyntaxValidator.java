/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jboss.osgi.metadata.OSGiMetaData;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

/**
 * Validates the syntax of the manifest headers of a bundle with a Bundle-ManifestVersion of 2.
 *
 * This works on the header values as they are in the manifest, the parsed metadata does not show a duplicate
 * attribute or directive.
 *
 * @since 12-Oct-2026
 */
final class BundleManifestSyntaxValidator {

    // symbolic-name ::= token ( '.' token )*
    private static final Pattern SYMBOLIC_NAME = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

    // The headers with attributes and directives. Bundle-NativeCode may repeat its attributes
    private static final String[] PARAMETERIZED_HEADERS = { Constants.BUNDLE_SYMBOLICNAME, Constants.FRAGMENT_HOST, Constants.IMPORT_PACKAGE,
            Constants.EXPORT_PACKAGE, Constants.DYNAMICIMPORT_PACKAGE, Constants.REQUIRE_BUNDLE, Constants.PROVIDE_CAPABILITY,
            Constants.REQUIRE_CAPABILITY, Constants.BUNDLE_ACTIVATIONPOLICY };

    private final Dictionary<String, String> manifestHeaders;
    private final String location;

    private BundleManifestSyntaxValidator(OSGiMetaData metadata, String location) {
        // The headers as they are in the manifest, a single header may already be cached as its parsed value
        this.manifestHeaders = metadata.getHeaders();
        this.location = location;
    }

    /**
     * @throws BundleException of type {@link BundleException#MANIFEST_ERROR} for a header that is not valid
     */
    static void validate(OSGiMetaData metadata, String location) throws BundleException {
        new BundleManifestSyntaxValidator(metadata, location).validate();
    }

    private void validate() throws BundleException {

        // Duplicate attribute or duplicate directive
        Map<String, List<Clause>> headers = new LinkedHashMap<String, List<Clause>>();
        for (String header : PARAMETERIZED_HEADERS)
            headers.put(header, parseHeader(header));

        // Improperly formatted version or bundle symbolic name
        String bundleVersion = manifestHeaders.get(Constants.BUNDLE_VERSION);
        if (bundleVersion != null)
            assertVersion(Constants.BUNDLE_VERSION, bundleVersion, bundleVersion);

        for (Clause clause : headers.get(Constants.BUNDLE_SYMBOLICNAME)) {
            for (String symbolicName : clause.paths) {
                if (SYMBOLIC_NAME.matcher(symbolicName).matches() == false)
                    throw manifestError(MESSAGES.invalidSymbolicName(symbolicName, location));
            }
            assertDirective(Constants.BUNDLE_SYMBOLICNAME, clause, Constants.SINGLETON_DIRECTIVE, "true", "false");
            assertDirective(Constants.BUNDLE_SYMBOLICNAME, clause, Constants.FRAGMENT_ATTACHMENT_DIRECTIVE, Constants.FRAGMENT_ATTACHMENT_ALWAYS,
                    Constants.FRAGMENT_ATTACHMENT_NEVER, Constants.FRAGMENT_ATTACHMENT_RESOLVETIME);
        }

        for (Clause clause : headers.get(Constants.FRAGMENT_HOST)) {
            // The extension directive is validated with the bundle revision, an unknown value is accepted
            assertVersionRange(Constants.FRAGMENT_HOST, clause, Constants.BUNDLE_VERSION_ATTRIBUTE);
        }

        for (String header : new String[] { Constants.IMPORT_PACKAGE, Constants.DYNAMICIMPORT_PACKAGE }) {
            for (Clause clause : headers.get(header)) {
                assertVersionRange(header, clause, Constants.VERSION_ATTRIBUTE);
                assertVersionRange(header, clause, Constants.PACKAGE_SPECIFICATION_VERSION);
                assertVersionRange(header, clause, Constants.BUNDLE_VERSION_ATTRIBUTE);
            }
        }
        for (Clause clause : headers.get(Constants.IMPORT_PACKAGE))
            assertDirective(Constants.IMPORT_PACKAGE, clause, Constants.RESOLUTION_DIRECTIVE, Constants.RESOLUTION_MANDATORY, Constants.RESOLUTION_OPTIONAL);

        // Export-Package with a mandatory attribute that is not defined
        for (Clause clause : headers.get(Constants.EXPORT_PACKAGE)) {
            for (String attribute : new String[] { Constants.VERSION_ATTRIBUTE, Constants.PACKAGE_SPECIFICATION_VERSION }) {
                String version = clause.attributes.get(attribute);
                if (version != null)
                    assertVersion(Constants.EXPORT_PACKAGE, version, clause.toString());
            }
            String mandatory = clause.directives.get(Constants.MANDATORY_DIRECTIVE);
            if (mandatory != null) {
                for (String attribute : mandatory.split(",")) {
                    attribute = attribute.trim();
                    if (attribute.length() > 0 && clause.attributes.containsKey(attribute) == false)
                        throw manifestError(MESSAGES.undefinedMandatoryAttribute(attribute, clause.toString(), location));
                }
            }
        }

        // Requiring the same bundle symbolic name more than once
        Set<String> requiredBundles = new HashSet<String>();
        for (Clause clause : headers.get(Constants.REQUIRE_BUNDLE)) {
            for (String symbolicName : clause.paths) {
                if (requiredBundles.add(symbolicName) == false)
                    throw manifestError(MESSAGES.duplicateRequireBundle(symbolicName, location));
            }
            assertVersionRange(Constants.REQUIRE_BUNDLE, clause, Constants.BUNDLE_VERSION_ATTRIBUTE);
            assertDirective(Constants.REQUIRE_BUNDLE, clause, Constants.RESOLUTION_DIRECTIVE, Constants.RESOLUTION_MANDATORY, Constants.RESOLUTION_OPTIONAL);
            assertDirective(Constants.REQUIRE_BUNDLE, clause, Constants.VISIBILITY_DIRECTIVE, Constants.VISIBILITY_PRIVATE, Constants.VISIBILITY_REEXPORT);
        }

        for (Clause clause : headers.get(Constants.REQUIRE_CAPABILITY)) {
            assertDirective(Constants.REQUIRE_CAPABILITY, clause, Constants.RESOLUTION_DIRECTIVE, Constants.RESOLUTION_MANDATORY, Constants.RESOLUTION_OPTIONAL);
            assertDirective(Constants.REQUIRE_CAPABILITY, clause, "cardinality", "single", "multiple");
        }

        for (Clause clause : headers.get(Constants.BUNDLE_ACTIVATIONPOLICY)) {
            if (clause.paths.equals(Arrays.asList(Constants.ACTIVATION_LAZY)) == false)
                throw manifestError(MESSAGES.invalidHeaderValue(Constants.BUNDLE_ACTIVATIONPOLICY, clause.toString(), location));
        }
    }

    private void assertVersion(String header, String version, String headerValue) throws BundleException {
        try {
            Version.parseVersion(version);
        } catch (IllegalArgumentException ex) {
            throw manifestError(MESSAGES.invalidHeaderValue(header, headerValue, location));
        }
    }

    private void assertVersionRange(String header, Clause clause, String attribute) throws BundleException {
        String range = clause.attributes.get(attribute);
        if (range != null) {
            try {
                new VersionRange(range);
            } catch (IllegalArgumentException ex) {
                throw manifestError(MESSAGES.invalidHeaderValue(header, clause.toString(), location));
            }
        }
    }

    private void assertDirective(String header, Clause clause, String directive, String... values) throws BundleException {
        String value = clause.directives.get(directive);
        if (value != null && Arrays.asList(values).contains(value) == false)
            throw manifestError(MESSAGES.invalidHeaderValue(header, clause.toString(), location));
    }

    private static BundleException manifestError(String message) {
        return new BundleException(message, BundleException.MANIFEST_ERROR);
    }

    private List<Clause> parseHeader(String header) throws BundleException {
        List<Clause> result = new ArrayList<Clause>();
        String value = manifestHeaders.get(header);
        if (value == null)
            return result;

        for (String clauseSpec : split(value, ',')) {
            if (clauseSpec.trim().length() == 0)
                continue;
            Clause clause = new Clause(clauseSpec.trim());
            for (String part : split(clauseSpec, ';')) {
                part = part.trim();
                int index = indexOfUnquoted(part, '=');
                if (index < 0) {
                    clause.paths.add(part);
                } else if (index > 0 && part.charAt(index - 1) == ':') {
                    String name = part.substring(0, index - 1).trim();
                    if (clause.directives.put(name, unquote(part.substring(index + 1))) != null)
                        throw manifestError(MESSAGES.duplicateHeaderParameter(name + ":", header, clauseSpec.trim(), location));
                } else {
                    // The name of an attribute may be followed by its type
                    String name = part.substring(0, index).trim();
                    int typeIndex = name.indexOf(':');
                    if (typeIndex > 0)
                        name = name.substring(0, typeIndex).trim();
                    if (clause.attributes.put(name, unquote(part.substring(index + 1))) != null)
                        throw manifestError(MESSAGES.duplicateHeaderParameter(name, header, clauseSpec.trim(), location));
                }
            }
            result.add(clause);
        }
        return result;
    }

    /** Split at the delimiters that are not in a quoted string. Like the header parser, this accepts both quote characters */
    private static List<String> split(String value, char delimiter) {
        List<String> result = new ArrayList<String>();
        char quote = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && quote != 0) {
                i++;
            } else if (quote != 0) {
                if (c == quote)
                    quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == delimiter) {
                result.add(value.substring(start, i));
                start = i + 1;
            }
        }
        result.add(value.substring(start));
        return result;
    }

    private static int indexOfUnquoted(String value, char search) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && quote != 0)
                i++;
            else if (quote != 0)
                quote = c == quote ? 0 : quote;
            else if (c == '"' || c == '\'')
                quote = c;
            else if (c == search)
                return i;
        }
        return -1;
    }

    private static String unquote(String value) {
        value = value.trim();
        if (value.length() > 1 && (value.charAt(0) == '"' || value.charAt(0) == '\'') && value.charAt(value.length() - 1) == value.charAt(0))
            value = value.substring(1, value.length() - 1);
        return value;
    }

    private static final class Clause {

        private final String spec;
        private final List<String> paths = new ArrayList<String>();
        private final Map<String, String> attributes = new LinkedHashMap<String, String>();
        private final Map<String, String> directives = new LinkedHashMap<String, String>();

        Clause(String spec) {
            this.spec = spec;
        }

        @Override
        public String toString() {
            return spec;
        }
    }
}
