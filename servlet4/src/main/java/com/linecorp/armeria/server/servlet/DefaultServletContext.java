/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.servlet;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpServlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

final class DefaultServletContext implements ServletContext {
    private static final Logger logger = LoggerFactory.getLogger(DefaultServletContext.class);
    private static final Set<SessionTrackingMode> defaultSessionTrackingModeSet =
            Sets.immutableEnumSet(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);

    private final LogLevel level;
    private final ServletUrlMapper servletUrlMapper = new ServletUrlMapper();
    private final Map<String, Object> attributeMap = new HashMap<>();
    private final String contextPath;
    private final String servletContextName;

    private int sessionTimeoutMinutes = 30; // TODO add setters.
    private boolean initialized;
    private Map<String, String> initParamMap = new HashMap<>();
    private Map<String, DefaultServletRegistration> servletRegistrations = new HashMap<>();
    private Map<String, String> mimeMappings = new HashMap<>();
    private Set<SessionTrackingMode> sessionTrackingModeSet = defaultSessionTrackingModeSet;
    private String requestCharacterEncoding = ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET.name();
    private String responseCharacterEncoding = ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET.name();

    DefaultServletContext(String contextPath) {
        this(contextPath, LogLevel.DEBUG);
    }

    DefaultServletContext(String contextPath, LogLevel level) {
        requireNonNull(contextPath, "contextPath");
        requireNonNull(level, "level");
        this.contextPath = contextPath;
        this.level = level;
        if (contextPath.isEmpty()) {
            servletContextName = "";
        } else {
            servletContextName = contextPath.substring(1).replace("/", "_").trim();
        }
    }

    boolean isInitialized() {
        return initialized;
    }

    /**
     * Set server started.
     */
    void init() {
        initialized = true;
        initParamMap = ImmutableMap.copyOf(initParamMap);
        servletRegistrations = ImmutableMap.copyOf(servletRegistrations);
        sessionTrackingModeSet = ImmutableSet.copyOf(sessionTrackingModeSet);
        mimeMappings = ImmutableMap.copyOf(mimeMappings);
    }

    void mimeMapping(String extension, String mimeType) {
        checkArgument(!isNullOrEmpty(extension),
                      "extension: %s (expected: not null and empty)", extension);
        checkArgument(!isNullOrEmpty(mimeType),
                      "mimeType: %s (expected: not null and empty)", mimeType);
        checkArgument(mimeType.contains("/"),
                      "mimeType: %s (expected: must contain '/' character)", mimeType);
        mimeMappings.put(extension, mimeType);
    }

