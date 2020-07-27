/*
 * Copyright 2019 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServletContextPathTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final ServletBuilder servletBuilder = new ServletBuilder(sb, "/foo");
            servletBuilder.servlet("root", new HomeServletTest(), "/")
                          .servlet("bar", new BarServletTest(), "/bar")
                          .servlet("end", new EndServletTest(), "/end/")
                          .servlet("servlet_path",
                                   new PathInfoServletTest(), "/servlet/path/*")
                          .build();
        }
    };

    @Test
    void doGet() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res;

        res = client.get("/foo").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get home");

        res = client.get("/foo/").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get home");

        res = client.get("/foo/bar").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get bar");

        res = client.get("/foo/bar/").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get bar");

        res = client.get("/foo/end").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get end");

        res = client.get("/foo/end/").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get end");

        res = client.get("/foo/servlet/path/path/info").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get path info");
    }

    private static class HomeServletTest extends HttpServlet {
        private static final long serialVersionUID = -4749186642363952824L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get home");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    private static class BarServletTest extends HttpServlet {
        private static final long serialVersionUID = 5296682765383130525L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                // Context path: "/foo" and servlet path: "/bar"
                assertThat(((ServletRequestDispatcher) request.getServletContext().getRequestDispatcher(
                        "/bar")).name()).isEqualTo("bar");
                assertThat(((ServletRequestDispatcher) request.getServletContext().getRequestDispatcher(
                        "/bar/")).name()).isEqualTo("bar");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get bar");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    private static class EndServletTest extends HttpServlet {
        private static final long serialVersionUID = -3895334662700600258L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                // Context path: "/foo" and servlet path: "/end/"
                assertThat(((ServletRequestDispatcher) request.getServletContext().getRequestDispatcher(
                        "/end")).name()).isEqualTo("end");
                assertThat(((ServletRequestDispatcher) request.getServletContext().getRequestDispatcher(
                        "/end/")).name()).isEqualTo("end");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get end");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    static class PathInfoServletTest extends HttpServlet {
        private static final long serialVersionUID = 7385832026109110130L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                // Context path: "/foo" and servlet path: "/servlet/path" path info: "/path/info"
                assertThat(request.getContextPath()).isEqualTo("/foo");
                assertThat(request.getServletPath()).isEqualTo("/servlet/path");
                assertThat(request.getPathInfo()).isEqualTo("/path/info");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get path info");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }
}
