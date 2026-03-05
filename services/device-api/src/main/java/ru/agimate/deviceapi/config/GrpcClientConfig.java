package ru.agimate.deviceapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import ru.agimate.user.v1.UserApiServiceGrpc;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class GrpcClientConfig {

    @Bean
    public UserApiServiceGrpc.UserApiServiceBlockingStub userApiStub(GrpcChannelFactory channels) {
        return UserApiServiceGrpc.newBlockingStub(channels.createChannel("user-api"));
    }

}
