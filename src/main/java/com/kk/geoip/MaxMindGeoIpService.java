package com.kk.geoip;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.net.InetAddress;

@Slf4j
@Service
@ConditionalOnProperty(value = "geoip.enabled", havingValue = "true")
public class MaxMindGeoIpService implements GeoIpService {
    private final GeoIpProperties props;
    private DatabaseReader reader;

    public MaxMindGeoIpService(GeoIpProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            if (props.getMmdbPath() == null || props.getMmdbPath().isEmpty()) {
                log.warn("GeoIP enabled but mmdbPath is empty");
                return;
            }
            File dbFile = new File(props.getMmdbPath());
            if (!dbFile.exists()) {
                log.warn("GeoIP database not found at {}", props.getMmdbPath());
                return;
            }
            reader = new DatabaseReader.Builder(dbFile).build();
        } catch (Exception e) {
            log.warn("Failed to initialize GeoIP reader: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
    }

    @Override
    public GeoInfo lookup(String ipAddress) {
        try {
            if (reader == null || ipAddress == null || ipAddress.isBlank()) return new GeoInfo("", "", "");
            InetAddress ip = InetAddress.getByName(ipAddress);
            CityResponse resp = reader.city(ip);
            String country = resp.getCountry() != null ? nullToEmpty(resp.getCountry().getNames().get("zh-CN"), resp.getCountry().getName()) : "";
            String province = resp.getMostSpecificSubdivision() != null ? nullToEmpty(resp.getMostSpecificSubdivision().getNames().get("zh-CN"), resp.getMostSpecificSubdivision().getName()) : "";
            String city = resp.getCity() != null ? nullToEmpty(resp.getCity().getNames().get("zh-CN"), resp.getCity().getName()) : "";
            return new GeoInfo(country, province, city);
        } catch (Exception e) {
            return new GeoInfo("", "", "");
        }
    }

    private String nullToEmpty(String a, String b) { return a != null ? a : (b != null ? b : ""); }
}

