package org.jboss.osgi.framework.internal;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.security.AccessController.doPrivileged;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.jboss.modules.ConcurrentClassLoader;
import org.jboss.modules.LocalLoader;
import org.jboss.modules.Resource;

/**
 * JDK-specific classes which are replaced for different JDK major versions.  This one is for Java 9 only.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class JDKSpecific {

    // === private fields and data ===

    static final Set<String> MODULES_PACKAGES = new HashSet<>(Arrays.asList(
            "org/jboss/modules",
            "org/jboss/modules/filter",
            "org/jboss/modules/log",
            "org/jboss/modules/management",
            "org/jboss/modules/ref"
    ));

    static Set<String> getJDKPaths() {
        Set<String> pathSet = new FastCopyHashSet<>(1024);
        processRuntimeImages(pathSet);
        // TODO: Remove this stuff once jboss-modules is itself a module
        final String javaClassPath = AccessController.doPrivileged(new PropertyReadAction("java.class.path"));
        JDKPaths.processClassPathItem(javaClassPath, new FastCopyHashSet<>(1024), pathSet);
        pathSet.addAll(MODULES_PACKAGES);
        return pathSet;
    }
    // === nested util stuff, non-API ===

    private static void processRuntimeImages(final Set<String> pathSet) {
        try {
            for (final Path root : FileSystems.getFileSystem(new URI("jrt:/")).getRootDirectories()) {
                Files.walkFileTree(root, new JrtFileVisitor(pathSet));
            }
        } catch (final URISyntaxException |IOException e) {
            throw new IllegalStateException("Unable to process java runtime images");
        }
    }

    private static class JrtFileVisitor implements FileVisitor<Path> {

        private static final String SLASH = "/";
        private static final String PACKAGES = "/packages";
        private final Set<String> pathSet;

        private JrtFileVisitor(final Set<String> pathSet) {
            this.pathSet = pathSet;
        }

        @Override
        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
            final String d = dir.toString();
            return d.equals(SLASH) || d.startsWith(PACKAGES) ? CONTINUE : SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
            if (file.getNameCount() >= 3 && file.getName(0).toString().equals("packages")) {
                pathSet.add(file.getName(1).toString().replace('.', '/'));
            }
            return CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
            return CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
            return CONTINUE;
        }
    }

}
