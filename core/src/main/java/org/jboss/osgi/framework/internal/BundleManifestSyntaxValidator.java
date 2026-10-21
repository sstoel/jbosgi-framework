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
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
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
            assertSymbolicNames(clause);
            assertDirective(Constants.BUNDLE_SYMBOLICNAME, clause, Constants.SINGLETON_DIRECTIVE, "true", "false");
            assertMandatoryAttributes(clause);
            assertDirective(Constants.BUNDLE_SYMBOLICNAME, clause, Constants.FRAGMENT_ATTACHMENT_DIRECTIVE, Constants.FRAGMENT_ATTACHMENT_ALWAYS,
                    Constants.FRAGMENT_ATTACHMENT_NEVER, Constants.FRAGMENT_ATTACHMENT_RESOLVETIME);
        }

        // A fragment names exactly one host bundle
        String fragmentHost = manifestHeaders.get(Constants.FRAGMENT_HOST);
        if (fragmentHost != null) {
            List<Clause> clauses = headers.get(Constants.FRAGMENT_HOST);
            if (clauses.size() != 1 || clauses.get(0).paths.size() != 1)
                throw manifestError(MESSAGES.invalidHeaderValue(Constants.FRAGMENT_HOST, fragmentHost, location));
        }
        for (Clause clause : headers.get(Constants.FRAGMENT_HOST)) {
            assertSymbolicNames(clause);
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
            assertMandatoryAttributes(clause);
        }

        // Requiring the same bundle symbolic name more than once
        Set<String> requiredBundles = new HashSet<String>();
        for (Clause clause : headers.get(Constants.REQUIRE_BUNDLE)) {
            assertSymbolicNames(clause);
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

        validateNativeCode();
    }

    private void assertSymbolicNames(Clause clause) throws BundleException {
        for (String symbolicName : clause.paths) {
            if (SYMBOLIC_NAME.matcher(symbolicName).matches() == false)
                throw manifestError(MESSAGES.invalidSymbolicName(symbolicName, location));
        }
    }

    // The attributes that a mandatory directive names must be defined in the clause (3.6.5, 3.7.6)
    private void assertMandatoryAttributes(Clause clause) throws BundleException {
        String mandatory = clause.directives.get(Constants.MANDATORY_DIRECTIVE);
        if (mandatory != null) {
            for (String attribute : mandatory.split(",")) {
                attribute = attribute.trim();
                if (attribute.length() > 0 && clause.attributes.containsKey(attribute) == false)
                    throw manifestError(MESSAGES.undefinedMandatoryAttribute(attribute, clause.toString(), location));
            }
        }
    }

    /**
     * Bundle-NativeCode (3.10): every clause has at least one path, except the optional '*' at the end; an osversion is
     * a version range; a selection-filter is a valid filter (NATIVECODE_ERROR). Like in the other headers, a trailing
     * ';' of a clause and a trailing ',' of the header are tolerated
     */
    private void validateNativeCode() throws BundleException {
        String value = manifestHeaders.get(Constants.BUNDLE_NATIVECODE);
        if (value == null)
            return;
        assertQuotesTerminated(Constants.BUNDLE_NATIVECODE, value);
        List<String> clauseSpecs = split(value, ',');
        if (clauseSpecs.size() > 1 && clauseSpecs.get(clauseSpecs.size() - 1).trim().isEmpty())
            clauseSpecs.remove(clauseSpecs.size() - 1);
        for (int i = 0; i < clauseSpecs.size(); i++) {
            String clauseSpec = clauseSpecs.get(i).trim();
            if (clauseSpec.equals("*") && i == clauseSpecs.size() - 1)
                continue;
            boolean path = false;
            boolean parameter = false;
            List<String> parts = split(clauseSpec, ';');
            for (int j = 0; j < parts.size(); j++) {
                String part = parts.get(j).trim();
                if (part.isEmpty() && j > 0 && j == parts.size() - 1)
                    continue;
                int index = indexOfUnquoted(part, '=');
                if (index < 0) {
                    if (part.isEmpty() || unquote(part).isEmpty() || parameter)
                        throw manifestError(MESSAGES.invalidHeaderValue(Constants.BUNDLE_NATIVECODE, clauseSpec, location));
                    path = true;
                    continue;
                }
                parameter = true;
                String name = part.substring(0, index).trim();
                String paramValue = unquote(part.substring(index + 1));
                if (Constants.BUNDLE_NATIVECODE_OSVERSION.equals(name)) {
                    try {
                        new VersionRange(paramValue);
                    } catch (IllegalArgumentException ex) {
                        throw manifestError(MESSAGES.invalidHeaderValue(Constants.BUNDLE_NATIVECODE, clauseSpec, location));
                    }
                } else if (Constants.SELECTION_FILTER_ATTRIBUTE.equals(name)) {
                    try {
                        FrameworkUtil.createFilter(paramValue);
                    } catch (InvalidSyntaxException ex) {
                        throw new BundleException(MESSAGES.invalidHeaderValue(Constants.BUNDLE_NATIVECODE, clauseSpec, location), BundleException.NATIVECODE_ERROR, ex);
                    }
                }
            }
            if (path == false)
                throw manifestError(MESSAGES.invalidHeaderValue(Constants.BUNDLE_NATIVECODE, clauseSpec, location));
        }
    }

    // The metadata parser takes a single quote as a quote too, like split and indexOfUnquoted do (fuzz/10)
    private void assertQuotesTerminated(String header, String value) throws BundleException {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && quote != 0)
                i++;
            else if (quote != 0)
                quote = c == quote ? 0 : quote;
            else if (c == '"' || c == '\'')
                quote = c;
        }
        if (quote != 0)
            throw manifestError(MESSAGES.invalidHeaderValue(header, value, location));
    }

    // The type of a typed attribute: String, Version, Long, Double, List or List<String|Version|Long|Double> (3.3.4)
    private void assertAttributeType(String header, String clause, String type) throws BundleException {
        String aux = type.trim();
        if (aux.startsWith("List")) {
            aux = aux.substring(4).trim();
            if (aux.isEmpty())
                return;
            if (aux.startsWith("<") == false || aux.endsWith(">") == false)
                throw manifestError(MESSAGES.invalidHeaderValue(header, clause, location));
            aux = aux.substring(1, aux.length() - 1).trim();
        }
        if (Arrays.asList("String", "Version", "Long", "Double").contains(aux) == false)
            throw manifestError(MESSAGES.invalidHeaderValue(header, clause, location));
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

        // A quoted string that is not terminated swallows the rest of the header
        assertQuotesTerminated(header, value);

        for (String clauseSpec : split(value, ',')) {
            if (clauseSpec.trim().length() == 0)
                continue;
            Clause clause = new Clause(clauseSpec.trim());
            boolean parameters = false;
            List<String> parts = split(clauseSpec, ';');
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i).trim();
                // A trailing semicolon is tolerated
                if (part.isEmpty() && i > 0 && i == parts.size() - 1)
                    continue;
                int index = indexOfUnquoted(part, '=');
                if (index < 0) {
                    // The paths come first, a path is not empty
                    if (parameters || part.isEmpty())
                        throw manifestError(MESSAGES.invalidHeaderValue(header, clauseSpec.trim(), location));
                    clause.paths.add(part);
                    continue;
                }
                // A clause starts with at least one path
                if (clause.paths.isEmpty())
                    throw manifestError(MESSAGES.invalidHeaderValue(header, clauseSpec.trim(), location));
                parameters = true;
                if (index > 0 && part.charAt(index - 1) == ':') {
                    String name = part.substring(0, index - 1).trim();
                    if (clause.directives.put(name, unquote(part.substring(index + 1))) != null)
                        throw manifestError(MESSAGES.duplicateHeaderParameter(name + ":", header, clauseSpec.trim(), location));
                } else {
                    // The name of an attribute may be followed by its type
                    String name = part.substring(0, index).trim();
                    int typeIndex = name.indexOf(':');
                    if (typeIndex > 0) {
                        assertAttributeType(header, clauseSpec.trim(), name.substring(typeIndex + 1));
                        name = name.substring(0, typeIndex).trim();
                    }
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
