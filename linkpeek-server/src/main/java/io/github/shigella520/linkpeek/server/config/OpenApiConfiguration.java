package io.github.shigella520.linkpeek.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    public OpenAPI linkPeekOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LinkPeek API")
                        .description("通用链接预览服务接口，当前提供预览入口、健康检查与媒体代理路由。")
                        .version("0.1.0-SNAPSHOT")
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
