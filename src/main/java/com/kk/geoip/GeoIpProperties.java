package com.kk.geoip;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "geoip")
public class GeoIpProperties {
    private boolean enabled = false;
    private String mmdbPath; // Absolute or classpath path to MMDB
}

