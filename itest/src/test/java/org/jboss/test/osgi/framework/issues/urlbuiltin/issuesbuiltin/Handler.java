/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues.urlbuiltin.issuesbuiltin;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * A built-in URL stream handler for the scheme "issuesbuiltin", found through the java.protocol.handler.pkgs
 * class name construction (package org.jboss.test.osgi.framework.issues.urlbuiltin). Support class of
 * {@code org.jboss.test.osgi.framework.issues.URLBuiltinHandlerPrecedenceIssueTestCase}.
 */
public class Handler extends URLStreamHandler {

    @Override
    protected URLConnection openConnection(URL url) {
        return new URLConnection(url) {
            @Override
            public void connect() {
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream("built-in".getBytes());
            }
        };
    }
}
