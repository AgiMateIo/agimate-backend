package ru.agimate.deviceapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import ru.agimate.common.security.apikey.ApiKeyAuthenticationFilter;
import ru.agimate.common.security.apikey.ApiKeyIntrospectService;
import ru.agimate.connectors.v1.ConnectorsEventServiceGrpc;
import ru.agimate.user.v1.UserApiServiceGrpc;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class GrpcClientConfig {

    @Bean
    public UserApiServiceGrpc.UserApiServiceBlockingStub userApiStub(GrpcChannelFactory channels) {
        return UserApiServiceGrpc.newBlockingStub(channels.createChannel("user-api"));
    }

    @Bean
    public ApiKeyIntrospectService apiKeyIntrospectService(UserApiServiceGrpc.UserApiServiceBlockingStub userApiStub) {
        return new ApiKeyIntrospectService(userApiStub);
    }

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyIntrospectService apiKeyIntrospectService) {
        return new ApiKeyAuthenticationFilter(apiKeyIntrospectService);
    }

    @Bean
    public ConnectorsEventServiceGrpc.ConnectorsEventServiceBlockingStub connectorsEventStub(
            GrpcChannelFactory channels) {
        return ConnectorsEventServiceGrpc.newBlockingStub(
                channels.createChannel("connectors-api"));
    }
}
