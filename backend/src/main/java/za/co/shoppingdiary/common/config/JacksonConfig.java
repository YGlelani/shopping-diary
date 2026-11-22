
package za.co.shoppingdiary.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /**
     * Provides the Jackson 2 ObjectMapper required by
     * existing services such as LoyaltyHubService.
     *
     * Spring Boot 4 uses Jackson 3 by default, so this
     * legacy mapper must be registered explicitly.
     */
    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules();
    }
}
