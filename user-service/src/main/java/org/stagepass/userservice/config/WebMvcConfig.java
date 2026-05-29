package org.stagepass.userservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WEB MVC CONFIGURATION
 *
 * Configures automatic view controllers to map static resources to clean URLs.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward /auth and /auth/ directly to the static index.html SPA
        registry.addViewController("/auth").setViewName("forward:/auth/index.html");
        registry.addViewController("/auth/").setViewName("forward:/auth/index.html");
    }
}
