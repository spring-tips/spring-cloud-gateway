package com.example.edgeservice;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator;
import org.springframework.cloud.gateway.filter.factory.GatewayFilters;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.Routes;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.stream.IntStream;

import static org.springframework.cloud.gateway.filter.factory.GatewayFilters.hystrix;
import static org.springframework.cloud.gateway.filter.factory.GatewayFilters.rewritePath;
import static org.springframework.cloud.gateway.handler.predicate.RoutePredicates.host;
import static org.springframework.cloud.gateway.handler.predicate.RoutePredicates.path;


/**
 * @todo demo this endpoint http://localhost:8081/application/gateway/routes
 */
@SpringBootApplication
@EnableWebFluxSecurity
public class EdgeServiceApplication {

    // this works by itself.
    @Bean
    DiscoveryClientRouteDefinitionLocator routes(DiscoveryClient dc) {
        return new DiscoveryClientRouteDefinitionLocator(dc);
    }

    @Bean
    ReactiveUserDetailsService userDetailsRepository() {
        UserDetails user = User
                .withDefaultPasswordEncoder()
                .username("user")
                .password("password")
                .roles("USER")
                .build();
        return new MapReactiveUserDetailsService(user);
    }

    @Bean
    ApplicationRunner rl() {
        WebClient client = WebClient.create().mutate().filter(ExchangeFilterFunctions.basicAuthentication("user", "password")).build();
        return args -> Flux
                .fromStream(IntStream.range(0, 100).boxed())
                .flatMap(i -> client.get().uri("http://localhost:8081/rl").exchange())
                .flatMap(cr -> cr.toEntity(String.class).map(re -> String.format("status: %s; body: %s", re.getStatusCode().value(), re.getBody())))
                .subscribe(System.out::println);
    }

    @Bean
    RouteLocator gwRoutes(RequestRateLimiterGatewayFilterFactory rateLimiter) {
        String loadBalancedCustomerService = "lb://customer-service/customers";
        return Routes
                .locator()
                // basic proxy
                .route("start")
                .predicate(path("/start"))
                .uri("http://start.spring.io:80/")
                // load-balanced requests
                .route("cs")
                .predicate(path("/cs"))
                .uri(loadBalancedCustomerService)
                // rate limiter
                .route("rl")
                .predicate(path("/rl"))
                .filter(rateLimiter.apply(RedisRateLimiter.args(5, 10)))
                .uri(loadBalancedCustomerService)
                // circuit breaker
                .route("cb")
                .predicate(path("/cb"))
                .filter(hystrix("cb"))
                .uri("lb://customer-service/delay")
                // custom filter #1
                .route("custom-filter-1")
                .predicate(path("/cf1"))
                .filter((exchange, filter) -> filter
                        .filter(exchange)
                        .then(Mono.fromRunnable(() -> {
                            ServerHttpResponse response = exchange.getResponse();
                            response.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
                            response.setStatusCode(HttpStatus.CONFLICT);
                        })))
                .uri(loadBalancedCustomerService)
                // custom filter #2: curl -v http://localhost:8081/cf2/5a1bfecdd95bb0425fbeaf4d
                .route("custom-filter-2")
                .predicate(path ("/cf2/**"))
                .filter(rewritePath("/cf2/(?<CID>.*)", "/customers/${CID}"))
                .uri("lb://customer-service")
                .build();
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) throws Exception {
        return http
                .httpBasic()
                .and()
                .authorizeExchange().pathMatchers("/rl**", "/anything/**").authenticated()
                .anyExchange().permitAll()
                .and()
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(EdgeServiceApplication.class, args);
    }
}
