package com.kk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;
import com.kk.config.OssProperties;
import com.kk.geoip.GeoIpProperties;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({OssProperties.class, GeoIpProperties.class})
public class KFileApplication {

    public static void main(String[] args) {
        SpringApplication.run(KFileApplication.class, args);
    }

}
