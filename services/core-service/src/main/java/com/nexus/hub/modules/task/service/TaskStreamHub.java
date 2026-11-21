package com.nexus.hub.modules.task.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class TaskStreamHub {

    static final long HEARTBEAT_INTERVAL_MS = 15000;

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> taskEmitters = new ConcurrentHashMap<>();

    /**
     * Registers a new SSE client for a specific task ID.
     *
     * @param taskId        the unique identifier of the agent task
     * @param timeoutMillis the connection timeout in milliseconds
     * @return configured SseEmitter instance with callbacks and handshake sent
     */
    public SseEmitter registerClient(UUID taskId, long timeoutMillis) {
        SseEmitter emitter = createEmitter(timeoutMillis);

        CopyOnWriteArrayList<SseEmitter> emitters =
                taskEmitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        Runnable cleanup = () -> {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                taskEmitters.remove(taskId, emitters);
            }
            log.debug("Removed SSE emitter for taskId={}. Remaining active: {}", taskId, emitters.size());
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            log.info("SSE emitter timed out for taskId={}", taskId);
            cleanup.run();
        });
        emitter.onError(throwable -> {
            log.warn("SSE emitter error for taskId={}: {}", taskId, throwable.getMessage());
            cleanup.run();
        });

        // Send initial handshake
        try {
            emitter.send(SseEmitter.event()
                    .name("HANDSHAKE")
                    .data(Map.of(
                            "taskId", taskId.toString(),
                            "status", "CONNECTED",
                            "timestamp", Instant.now().toString()
                    )));
            log.info("Registered SSE subscriber for taskId={}. Total active: {}", taskId, emitters.size());
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException: the stream was completed concurrently (e.g. the task finished
            // between registration and handshake) - release it quietly instead of failing the request.
            log.warn("Could not send initial SSE handshake for taskId={}: {}", taskId, e.getMessage());
            cleanup.run();
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // already completed
            }
        }

        return emitter;
    }

    /**
     * Broadcasts an event to all connected SSE clients observing the specified task.
     *
     * @param taskId    the task identifier
     * @param eventName name of the SSE event (e.g., TASK_PROGRESS, TASK_COMPLETED)
     * @param data      payload object to serialize
     */
    public void broadcastEvent(UUID taskId, String eventName, Object data) {
        List<SseEmitter> emitters = taskEmitters.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active SSE subscribers for taskId={}. Skipping broadcast of '{}'", taskId, eventName);
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                log.warn("Failed to dispatch SSE event '{}' to subscriber for taskId={}: {}",
                        eventName, taskId, e.getMessage());
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            if (emitters.isEmpty()) {
                taskEmitters.remove(taskId, emitters);
            }
        }
    }

    /**
     * Completes and gracefully closes all active SSE emitters for the specified task.
     *
     * @param taskId the task identifier
     */
    public void completeStream(UUID taskId) {
        List<SseEmitter> emitters = taskEmitters.remove(taskId);
        if (emitters != null && !emitters.isEmpty()) {
            log.info("Closing {} active SSE emitters for completed taskId={}", emitters.size(), taskId);
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Error completing emitter for taskId={}: {}", taskId, e.getMessage());
                }
            }
        }
    }

    /**
     * Sends an SSE comment ({@code : keep-alive}) to every active emitter every 15s so idle streams are
     * not cut by proxies (NGINX proxy_read_timeout) or load balancers. Emitters whose client has gone
     * away (IOException / IllegalStateException on send) are removed and closed. Runs on the scheduler
     * thread concurrently with Kafka-driven broadcasts: the map/list are concurrent collections
     * (iteration is over a snapshot) and SseEmitter serializes concurrent sends internally.
     */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void sendHeartbeats() {
        for (Map.Entry<UUID, CopyOnWriteArrayList<SseEmitter>> entry : taskEmitters.entrySet()) {
            UUID taskId = entry.getKey();
            CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException e) {
                    log.debug("SSE heartbeat failed for taskId={}; removing emitter: {}", taskId, e.getMessage());
                    emitters.remove(emitter);
                    closeQuietly(emitter, e);
                } catch (RuntimeException e) {
                    log.warn("Unexpected SSE heartbeat error for taskId={}; removing emitter: {}", taskId, e.getMessage());
                    emitters.remove(emitter);
                    closeQuietly(emitter, e);
                }
            }
            if (emitters.isEmpty()) {
                taskEmitters.remove(taskId, emitters);
            }
        }
    }

    private static void closeQuietly(SseEmitter emitter, Throwable cause) {
        try {
            if (cause instanceof IOException) {
                emitter.completeWithError(cause);
            } else {
                emitter.complete();
            }
        } catch (Exception ignored) {
            // Already completed / response already closed: nothing left to release
        }
    }

    /** Factory for emitters; overridable in tests. */
    protected SseEmitter createEmitter(long timeoutMillis) {
        return new SseEmitter(timeoutMillis);
    }

    /**
     * Total number of active SSE subscribers across all tasks.
     */
    public int getTotalActiveSubscriberCount() {
        return taskEmitters.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Returns count of active SSE subscribers for a task.
     */
    public int getActiveSubscriberCount(UUID taskId) {
        List<SseEmitter> emitters = taskEmitters.get(taskId);
        return emitters != null ? emitters.size() : 0;
    }
}

