package com.foodx.orderservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Cấu hình RestTemplate có load balancing.
 *
 * Nhờ @LoadBalanced, order-service gọi restaurant-service bằng TÊN LOGIC
 * (http://restaurant-service/...) thay vì IP cứng. Spring Cloud LoadBalancer
 * lấy danh sách instance đang sống từ Eureka và phân phối tải đều cho cả 4 instance,
 * tự động loại bỏ instance đã bị Eureka gỡ khỏi danh bạ.
 */
@Configuration
public class RestClientConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
