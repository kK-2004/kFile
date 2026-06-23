package com.kk.kmessage;

import com.kk.config.KMessageProperties;
import com.kk2004.kmessage.sdk.KMessageClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仅在 app.kmessage.enabled=true 时装配 KMessageClient 单例。
 */
@Configuration
@ConditionalOnProperty(name = "app.kmessage.enabled", havingValue = "true")
public class KMessageConfig {

    @Bean
    public KMessageClient kMessageClient(KMessageProperties props) {
        return new KMessageClient(props.getEndpoint(), props.getAppKey(), props.getAppSecret());
    }
}
