package org.jboss.test.osgi.framework.url;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ContentHandler;
import java.net.URL;
import java.net.URLConnection;
import java.util.Hashtable;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;

/**
 * Test that a {@link ContentHandler} service can be unregistered and registered again.
 *
 * @since 11-Oct-2026
 */
public class ContentHandlerTestCase extends OSGiFrameworkTest {

    private static final String PROTOCOL = "contenthandlertest";
    private static final String MIMETYPE = "test/contenthandlertest";

    @Test
    public void testContentHandlerReRegistration() throws Exception {
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put(URLConstants.URL_HANDLER_PROTOCOL, new String[] { PROTOCOL });
        ServiceRegistration<?> streamReg = getSystemContext().registerService(URLStreamHandlerService.class.getName(), new TestURLStreamHandlerService(), props);
        try {
            ServiceRegistration<?> contentReg = registerContentHandler("first");
            assertEquals("first", new URL(PROTOCOL + "://test").getContent());
            contentReg.unregister();

            // Without a content handler service the content is the input stream
            assertTrue(new URL(PROTOCOL + "://test").getContent() instanceof InputStream);

            // The JVM must not hold on to its default content handler
            contentReg = registerContentHandler("second");
            try {
                assertEquals("second", new URL(PROTOCOL + "://test").getContent());
            } finally {
                contentReg.unregister();
            }
        } finally {
            streamReg.unregister();
        }
    }

    private ServiceRegistration<?> registerContentHandler(final String content) throws Exception {
        ContentHandler handler = new ContentHandler() {
            @Override
            public Object getContent(URLConnection urlc) throws IOException {
                return content;
            }
        };
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put(URLConstants.URL_CONTENT_MIMETYPE, new String[] { MIMETYPE });
        return getSystemContext().registerService(ContentHandler.class.getName(), handler, props);
    }

    private static class TestURLStreamHandlerService extends AbstractURLStreamHandlerService {

        @Override
        public URLConnection openConnection(final URL url) throws IOException {
            return new URLConnection(url) {

                @Override
                public void connect() throws IOException {
                }

                @Override
                public String getContentType() {
                    return MIMETYPE;
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    return new ByteArrayInputStream(new byte[0]);
                }
            };
        }
    }
}
