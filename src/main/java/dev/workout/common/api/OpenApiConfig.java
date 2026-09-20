package dev.workout.common.api;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Workout Tracker internal API")
                .version("0.1.0")
                .description(
                    "Trusted service API. X-Api-Key authenticates the calling service; X-Telegram-User-Id selects the user. Never expose this impersonation-capable API key to client applications. Weights are kg, distance km, duration seconds."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Api-Key"))
                .addSecuritySchemes(
                    "telegramIdentity",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Telegram-User-Id")))
        .addSecurityItem(new SecurityRequirement().addList("apiKey").addList("telegramIdentity"));
  }
}
