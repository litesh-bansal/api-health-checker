package com.uidai.enu.extapipulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtApiPulseApplication {

    public static void main(String[] args) {
        // The JDK HTTP client refuses Basic auth when tunnelling HTTPS through a proxy
        // unless this property is cleared. Needed only if a proxy in config has a username,
        // but it must be set before the HTTP client classes load, so we set it up front.
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
        SpringApplication.run(ExtApiPulseApplication.class, args);
    }
}
