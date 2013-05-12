/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.server.shared;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Date;
import java.util.GregorianCalendar;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.CacheHeaderContentStream;
import org.apache.chemistry.opencmis.commons.data.ContentLengthContentStream;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.LastModifiedContentStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;

public abstract class AbstractServiceCall implements ServiceCall {

    /**
     * Extracts a string parameter.
     */
    public String getStringParameter(HttpServletRequest request, String name) {
        return HttpUtils.getStringParameter(request, name);
    }

    /**
     * Extracts a boolean parameter (with default).
     */
    public boolean getBooleanParameter(HttpServletRequest request, String name, boolean def) {
        String value = getStringParameter(request, name);
        if ((value == null) || (value.length() == 0)) {
            return def;
        }

        return Boolean.valueOf(value);
    }

    /**
     * Extracts a boolean parameter.
     */
    public Boolean getBooleanParameter(HttpServletRequest request, String name) {
        String value = getStringParameter(request, name);
        if ((value == null) || (value.length() == 0)) {
            return null;
        }

        return Boolean.valueOf(value);
    }

    /**
     * Extracts an integer parameter (with default).
     */
    public BigInteger getBigIntegerParameter(HttpServletRequest request, String name, long def) {
        BigInteger result = getBigIntegerParameter(request, name);
        if (result == null) {
            result = BigInteger.valueOf(def);
        }

        return result;
    }

    /**
     * Extracts an integer parameter.
     */
    public BigInteger getBigIntegerParameter(HttpServletRequest request, String name) {
        String value = getStringParameter(request, name);
        if ((value == null) || (value.length() == 0)) {
            return null;
        }

        try {
            return new BigInteger(value);
        } catch (Exception e) {
            throw new CmisInvalidArgumentException("Invalid parameter '" + name + "'!");
        }
    }

    /**
     * Extracts an enum parameter.
     */
    @SuppressWarnings("unchecked")
    public <T> T getEnumParameter(HttpServletRequest request, String name, Class<T> clazz) {
        String value = getStringParameter(request, name);
        if ((value == null) || (value.length() == 0)) {
            return null;
        }

        try {
            Method m = clazz.getMethod("fromValue", new Class[] { String.class });
            return (T) m.invoke(null, new Object[] { value });
        } catch (Exception e) {
            if (e instanceof InvocationTargetException && e.getCause() instanceof IllegalArgumentException) {
                throw new CmisInvalidArgumentException("Invalid parameter '" + name + "'!");
            }

            throw new CmisRuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Closes a content stream.
     */
    public void closeContentStream(ContentStream contentStream) {
        if (contentStream != null && contentStream.getStream() != null) {
            try {
                contentStream.getStream().close();
            } catch (IOException e) {
                // we tried our best
            }
        }
    }

    /**
     * Sets certain HTTP headers if the server implementation requested them.
     * 
     * @return <code>true</code> if the request has been served by this method
     *         (e.g. status code 304 was send), <code>false</code> if the
     *         content should be served.
     */
    public boolean sendContentStreamHeaders(ContentStream content, HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        // check if Last-Modified header should be set
        if (content instanceof LastModifiedContentStream) {
            GregorianCalendar lastModified = ((LastModifiedContentStream) content).getLastModified();
            if (lastModified != null) {
                long lastModifiedSecs = (long) Math.floor((double) lastModified.getTimeInMillis() / 1000);

                Date modifiedSince = DateTimeHelper.parseHttpDateTime(request.getHeader("If-Modified-Since"));
                if (modifiedSince != null) {
                    long modifiedSinceSecs = (long) Math.floor((double) modifiedSince.getTime() / 1000);

                    if (modifiedSinceSecs >= lastModifiedSecs) {
                        // close stream
                        content.getStream().close();

                        // send not modified status code
                        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                        response.setContentLength(0);
                        return true;
                    }
                }

                response.setHeader("Last-Modified", DateTimeHelper.formatHttpDateTime(lastModifiedSecs * 1000));
            }
        }

        // check if cache headers should be set
        if (content instanceof CacheHeaderContentStream) {
            CacheHeaderContentStream chcs = (CacheHeaderContentStream) content;

            if (chcs.getETag() != null) {
                String etag = request.getHeader("If-None-Match");
                if (etag != null && !etag.equals("*")) {
                    if (etag.length() > 2 && etag.startsWith("\"") && etag.endsWith("\"")) {
                        etag = etag.substring(1, etag.length() - 1);
                    }

                    if (chcs.getETag().equals(etag)) {
                        // close stream
                        content.getStream().close();

                        // send not modified status code
                        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                        response.setContentLength(0);
                        return true;
                    }
                }

                response.setHeader("ETag", "\"" + chcs.getETag() + "\"");
            }

            if (chcs.getCacheControl() != null) {
                response.setHeader("Cache-Control", chcs.getCacheControl());
            }

            if (chcs.getExpires() != null) {
                response.setHeader("Expires", DateTimeHelper.formatHttpDateTime(chcs.getExpires()));
            }
        }

        // check if Content-Length header should be set
        if (content instanceof ContentLengthContentStream) {
            if (content.getBigLength() != null && content.getBigLength().signum() >= 0) {
                response.setHeader("Content-Length", content.getBigLength().toString());
            }
        }

        return false;
    }
}