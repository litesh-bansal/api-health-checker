package com.uidai.enu.extapipulse.alert;

import com.uidai.enu.extapipulse.incident.AlertEvent;

import java.util.Set;

/**
 * One way of telling people (email today, Jira later). Each implementation is a Spring bean;
 * routing in config refers to it by {@link #name()}, e.g. {@code channels: [email, jira]}.
 */
public interface AlertChannel {

    String name();

    void send(AlertEvent event, Set<String> recipients);
}
