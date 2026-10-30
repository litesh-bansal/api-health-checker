package com.uidai.enu.extapipulse.alert;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.incident.AlertEvent;
import com.uidai.enu.extapipulse.incident.IncidentTracker;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Sends an {@link AlertEvent} to the people and channels its failure category is routed to. */
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final ProbeCatalog catalog;
    private final RecipientResolver recipients;
    private final IncidentTracker tracker;
    private final Map<String, AlertChannel> channels;

    public AlertDispatcher(ProbeCatalog catalog, RecipientResolver recipients, IncidentTracker tracker,
                           List<AlertChannel> channels) {
        this.catalog = catalog;
        this.recipients = recipients;
        this.tracker = tracker;
        this.channels = channels.stream().collect(Collectors.toMap(AlertChannel::name, Function.identity()));
        validateRouting();
    }

    /** Fail at startup if routing names a channel that does not exist (e.g. "jira" before it is built). */
    private void validateRouting() {
        StringBuilder errors = new StringBuilder();
        for (FailureCategory category : FailureCategory.values()) {
            for (String name : catalog.route(category).channels()) {
                if (!channels.containsKey(name)) {
                    errors.append("\n  - routing.").append(category).append(": unknown channel '").append(name)
                            .append("' (available: ").append(channels.keySet()).append(')');
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid pulse configuration:" + errors);
        }
    }

    public void dispatch(AlertEvent event, List<String> apiContacts) {
        Route route = catalog.route(event.category());
        Set<String> to;
        if (event.type() == AlertEvent.Type.RECOVERED && !event.previouslyNotified().isEmpty()) {
            to = event.previouslyNotified(); // everyone who heard it was down hears it is back
        } else {
            to = recipients.resolve(route, apiContacts);
        }

        log.info("{} {} {} -> {} via {}", event.type(), event.key(), event.category(), to, route.channels());
        for (String name : route.channels()) {
            AlertChannel channel = channels.get(name);
            if (channel == null) {
                log.error("Channel '{}' not found (validated at startup, so this should not happen)", name);
                continue;
            }
            try {
                channel.send(event, to);
            } catch (RuntimeException e) {
                // One broken channel must not stop the others, or the monitor itself.
                log.error("Channel '{}' failed for {} {}: {}", name, event.type(), event.key(), e.getMessage(), e);
            }
        }
        if (event.type() != AlertEvent.Type.RECOVERED && !to.isEmpty()) {
            tracker.addNotified(event.key(), to);
        }
    }
}