    void mimeMappings(Map<String, String> mappings) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            checkArgument(!isNullOrEmpty(entry.getKey()),
                          "extension: %s (expected: not null and empty)", entry.getKey());
            checkArgument(!isNullOrEmpty(entry.getValue()),
                          "mimeType: %s (expected: not null and empty)", entry.getValue());
            checkArgument(entry.getValue().contains("/"),
                          "mimeType: %s (expected: must contain '/' character)", entry.getValue());
        }
        mimeMappings.putAll(requireNonNull(mappings, "mappings"));
    }

    @Override
    public int getSessionTimeout() {
        return sessionTimeoutMinutes;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public DefaultServletContext getContext(String uripath) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMajorVersion() {
        return 4;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 4;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    @Nullable
    public String getMimeType(@Nullable String file) {
        if (file == null) {
            return null;
        }
        final int period = file.lastIndexOf('.');
        if (period < 0) {
            return null;
        }
        final String extension = file.substring(period + 1);
        if (extension.isEmpty()) {
            return null;
        }
        return mimeMappings.get(extension);
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRealPath(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Nullable
    public ServletRequestDispatcher getRequestDispatcher(String path) {
        requireNonNull(path, "path");
        final Map.Entry<String, DefaultServletRegistration> pair = servletUrlMapper.getMapping(path);
        if (pair == null) {
            return null;
        }

        // TODO Integrate this logic into servletUrlMapper.
        final String pathPattern = pair.getKey();
        final String servletPath;
        final String pathInfo;
        if (pathPattern.endsWith("/*")) {
            // pathPattern: "/lawn/*"
            // path: "/lawn/index.html" then,
            // servletPath: "/lawn"
            // pathInfo: "/index.html"
            servletPath = pathPattern.substring(0, pathPattern.length() - 2);
            pathInfo = path.substring(servletPath.length());
        } else {
            // pathPattern starts with "*." or exact path.
            servletPath = path;
            pathInfo = null;
        }

        return new ServletRequestDispatcher(new ServletFilterChain(pair.getValue()), pair.getValue().getName(),
                                            servletPath, pathInfo);
    }

    @Override
    @Nullable
    public ServletRequestDispatcher getNamedDispatcher(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Nullable
    public Servlet getServlet(String name) throws ServletException {
        // This method is deprecated and should return null.
        return null;
    }

    @Override
    public Enumeration<Servlet> getServlets() {
        // This method is deprecated and should return an empty set.
        return Collections.enumeration(ImmutableSet.of());
    }

    @Override
    public Enumeration<String> getServletNames() {
        // This method is deprecated and should return an empty set.
        return Collections.enumeration(ImmutableSet.of());
    }

    @Override
    public void log(String message) {
        requireNonNull(message, "message");
        level.log(logger, message);
    }

    @Override
    public void log(Exception exception, String message) {
        requireNonNull(exception, "exception");
        requireNonNull(message, "message");
        level.log(logger, message, exception);
    }

    @Override
    public void log(String message, Throwable throwable) {
        requireNonNull(message, "message");
        requireNonNull(throwable, "throwable");
        level.log(logger, message, throwable);
    }

    @Override
    public String getServerInfo() {
        return ArmeriaHttpUtil.SERVER_HEADER +
               " (JDK " + SystemInfo.javaVersion() + ';' + SystemInfo.osType().name() + ')';
    }

    @Override
    public String getInitParameter(String name) {
        requireNonNull(name, "name");
        return initParamMap.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParamMap.keySet());
    }

    @Override
    public boolean setInitParameter(String name, @Nullable String value) {
        ensureUninitialized("setInitParameter");
        requireNonNull(name, "name");
        return initParamMap.putIfAbsent(name, value) == null;
    }

    @Override
    public Object getAttribute(String name) {
        requireNonNull(name, "name");
        return attributeMap.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(ImmutableSet.copyOf(attributeMap.keySet()));
    }

    @Override
    public void setAttribute(String name, @Nullable Object object) {
        requireNonNull(name);
        if (object == null) {
            removeAttribute(name);
            return;
        }
        attributeMap.put(name, object);
    }

    @Override
    public void removeAttribute(String name) {
        requireNonNull(name, "name");
        attributeMap.remove(name);
    }

    @Override
    public String getServletContextName() {
        return servletContextName;
    }

    private void addUrlPatterns(String servletName, @Nullable DefaultServletRegistration registration,
                                String... urlPatterns) {
        if (registration == null) {
            return;
        }
        final Set<String> conflicts = registration.addMapping(urlPatterns);
        if (!conflicts.isEmpty()) {
            servletRegistrations.remove(servletName);
            throw new IllegalArgumentException(conflicts + " are mapped already in urlPatterns: " +
                                               Arrays.toString(urlPatterns));
        }
    }

    void addServlet(String servletName, String className, String... urlPatterns) {
        final DefaultServletRegistration registration = addServlet(servletName, className);
        addUrlPatterns(servletName, registration, urlPatterns);
    }

    void addServlet(String servletName, HttpServlet httpServlet, String... urlPatterns) {
        final DefaultServletRegistration registration = addServlet(servletName, httpServlet);
        addUrlPatterns(servletName, registration, urlPatterns);
    }

    void addServlet(String servletName, Class<? extends Servlet> servletClass, String... urlPatterns) {
        final DefaultServletRegistration registration = addServlet(servletName, servletClass);
        addUrlPatterns(servletName, registration, urlPatterns);
    }

    @Nullable
    @Override
    public DefaultServletRegistration addServlet(String servletName, String className) {
        requireNonNull(className, "className");
        try {
            //noinspection unchecked
            return addServlet(servletName, (Class<HttpServlet>) Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to add a servlet. servletName: " + servletName +
                                       ", className: " + className, e);
        }
    }

    @Nullable
    @Override
    public DefaultServletRegistration addServlet(String servletName, Servlet servlet) {
        ensureUninitialized("addServlet");
        checkArgument(!isNullOrEmpty(servletName),
                      "servletName: %s (expected: not null and empty)", servletName);
        requireNonNull(servlet, "servlet");
        if (servletRegistrations.containsKey(servletName)) {
            logger.warn("{} is registered already.", servletName);
            return null;
        }
        final DefaultServletRegistration servletRegistration =
                new DefaultServletRegistration(servletName, servlet, this, servletUrlMapper, initParamMap);
        if (servletRegistrations.containsValue(servletRegistration)) {
            logger.warn("{} is registered already.", servlet);
            return null;
        }
        servletRegistrations.put(servletName, servletRegistration);
        return servletRegistration;
    }

    @Nullable
    @Override
    public DefaultServletRegistration addServlet(String servletName, Class<? extends Servlet> servletClass) {
        requireNonNull(servletClass, "servletClass");
        try {
            return addServlet(servletName, createServlet(servletClass));
        } catch (ServletException e) {
            throw new RuntimeException("Failed to add a servlet. servletName: " + servletName +
                                       ", servletClass: " + servletClass, e);
        }
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        try {
            requireNonNull(clazz, "clazz");
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException("Failed to create a servlet: " + clazz.getSimpleName(), e);
        }
    }

    @Override
    @Nullable
    public DefaultServletRegistration getServletRegistration(String servletName) {
        requireNonNull(servletName, "servletName");
        return servletRegistrations.get(servletName);
    }

    @Override
    public Map<String, DefaultServletRegistration> getServletRegistrations() {
        return servletRegistrations;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return defaultSessionTrackingModeSet;
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return sessionTrackingModeSet;
    }

    @Override
    public void addListener(String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends EventListener> void addListener(T listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public void declareRoles(String... roleNames) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVirtualServerName() {
        return ArmeriaHttpUtil.SERVER_HEADER +
               " (JDK " + SystemInfo.javaVersion() + ';' + SystemInfo.osType().name() + ')';
    }

    @Override
    public String getRequestCharacterEncoding() {
        return requestCharacterEncoding;
    }

    @Override
    public void setRequestCharacterEncoding(String requestCharacterEncoding) {
        ensureUninitialized("setRequestCharacterEncoding");
        requireNonNull(requestCharacterEncoding, "requestCharacterEncoding");
        this.requestCharacterEncoding = requestCharacterEncoding;
    }

    @Override
    public String getResponseCharacterEncoding() {
        return responseCharacterEncoding;
    }

    @Override
    public void setResponseCharacterEncoding(String responseCharacterEncoding) {
        ensureUninitialized("setResponseCharacterEncoding");
        requireNonNull(responseCharacterEncoding, "responseCharacterEncoding");
        this.responseCharacterEncoding = responseCharacterEncoding;
    }

    @Override
    public Dynamic addJspFile(String jspName, String jspFile) {
        throw new UnsupportedOperationException();
    }

    private void ensureUninitialized(String name) {
        requireNonNull(name, "name");
        checkState(!initialized, "Can't execute %s after the servlet context is initialized.", name);
    }
}
