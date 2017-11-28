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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.stream.IntStream;

import static org.springframework.cloud.gateway.handler.predicate.RoutePredicates.path;

@EnableWebFluxSecurity
@SpringBootApplication
public class EdgeServiceApplication {

    @Bean
    ApplicationRunner client() {
        return args -> {
            WebClient client = WebClient.builder().filter(ExchangeFilterFunctions.basicAuthentication("user", "pw")).build();
            Flux.fromStream(IntStream.range(0, 100).boxed())
                    .flatMap(number -> client.get().uri("http://localhost:8081/rl").exchange())
                    .flatMap(clientResponse -> clientResponse.toEntity(String.class).map(re -> String.format("status: %s; body: %s", re.getStatusCodeValue(), re.getBody())))
                    .subscribe(System.out::println);
        };
    }

    @Bean
    MapReactiveUserDetailsService authentication() {
        return new MapReactiveUserDetailsService(
                User.withDefaultPasswordEncoder()
                        .username("user")
                        .password("pw")
                        .roles("USER")
                        .build());
    }

    @Bean
    SecurityWebFilterChain authorization(ServerHttpSecurity security) {
        return security
                .authorizeExchange().pathMatchers("/rl").authenticated()
                .anyExchange().permitAll()
                .and()
                .httpBasic()
                .and()
                .build();
    }

    @Bean
    DiscoveryClientRouteDefinitionLocator discoveryRoutes(DiscoveryClient dc) {
        return new DiscoveryClientRouteDefinitionLocator(dc);
    }

    @Bean
    RouteLocator gatewayRoutes(RequestRateLimiterGatewayFilterFactory rl) {
        return Routes
                .locator()
                // basic proxy
                .route("start")
                .predicate(path("/start"))
                .uri("http://start.spring.io:80/")
                // load balanced proxy
                .route("lb")
                .predicate(path("/lb"))
                .uri("lb://customer-service/customers")
                // custom filter 1
                .route("cf1")
                .predicate(path("/cf1"))
                .filter((exchange, chain) ->
                        chain.filter(exchange).then(Mono.fromRunnable(() -> {
                            ServerHttpResponse httpResponse = exchange.getResponse();
                            httpResponse.setStatusCode(HttpStatus.CONFLICT);
                            httpResponse.getHeaders().setContentType(MediaType.APPLICATION_PDF);
                        }))
                )
                .uri("lb://customer-service/customers")
                // custom filter 2
                .route("cf2")
                .predicate(path("/cf2/**"))
                .filter(GatewayFilters.rewritePath("/cf2/(?<CID>.*)", "/customers/${CID}"))
                .uri("lb://customer-service")
                // circuit breaker
                .route("cb")
                .predicate(path("/cb"))
                .filter(GatewayFilters.hystrix("cb"))
                .uri("lb://customer-service/delay")
                // rate limiter
                .route("rl")
                .predicate(path("/rl"))
                .filter(rl.apply(RedisRateLimiter.args(5, 10)))
                .uri("lb://customer-service/customers")
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(EdgeServiceApplication.class, args);
    }
}
