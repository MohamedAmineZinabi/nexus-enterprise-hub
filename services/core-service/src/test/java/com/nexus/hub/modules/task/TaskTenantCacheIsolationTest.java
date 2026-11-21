package com.nexus.hub.modules.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.common.exception.ResourceNotFoundException;
import com.nexus.hub.config.RedisConfig;
import com.nexus.hub.modules.task.controller.TaskController;
import com.nexus.hub.modules.task.dto.TaskResponse;
import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.entity.TaskType;
import com.nexus.hub.modules.task.repository.AgentTaskRepository;
import com.nexus.hub.modules.task.repository.TaskAuditEventRepository;
import com.nexus.hub.modules.task.service.TaskService;
import com.nexus.hub.modules.task.service.TaskStreamHub;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.tenant.repository.TenantRepository;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * C3 regression: tenant B must never obtain tenant A's task, even when A's task is cached.
 * Minimal Spring context (real caching proxy + ConcurrentMapCacheManager), no Redis/DB.
 */
@SpringJUnitConfig(TaskTenantCacheIsolationTest.Config.class)
class TaskTenantCacheIsolationTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(RedisConfig.TASKS_CACHE);
        }

        @Bean
        AgentTaskRepository agentTaskRepository() {
            return Mockito.mock(AgentTaskRepository.class);
        }

        @Bean
        TaskAuditEventRepository taskAuditEventRepository() {
            return Mockito.mock(TaskAuditEventRepository.class);
        }

        @Bean
        UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }

        @Bean
        TenantRepository tenantRepository() {
            return Mockito.mock(TenantRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TaskStreamHub taskStreamHub() {
            return Mockito.mock(TaskStreamHub.class);
        }

        @Bean
        TaskService taskService(AgentTaskRepository taskRepository,
                                TaskAuditEventRepository auditEventRepository,
                                UserRepository userRepository,
                                TenantRepository tenantRepository,
                                org.springframework.context.ApplicationEventPublisher publisher,
                                ObjectMapper objectMapper) {
            return new TaskService(taskRepository, auditEventRepository, userRepository, tenantRepository,
                    publisher, objectMapper);
        }

        @Bean
        TaskController taskController(TaskService taskService, TaskStreamHub taskStreamHub) {
            return new TaskController(taskService, taskStreamHub);
        }
    }

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskController taskController;

    @Autowired
    private AgentTaskRepository taskRepository;

    @Autowired
    private TaskStreamHub taskStreamHub;

    @Autowired
    private CacheManager cacheManager;

    private UUID tenantA;
    private UUID tenantB;
    private UUID taskId;
    private UserPrincipal principalA;
    private UserPrincipal principalB;

    @BeforeEach
    void setUp() {
        reset(taskRepository, taskStreamHub);
        cacheManager.getCache(RedisConfig.TASKS_CACHE).clear();

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        taskId = UUID.randomUUID();

        Tenant tenant = Tenant.builder().id(tenantA).name("A").slug("a").status("ACTIVE").build();
        User user = User.builder().id(UUID.randomUUID()).tenant(tenant).email("a@a.io")
                .fullName("A").role(Role.ADMIN).enabled(true).build();
        AgentTask task = AgentTask.builder()
                .id(taskId).tenant(tenant).user(user).prompt("secret prompt of tenant A")
                .taskType(TaskType.DEEP_RESEARCH).status(TaskStatus.COMPLETED).totalTokensUsed(0L).build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        principalA = principal(tenantA);
        principalB = principal(tenantB);
    }

    private static UserPrincipal principal(UUID tenantId) {
        return UserPrincipal.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(tenantId + "@test.io")
                .role(Role.ADMIN)
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("Tenant B gets 404 for tenant A's task even after A's COMPLETED task was cached")
    void tenantBCannotReadCachedTaskOfTenantA() {
        TaskResponse forA = taskService.getTaskById(principalA, taskId);
        assertThat(forA.getTenantId()).isEqualTo(tenantA);

        Cache cache = cacheManager.getCache(RedisConfig.TASKS_CACHE);
        assertThat(cache.get(tenantA + ":" + taskId)).as("A's entry is cached under a tenant-scoped key").isNotNull();
        assertThat(cache.get(taskId)).as("no bare taskId key").isNull();

        // Second call for A is served from cache
        taskService.getTaskById(principalA, taskId);
        verify(taskRepository, times(1)).findById(taskId);

        // Tenant B: different cache key -> miss -> DB ownership check -> 404
        assertThatThrownBy(() -> taskService.getTaskById(principalB, taskId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> taskController.getTask(principalB, taskId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(cache.get(tenantB + ":" + taskId)).isNull();
    }

    @Test
    @DisplayName("Controller re-checks ownership on the DTO even if the cache were poisoned for tenant B's key")
    void controllerRejectsForeignTaskEvenFromCache() {
        TaskResponse foreign = taskService.getTaskById(principalA, taskId);
        cacheManager.getCache(RedisConfig.TASKS_CACHE).put(tenantB + ":" + taskId, foreign);

        assertThatThrownBy(() -> taskController.getTask(principalB, taskId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> taskController.streamTask(principalB, taskId))
                .isInstanceOf(ResourceNotFoundException.class);

        // No SSE emitter is ever registered for the foreign tenant
        verify(taskStreamHub, never()).registerClient(any(), anyLong());
    }

    @Test
    @DisplayName("Owner can still read and stream its own task")
    void ownerCanReadOwnTask() {
        assertThat(taskController.getTask(principalA, taskId).getBody().getData().getTaskId()).isEqualTo(taskId);
        taskController.streamTask(principalA, taskId);
        verify(taskStreamHub).registerClient(eq(taskId), anyLong());
    }
}
