package org.stagepass.notificationservice.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.stagepass.notificationservice.exception.NotificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    @Autowired
    private final TemplateEngine emailTemplateEngine;

    /**
     * Renders a Thymeleaf HTML template from src/main/resources/templates/.
     *
     * @param templateName the template file name WITHOUT the .html suffix (e.g. "booking-confirmed")
     * @param variables    map of variables to expose in the template (e.g. eventName, seats, totalAmount)
     * @return fully rendered HTML string, ready to be set as a MimeMessage body
     */
    public String render(String templateName, Map<String, Object> variables) {
        try {
            Context context = new Context();
            context.setVariables(variables);

            String html = emailTemplateEngine.process(templateName, context);
            log.debug("Template '{}' rendered successfully", templateName);
            return html;

        } catch (Exception ex) {
            log.error("Failed to render template '{}': {}", templateName, ex.getMessage());
            throw new NotificationException("Template rendering failed for: " + templateName, ex);
        }
    }
}