package com.uidai.enu.extapipulse.alert;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.incident.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Set;

/**
 * Sends alerts by email. One message per recipient, so a vendor never sees our internal addresses.
 * With {@code pulse.mail.enabled: false} it only logs what it would have sent (handy locally).
 */
public class EmailChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final JavaMailSender mailSender; // null when mail is disabled
    private final AlertFormatter formatter;
    private final ProbeCatalog.MailSettings settings;

    public EmailChannel(JavaMailSender mailSender, AlertFormatter formatter, ProbeCatalog.MailSettings settings) {
        this.mailSender = mailSender;
        this.formatter = formatter;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void send(AlertEvent event, Set<String> recipients) {
        if (recipients.isEmpty()) {
            log.warn("No recipients for {} {} ({}) - check routing.{} in config",
                    event.type(), event.key(), event.category(), event.category());
            return;
        }
        String subject = formatter.subject(event);
        String body = formatter.body(event);

        if (!settings.enabled() || mailSender == null) {
            log.info("[mail disabled] Would email {}\nSubject: {}\n{}", recipients, subject, body);
            return;
        }
        for (String to : recipients) {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(settings.from());
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            try {
                mailSender.send(msg);
            } catch (MailException e) {
                log.error("Could not email {} about {} {}: {}", to, event.type(), event.key(), e.getMessage());
            }
        }
    }
}
