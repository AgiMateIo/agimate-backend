package ru.agimate.connectorsapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import ru.agimate.device.v1.DeviceApiServiceGrpc;
import ru.agimate.user.v1.UserApiServiceGrpc;

@Configuration
public class GrpcClientConfig {

    @Bean
    public DeviceApiServiceGrpc.DeviceApiServiceBlockingStub deviceApiStub(GrpcChannelFactory channels) {
        return DeviceApiServiceGrpc.newBlockingStub(channels.createChannel("device-api"));
    }

    @Bean
    public DeviceApiServiceGrpc.DeviceApiServiceStub deviceApiAsyncStub(GrpcChannelFactory channels) {
        return DeviceApiServiceGrpc.newStub(channels.createChannel("device-api"));
    }

    @Bean
    public UserApiServiceGrpc.UserApiServiceBlockingStub userApiStub(GrpcChannelFactory channels) {
        return UserApiServiceGrpc.newBlockingStub(channels.createChannel("user-api"));
    }
}
