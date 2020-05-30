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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * The servlet filter chain.
 */
public class ServletFilterChain implements FilterChain {

    /**
     * Consider that each request is handled by only one thread, and that the ServletContext will create a new
     * SimpleFilterChain object on each request therefore, the FilterChain's Iterator is used as a private
     * variable of the FilterChain, without thread safety problems.
     */
    private final List<FilterRegistration> filterRegistrationList = new ArrayList<>();
    private final ServletRegistration servletRegistration;
    private int pos;

    /**
     * Get new instance.
     */
    public ServletFilterChain(ServletRegistration servletRegistration) {
        requireNonNull(servletRegistration, "servletRegistration");
        this.servletRegistration = servletRegistration;
    }

    /**
     * each Filter calls the FilterChain method after processing the request.
     * this should find the next Filter, call its doFilter() method.
     * if there is no next one, you should call the servlet's service() method
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {
        requireNonNull(request, "request");
        requireNonNull(response, "response");

        if (pos == 0) {
            if (servletRegistration.isInitServletCas(false, true)) {
                servletRegistration.getServlet().init(servletRegistration.getServletConfig());
            }
        }

        if (pos < filterRegistrationList.size()) {
            pos++;
            filterRegistrationList.get(pos).getFilter().doFilter(request, response, this);
        } else {
            servletRegistration.getServlet().service(request, response);
        }
    }

    /**
     * Get servlet registration.
     */
    public ServletRegistration getServletRegistration() {
        return servletRegistration;
    }

    /**
     * Get filter registration list.
     */
    public List<FilterRegistration> getFilterRegistrationList() {
        return filterRegistrationList;
    }
}
