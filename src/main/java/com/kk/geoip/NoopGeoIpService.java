package com.kk.geoip;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "geoip.enabled", havingValue = "false", matchIfMissing = true)
public class NoopGeoIpService implements GeoIpService {
    @Override
    public GeoInfo lookup(String ipAddress) {
        return new GeoInfo("", "", "");
    }
}

