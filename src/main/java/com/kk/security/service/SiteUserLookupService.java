package com.kk.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class SiteUserLookupService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${site.base-url:http://localhost:8081}")
    private String siteBaseUrl;

    public String fetchNickNameByUserId(Long userId) {
        try {
            String url = siteBaseUrl.replaceAll("/+$", "") + "/user/api/home?userId=" + userId;
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;
            JsonNode root = mapper.readTree(resp.getBody());
            // 兼容 ResultVo 结构：{ status:{code:0}, result:{ userHome:{ nickName:..., role:... } } }
            JsonNode result = root.get("result");
            if (result == null) return null;
            JsonNode userHome = result.get("userHome");
            if (userHome == null) return null;
            JsonNode nn = userHome.get("nickName");
            return nn == null || nn.isNull() ? null : nn.asText();
        } catch (Exception e) {
            return null;
        }
    }
}

