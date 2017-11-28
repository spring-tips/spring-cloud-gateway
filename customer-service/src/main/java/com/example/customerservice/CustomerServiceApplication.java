package com.example.customerservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@SpringBootApplication
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }

    @Bean
    RouterFunction<?> routes(CustomerRepository repo) {
        return RouterFunctions
                .route(GET("/customers"), r -> ok().body(repo.findAll(), Customer.class))
                .andRoute(GET("/delay"), r -> ok().body(
                        Flux.<String>generate(s -> s.next("Hello, world")).take(2).delayElements(Duration.ofSeconds(20)), String.class))
                .andRoute(GET("/customers/{id}"), r -> ok().body(repo.findById(r.pathVariable("id")), Customer.class));
    }

    private static Mono<ServerResponse> log(ServerRequest r, Mono<ServerResponse> responseMono) {

        return responseMono;
    }

    @Bean
    ApplicationRunner init(CustomerRepository repository) {
        return a -> repository
                .deleteAll()
                .thenMany(Flux.just("A", "B", "C").flatMap(x -> repository.save(new Customer(null, x))))
                .thenMany(repository.findAll())
                .subscribe(System.out::println);
    }
}

interface CustomerRepository extends ReactiveMongoRepository<Customer, String> {
}

@Document
@Data
@AllArgsConstructor
class Customer {
    private String id;
    private String email;
}