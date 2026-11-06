package no.scienta.cfp;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.UrlHandlerFilter;

/**
 * Web behaviour that Spring Boot 2.7 had by default and later versions dropped, kept so clients see no change.
 */
@Configuration
public class WebCompatibilityConfig {

    /**
     * /speakers/ and /speakers/{id}/ answer like /speakers and /speakers/{id}: the legacy web client adds the slash
     * (Speakers spec). Spring MVC matched a trailing slash by default until Spring 6; this keeps that for every path,
     * as before. The request is rewritten rather than redirected, so a POST to /speakers/ still works.
     */
    @Bean
    public UrlHandlerFilter trailingSlashFilter() {
        return UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build();
    }

    /**
     * Error responses say "application/json;charset=UTF-8" like every other response, as they did on Boot 2.7.
     * spring.servlet.encoding.force covers the request dispatch only, and the error body is written in the error
     * dispatch.
     */
    @Bean
    public FilterRegistrationBean<CharacterEncodingFilter> errorDispatchEncodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter(StandardCharsets.UTF_8.name(), true) {
            @Override
            protected boolean shouldNotFilterErrorDispatch() {
                return false;
            }
        };
        FilterRegistrationBean<CharacterEncodingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
