package com.apimarketplace.datasource.events;

import com.apimarketplace.datasource.crud.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link VectorColumnCreatedListener} - the post-commit HNSW index builder.
 * Wired for the first time here: the index-creation code existed since V75
 * but had no caller, so every similarity search sequential-scanned.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VectorColumnCreatedListener")
class VectorColumnCreatedListenerTest {

    @Mock
    private VectorRepository vectorRepository;

    private VectorColumnCreatedListener listener() {
        return new VectorColumnCreatedListener(vectorRepository,
                VectorIndexExecutorConfig.VectorIndexExecutor.direct());
    }

    @Test
    @DisplayName("builds the HNSW index with the event's datasource, dimension and metric")
    void buildsIndexFromEvent() {
        listener()
                .onVectorColumnCreated(new VectorColumnCreatedEvent(42L, 1536, "cosine"));

        verify(vectorRepository).createHnswIndex(42L, 1536, "cosine");
    }

    @Test
    @DisplayName("swallows index-build failures - similarity search must keep working via seq scan")
    void swallowsBuildFailures() {
        doThrow(new IllegalStateException("mixed dimensions on datasource"))
                .when(vectorRepository).createHnswIndex(7L, 768, "l2");

        assertThatCode(() -> listener()
                .onVectorColumnCreated(new VectorColumnCreatedEvent(7L, 768, "l2")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("REGRESSION: listener runs without a transaction too (fallbackExecution) - table creation publishes outside any tx and the default silently dropped the event")
    void listenerHasFallbackExecution() throws Exception {
        var annotation = VectorColumnCreatedListener.class
                .getMethod("onVectorColumnCreated", VectorColumnCreatedEvent.class)
                .getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);

        org.assertj.core.api.Assertions.assertThat(annotation.fallbackExecution())
                .as("createDataSource publishes with NO active transaction; without fallbackExecution "
                        + "the HNSW build event is dropped and the index is never created (caught live in CE e2e)")
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(annotation.phase())
                .isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("drops the HNSW index of a deleted datasource, and swallows a failing drop")
    void dropsIndexOnDatasourceDeletion() {
        listener()
                .onVectorDataSourceDeleted(new VectorDataSourceDeletedEvent(42L));
        verify(vectorRepository).dropHnswIndex(42L);

        doThrow(new IllegalStateException("lock timeout")).when(vectorRepository).dropHnswIndex(7L);
        assertThatCode(() -> listener()
                .onVectorDataSourceDeleted(new VectorDataSourceDeletedEvent(7L)))
                .doesNotThrowAnyException();
    }

    /**
     * The trap this pins: Spring Boot registers applicationTaskExecutor only when
     * the context holds no Executor bean of its own, and a component-scanned bean
     * of that type fails the condition before auto-configuration runs. The service
     * would then lose its default executor silently and every unqualified @Async
     * (the row-event listener that calls trigger-service on each row write) would
     * fall back to a thread per invocation. The pool is therefore wrapped in a
     * type that is NOT an Executor. If someone simplifies it back to one, this
     * fails.
     */
    @Test
    @DisplayName("the index pool is not exposed as a java.util.concurrent.Executor bean")
    void poolIsNotAnExecutorBean() throws NoSuchMethodException {
        Class<?> beanType = VectorIndexExecutorConfig.class
                .getMethod("vectorIndexExecutor", io.micrometer.core.instrument.MeterRegistry.class).getReturnType();
        org.assertj.core.api.Assertions.assertThat(java.util.concurrent.Executor.class.isAssignableFrom(beanType))
                .as("exposing the pool as an Executor evicts the default applicationTaskExecutor from this service")
                .isFalse();
    }

    @Test
    @DisplayName("neither handler is @Async: the pool is not an Executor bean, so the handlers hand over the work themselves")
    void handlersAreNotAsync() throws NoSuchMethodException {
        var build = VectorColumnCreatedListener.class.getMethod("onVectorColumnCreated", VectorColumnCreatedEvent.class);
        var drop = VectorColumnCreatedListener.class.getMethod("onVectorDataSourceDeleted", VectorDataSourceDeletedEvent.class);
        org.assertj.core.api.Assertions.assertThat(VectorColumnCreatedListener.class
                .getAnnotation(org.springframework.scheduling.annotation.Async.class))
                .as("a class-level @Async would route every handler to the default executor").isNull();
        for (var m : java.util.List.of(build, drop)) {
            org.assertj.core.api.Assertions.assertThat(m.getAnnotation(org.springframework.scheduling.annotation.Async.class))
                    .as(m.getName() + " must not be @Async").isNull();
            var tel = m.getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);
            org.assertj.core.api.Assertions.assertThat(tel).isNotNull();
            // Load-bearing for BOTH delete paths: neither deleteDataSource nor
            // deleteByWorkflow runs inside a transaction, so without fallbackExecution
            // the drop event is silently discarded and the index leaks again.
            org.assertj.core.api.Assertions.assertThat(tel.fallbackExecution())
                    .as(m.getName() + " must run without a transaction: both delete paths publish outside one")
                    .isTrue();
            org.assertj.core.api.Assertions.assertThat(tel.phase())
                    .isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
        }
    }

    @Test
    @DisplayName("the vector-index pool is capped at two threads with a bounded queue")
    void executorIsBounded() {
        var executor = new VectorIndexExecutorConfig().vectorIndexExecutor(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        org.assertj.core.api.Assertions.assertThat(executor.maxPoolSize()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(executor.queueCapacity()).isPositive();
        executor.shutdown();
    }
}
