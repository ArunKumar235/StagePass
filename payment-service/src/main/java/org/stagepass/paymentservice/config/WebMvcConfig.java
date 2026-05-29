package org.stagepass.paymentservice.config;

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
        // Forward /payments/pay directly to the static index.html resource
        registry.addViewController("/payments/pay").setViewName("forward:/payments/index.html");

        registry.addViewController("/payments/pay/").setViewName("forward:/payments/index.html");
    }
}
