package ru.agimate.connectorsapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import ru.agimate.mobile.v1.MobileApiServiceGrpc;

@Configuration
public class GrpcClientConfig {

    @Bean
    public MobileApiServiceGrpc.MobileApiServiceBlockingStub mobileApiStub(GrpcChannelFactory channels) {
        return MobileApiServiceGrpc.newBlockingStub(channels.createChannel("mobile-api"));
    }
}
