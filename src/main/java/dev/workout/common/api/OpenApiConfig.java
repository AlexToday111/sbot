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
                .title("Внутренний API дневника тренировок")
                .version("0.1.0")
                .description(
                    "API для доверенных сервисов. X-Api-Key проверяет вызывающий сервис; X-Telegram-User-Id выбирает пользователя. Не передавайте ключ клиентским приложениям: он позволяет действовать от имени любого пользователя. Вес в кг, расстояние в км, длительность в секундах."))
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
