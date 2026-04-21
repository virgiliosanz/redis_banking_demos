package com.redis.workshop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code workshop.ams.*} from application.yml. The new AMS-powered use case
 * reads all its AMS coordinates through this single type so controllers/services
 * never parse environment variables directly.
 */
@Component
@ConfigurationProperties(prefix = "workshop.ams")
public class AmsProperties {

    private String baseUrl = "http://localhost:8000";
    private String mcpUrl = "http://localhost:9000";
    private String defaultNamespace = "workshop";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getMcpUrl() { return mcpUrl; }
    public void setMcpUrl(String mcpUrl) { this.mcpUrl = mcpUrl; }

    public String getDefaultNamespace() { return defaultNamespace; }
    public void setDefaultNamespace(String defaultNamespace) { this.defaultNamespace = defaultNamespace; }
}
