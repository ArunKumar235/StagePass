package org.stagepass.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * MAIL CONFIG
 *
 * Configures JavaMailSender for sending HTML emails with PDF attachments.
 *
 * SMTP PROVIDER OPTIONS:
 *
 * Local development → Mailtrap (https://mailtrap.io)
 *   Free fake SMTP inbox. Catches all emails without delivering them.
 *   Perfect for testing — you see the rendered email in Mailtrap's UI.
 *   host: smtp.mailtrap.io, port: 587, username/password from Mailtrap dashboard
 *
 * Production → SendGrid or AWS SES
 *   SendGrid: host: smtp.sendgrid.net, port: 587, username: apikey, password: your_api_key
 *   AWS SES:  host: email-smtp.{region}.amazonaws.com, port: 587, SMTP credentials from SES console
 *
 * All credentials injected via environment variables — never hardcoded.
 *
 * CONNECTION POOL:
 * JavaMailSenderImpl creates a new SMTP connection per email by default.
 * For high throughput, enable SMTP session reuse via:
 *   mail.smtp.connectiontimeout, mail.smtp.timeout
 * For StagePass V1, per-email connections are fine — volume is low.
 */
@Configuration
public class MailConfig {

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.port}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:false}")
    private boolean authEnabled;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}")
    private boolean starttlsEnabled;

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        
        if (username != null && !username.isBlank()) {
            mailSender.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            mailSender.setPassword(password);
        }

        // UTF-8 encoding — handles special characters in event names, user names
        mailSender.setDefaultEncoding("UTF-8");

        Properties props = mailSender.getJavaMailProperties();

        // SMTP authentication
        props.put("mail.smtp.auth", String.valueOf(authEnabled));

        // STARTTLS — upgrades plain connection to encrypted
        props.put("mail.smtp.starttls.enable", String.valueOf(starttlsEnabled));
        if (starttlsEnabled) {
            props.put("mail.smtp.starttls.required", "true");
        } else {
            props.put("mail.smtp.starttls.required", "false");
        }

        // Timeouts — fail fast if SMTP server is unreachable
        // Prevents Kafka consumer thread from hanging indefinitely
        props.put("mail.smtp.connectiontimeout", "5000"); // 5s to establish connection
        props.put("mail.smtp.timeout",           "5000"); // 5s for SMTP command response
        props.put("mail.smtp.writetimeout",      "5000"); // 5s for data write

        // Debug mode — set to true locally to see full SMTP exchange in logs
        // NEVER enable in production — logs contain email content
        props.put("mail.debug", "false");

        return mailSender;
    }
}