package com.nexus.hub.config;

import com.nexus.hub.grpc.ai.v1.AgentServiceGrpc;
import com.nexus.hub.grpc.ai.v1.RagServiceGrpc;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class GrpcClientConfig {

    /**
     * ASCII gRPC metadata header carrying the shared internal service secret expected by ai-service.
     */
    public static final String INTERNAL_API_KEY_HEADER = "x-internal-api-key";
    public static final Metadata.Key<String> INTERNAL_API_KEY_METADATA_KEY =
            Metadata.Key.of(INTERNAL_API_KEY_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    @Value("${nexus.grpc.ai-service.host:localhost}")
    private String host;

    @Value("${nexus.grpc.ai-service.port:50051}")
    private int port;

    @Value("${nexus.grpc.ai-service.keep-alive-time-seconds:30}")
    private long keepAliveTimeSeconds;

    // No default on purpose: an unresolvable placeholder aborts startup.
    @Value("${nexus.ai-service.internal-api-key}")
    private String internalApiKey;

    private ManagedChannel channel;

    @Bean
    public ManagedChannel aiServiceManagedChannel() {
        ClientInterceptor apiKeyInterceptor = internalApiKeyInterceptor(internalApiKey);
        log.info("Initializing gRPC ManagedChannel to AI Service at {}:{}", host, port);
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(keepAliveTimeSeconds, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .maxInboundMessageSize(20 * 1024 * 1024)
                // Attaches x-internal-api-key to EVERY call made on this channel (Rag + Agent stubs)
                .intercept(apiKeyInterceptor)
                .build();
        return this.channel;
    }

    /**
     * Builds the interceptor that attaches the internal API key to every outgoing call.
     * Fails fast with a clear message when the key is missing or blank.
     */
    public static ClientInterceptor internalApiKeyInterceptor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration 'nexus.ai-service.internal-api-key' (env INTERNAL_API_KEY). "
                            + "core-service cannot authenticate to ai-service without it.");
        }
        Metadata headers = new Metadata();
        headers.put(INTERNAL_API_KEY_METADATA_KEY, apiKey.trim());
        return MetadataUtils.newAttachHeadersInterceptor(headers);
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
