package org.stagepass.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

/**
 * THYMELEAF CONFIG
 *
 * Configures a dedicated Thymeleaf engine for rendering HTML email templates.
 *
 * WHY A SEPARATE ENGINE?
 * Spring Boot auto-configures a Thymeleaf engine for web (MVC) views.
 * Email templates are NOT web views — they are rendered in a service class
 * (TemplateService) and the output is passed to JavaMailSender.
 *
 * Using the auto-configured engine for emails works but couples email rendering
 * to Spring MVC's request context. A separate engine avoids this coupling
 * and can be used from Kafka consumer threads safely (no request context needed).
 *
 * TEMPLATE LOCATION:
 * Templates loaded from: src/main/resources/templates/
 * Template names passed to engine: 'booking-confirmed', 'welcome', etc.
 * Full path resolved as: classpath:/templates/booking-confirmed.html
 *
 * TEMPLATE VARIABLES (used in .html files):
 * ${userName}, ${eventName}, ${seats}, ${totalAmount}, ${bookingId}, etc.
 * Thymeleaf syntax: <span th:text="${userName}">placeholder</span>
 *
 * CACHING:
 * cacheable = true in production — templates are compiled and cached after
 * first render. Set cacheable = false during local development to see
 * template changes without restarting the service.
 */
@Configuration
public class ThymeleafConfig {

    @Bean(name = "emailTemplateEngine")
    public TemplateEngine emailTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(emailTemplateResolver());
        return engine;
    }

    private ITemplateResolver emailTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();

        // Templates live in src/main/resources/templates/
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");

        // HTML mode — parse as full HTML document
        resolver.setTemplateMode(TemplateMode.HTML);

        // UTF-8 — handles Indian characters in event names, venue names
        resolver.setCharacterEncoding("UTF-8");

        // Cache compiled templates — set to false locally for hot-reload
        resolver.setCacheable(true);

        // Priority 1 — only resolver in this engine
        resolver.setOrder(1);

        return resolver;
    }
}