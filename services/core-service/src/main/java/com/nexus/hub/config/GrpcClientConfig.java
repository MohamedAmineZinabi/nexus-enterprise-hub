package com.nexus.hub.config;

import com.nexus.hub.grpc.ai.v1.AgentServiceGrpc;
import com.nexus.hub.grpc.ai.v1.RagServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class GrpcClientConfig {

    @Value("${nexus.grpc.ai-service.host:localhost}")
    private String host;

    @Value("${nexus.grpc.ai-service.port:50051}")
    private int port;

    @Value("${nexus.grpc.ai-service.keep-alive-time-seconds:30}")
    private long keepAliveTimeSeconds;

    private ManagedChannel channel;

    @Bean
    public ManagedChannel aiServiceManagedChannel() {
        log.info("Initializing gRPC ManagedChannel to AI Service at {}:{}", host, port);
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(keepAliveTimeSeconds, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .maxInboundMessageSize(20 * 1024 * 1024)
                .build();
        return this.channel;
    }

    @Bean
    public RagServiceGrpc.RagServiceBlockingStub ragServiceBlockingStub(ManagedChannel channel) {
        return RagServiceGrpc.newBlockingStub(channel);
    }

    @Bean
    public AgentServiceGrpc.AgentServiceBlockingStub agentServiceBlockingStub(ManagedChannel channel) {
        return AgentServiceGrpc.newBlockingStub(channel);
    }

    @Bean
    public AgentServiceGrpc.AgentServiceStub agentServiceStub(ManagedChannel channel) {
        return AgentServiceGrpc.newStub(channel);
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            log.info("Gracefully shutting down gRPC ManagedChannel for AI Service...");
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("gRPC ManagedChannel did not shut down within 5s, forcing shutdownNow()...");
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while awaiting gRPC ManagedChannel shutdown", e);
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
