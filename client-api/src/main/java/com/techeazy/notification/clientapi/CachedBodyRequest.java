/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.clientapi;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import org.apache.catalina.core.ApplicationPart;
import org.apache.tomcat.util.http.fileupload.FileItem;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.UploadContext;
import org.apache.tomcat.util.http.fileupload.disk.DiskFileItemFactory;
import org.springframework.http.MediaType;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A request whose body has been read into memory, so its exact bytes can be checked against a signature (ADR-036) and
 * then read again by the controller.
 * <p>
 * Tomcat parses a multipart body straight from the connection rather than through a wrapper's input stream, so for a
 * multipart request this wrapper parses the remembered bytes itself, with Tomcat's own parser, and answers the part
 * and parameter calls from them. Query parameters are merged with the form fields, as the container would.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    /** Raised when the body is larger than the limit given to {@link #read}. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long limit) {
            super("The request body is larger than " + limit + " bytes");
        }
    }

    private static final int MAX_PARTS = 32;
    private static final int READ_CHUNK = 8192;
    private static final File TEMPORARY_DIRECTORY = new File(System.getProperty("java.io.tmpdir"));

    private final byte[] body;
    private List<Part> parts;
    private Map<String, String[]> parameters;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** Reads the whole body of {@code request}, refusing one longer than {@code maxBytes}. */
    static CachedBodyRequest read(HttpServletRequest request, long maxBytes) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new BodyTooLargeException(maxBytes);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = request.getInputStream()) {
            byte[] chunk = new byte[READ_CHUNK];
            long total = 0;
            for (int read = in.read(chunk); read != -1; read = in.read(chunk)) {
                total += read;
                if (total > maxBytes) {
                    throw new BodyTooLargeException(maxBytes);
                }
                buffer.write(chunk, 0, read);
            }
        }
        return new CachedBodyRequest(request, buffer.toByteArray());
    }

    byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedInputStream(new ByteArrayInputStream(body));
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        if (!isMultipart()) {
            return super.getParts();
        }
        parseMultipart();
        return Collections.unmodifiableList(parts);
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        if (!isMultipart()) {
            return super.getPart(name);
        }
        return getParts().stream().filter(part -> part.getName().equals(name)).findFirst().orElse(null);
    }

    @Override
    public String getParameter(String name) {
        String[] values = getParameterMap().get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = getParameterMap().get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (!isMultipart()) {
            return super.getParameterMap();
        }
        try {
            parseMultipart();
        } catch (IOException | ServletException e) {
            return asArrays(queryParameters());
        }
        return Collections.unmodifiableMap(parameters);
    }

    private boolean isMultipart() {
        String type = getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    private void parseMultipart() throws IOException, ServletException {
        if (parts != null) {
            return;
        }
        FileUpload upload = new FileUpload();
        upload.setFileItemFactory(new DiskFileItemFactory(body.length + 1, TEMPORARY_DIRECTORY));
        upload.setFileCountMax(MAX_PARTS);
        List<FileItem> items;
        try {
            items = upload.parseRequest(new CachedContext());
        } catch (FileUploadException e) {
            throw new ServletException("The multipart body could not be parsed", e);
        }
        Map<String, List<String>> values = queryParameters();
        List<Part> parsed = new ArrayList<>();
        for (FileItem item : items) {
            parsed.add(new ApplicationPart(item, TEMPORARY_DIRECTORY));
            if (item.isFormField()) {
                values.computeIfAbsent(item.getFieldName(), key -> new ArrayList<>()).add(item.getString(charset().name()));
            }
        }
        this.parameters = asArrays(values);
        this.parts = parsed;
    }

    private Map<String, List<String>> queryParameters() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        String query = getQueryString();
        if (query == null || query.isEmpty()) {
            return values;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            values.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return values;
    }

    private static Map<String, String[]> asArrays(Map<String, List<String>> values) {
        Map<String, String[]> result = new LinkedHashMap<>();
        values.forEach((name, list) -> result.put(name, list.toArray(String[]::new)));
        return result;
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    }

    /** Serves the remembered bytes as the request body. */
    private static final class CachedInputStream extends ServletInputStream {

        private final ByteArrayInputStream in;

        private CachedInputStream(ByteArrayInputStream in) {
            this.in = in;
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            return in.read(target, offset, length);
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("The body is already in memory; read it synchronously");
        }
    }

    /** Hands the remembered bytes to Tomcat's multipart parser. */
    private final class CachedContext implements UploadContext {

        @Override
        public String getCharacterEncoding() {
            return charset().name();
        }

        @Override
        public String getContentType() {
            return CachedBodyRequest.this.getContentType();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public long contentLength() {
            return body.length;
        }
    }
}
