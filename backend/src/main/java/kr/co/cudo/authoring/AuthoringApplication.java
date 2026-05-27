package kr.co.cudo.authoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class AuthoringApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthoringApplication.class, args);
    }
}
