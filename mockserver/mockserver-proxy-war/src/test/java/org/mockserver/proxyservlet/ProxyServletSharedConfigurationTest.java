package org.mockserver.proxyservlet;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.HttpState;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * The servlet must run on ONE {@link Configuration}: a component built from its own default instance
 * would not see a change made to the servlet's (the netty server had this split, which let a runtime
 * configuration change be echoed back but never enforced).
 */
public class ProxyServletSharedConfigurationTest {

    @Test
    public void everyComponentMustShareTheServletConfiguration() throws Exception {
        ProxyServlet servlet = new ProxyServlet();
        try {
            Configuration configuration = (Configuration) read(servlet, "configuration");
            assertThat(configuration, notNullValue());

            HttpState httpState = (HttpState) read(servlet, "httpStateHandler");
            assertThat("HttpState", httpState.getConfiguration(), sameInstance(configuration));
            assertThat("Scheduler", read(read(servlet, "scheduler"), "configuration"), sameInstance(configuration));
            assertThat("HttpActionHandler", read(read(servlet, "actionHandler"), "configuration"), sameInstance(configuration));
        } finally {
            servlet.destroy();
        }
    }

    @Test
    public void theResponseWriterEachRequestBuildsMustUseTheServletConfiguration() throws Exception {
        ProxyServlet servlet = new ProxyServlet();
        try {
            // set on the servlet's instance only, so a writer built from any other instance omits it
            Configuration configuration = (Configuration) read(servlet, "configuration");
            configuration.defaultResponseHeaders("X-Servlet-Configuration=shared");

            MockHttpServletResponse response = new MockHttpServletResponse();
            servlet.service(new MockHttpServletRequest("PUT", "/mockserver/status"), response);

            assertThat(response.getStatus(), is(200));
            assertThat("the per-request ServletResponseWriter must be built on the servlet's Configuration",
                response.getHeader("X-Servlet-Configuration"), is("shared"));
        } finally {
            servlet.destroy();
        }
    }

    private static Object read(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
