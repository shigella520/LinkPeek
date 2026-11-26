package io.github.shigella520.linkpeek.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The admin UI is split into native ES modules. Imported modules cannot carry the page's
 * {@code ?v=} cache-busting query, so they are served with {@code no-cache}: browsers keep
 * them but revalidate on every load (a cheap 304), and a deploy never mixes old and new modules.
 */
@Configuration
public class AdminStaticResourceConfiguration implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/js/**")
                .addResourceLocations("classpath:/static/admin/js/")
                .setCacheControl(CacheControl.noCache());
    }
}
