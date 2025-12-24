package ru.agimate.connectorsapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "ru.agimate.connectorsapi",
        "ru.agimate.common"
})
public class ConnectorsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectorsApiApplication.class, args);
    }
}