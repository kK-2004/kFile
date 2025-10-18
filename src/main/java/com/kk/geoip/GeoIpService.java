package com.kk.geoip;

public interface GeoIpService {
    GeoInfo lookup(String ipAddress);
}

