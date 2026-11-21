package com.nexus.hub.modules.task.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TaskStreamHubHeartbeatTest {

    /** Records sends; can be armed to fail like a disconnected client. */
    static class RecordingEmitter extends SseEmitter {
        final AtomicInteger sends = new AtomicInteger();
        volatile boolean failOnSend;
        volatile boolean completedWithError;

        RecordingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("Broken pipe");
            }
            sends.incrementAndGet();
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError = true;
            super.completeWithError(ex);
        }
    }

    /** Hub that hands out pre-built emitters in registration order. */
    static TaskStreamHub hubWith(Deque<SseEmitter> emitters) {
        return new TaskStreamHub() {
            @Override
            protected SseEmitter createEmitter(long timeoutMillis) {
                SseEmitter next = emitters.poll();
                return next != null ? next : super.createEmitter(timeoutMillis);
            }
        };
    }

    @Test
    @DisplayName("Heartbeat is scheduled every 15s")
    void heartbeatIsScheduledEvery15s() throws Exception {
        Scheduled scheduled = TaskStreamHub.class.getMethod("sendHeartbeats").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedRate()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("Heartbeat reaches every live emitter and removes + closes the broken ones")
    void heartbeatSendsCommentAndRemovesDeadEmitters() {
        RecordingEmitter healthy = new RecordingEmitter(60_000L);
        RecordingEmitter broken = new RecordingEmitter(60_000L);
        RecordingEmitter otherTask = new RecordingEmitter(60_000L);
        Deque<SseEmitter> queue = new ArrayDeque<>(List.of(healthy, broken, otherTask));
        TaskStreamHub hub = hubWith(queue);

        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        hub.registerClient(taskA, 60_000L);
        hub.registerClient(taskA, 60_000L);
        hub.registerClient(taskB, 60_000L);
        assertThat(hub.getTotalActiveSubscriberCount()).isEqualTo(3);
        int handshakeSends = healthy.sends.get();

        broken.failOnSend = true;
        hub.sendHeartbeats();

        assertThat(healthy.sends.get()).isEqualTo(handshakeSends + 1);
        assertThat(otherTask.sends.get()).isEqualTo(handshakeSends + 1);
        assertThat(broken.completedWithError).isTrue();
        assertThat(hub.getActiveSubscriberCount(taskA)).isEqualTo(1);
        assertThat(hub.getActiveSubscriberCount(taskB)).isEqualTo(1);

        // Next heartbeat no longer touches the removed emitter
        hub.sendHeartbeats();
        assertThat(healthy.sends.get()).isEqualTo(handshakeSends + 2);
    }

    @Test
    @DisplayName("An already-completed emitter (IllegalStateException on send) is removed and the task entry dropped")
    void completedEmitterIsRemoved() {
        TaskStreamHub hub = new TaskStreamHub();
        UUID taskId = UUID.randomUUID();
        SseEmitter emitter = hub.registerClient(taskId, 60_000L);
        emitter.complete(); // no servlet handler in a unit test -> completion callbacks never fire

        assertThat(hub.getActiveSubscriberCount(taskId)).isEqualTo(1);
        assertThatCode(hub::sendHeartbeats).doesNotThrowAnyException();
        assertThat(hub.getActiveSubscriberCount(taskId)).isZero();
        assertThat(hub.getTotalActiveSubscriberCount()).isZero();
    }

    @Test
    @DisplayName("Heartbeat with no subscribers is a no-op")
    void noSubscribers() {
        assertThatCode(() -> new TaskStreamHub().sendHeartbeats()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Heartbeats run safely concurrently with registrations, broadcasts and completions")
    void concurrentHeartbeatIsThreadSafe() throws Exception {
        TaskStreamHub hub = new TaskStreamHub();
        List<UUID> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(UUID.randomUUID());
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            for (int t = 0; t < 4; t++) {
                final int worker = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 200; i++) {
                            UUID task = tasks.get((i + worker) % tasks.size());
                            switch (worker) {
                                case 0 -> hub.registerClient(task, 60_000L);
                                case 1 -> hub.sendHeartbeats();
                                case 2 -> hub.broadcastEvent(task, "TASK_PROGRESS", "x");
                                default -> hub.completeStream(task);
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).isEmpty();
        assertThatCode(hub::sendHeartbeats).doesNotThrowAnyException();
    }
}
