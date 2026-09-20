package com.muedsa.snapshot.open

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 等待渲染槽位的请求数超过队列上限。 */
internal class RenderQueueFullException(val maxQueueSize: Int) : RuntimeException()

/** 等待渲染槽位的时间超过上限。 */
internal class RenderQueueTimeoutException(val queueTimeoutMs: Long) : RuntimeException()

/**
 * 渲染执行器：把阻塞式 Skia 渲染从 Netty 工作线程搬到专用线程池，并对排队做背压。
 *
 * - 池大小与 `snapshot.max-concurrent-renders` 一致，是并发渲染的硬上限；
 * - 有空闲槽位时直接执行，不受队列上限影响；
 * - 需要排队时受 `snapshot.max-render-queue` 约束：等待中的请求超过上限立刻拒绝，避免无界堆积；
 * - 排队超时 `snapshot.render-queue-timeout-ms`：等待过久快速失败，不占着连接；
 * - 线程命名 `snapshot-render-*`，daemon 线程，便于线程转储与进程退出。
 *
 * 注意：阻塞调用无法被协程取消中断，超时只会在渲染返回后生效，
 * 因此渲染槽位会在任务真正结束后才释放。
 */
internal class RenderExecutor(
    private val maxConcurrentRenders: Int,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    private val queueTimeoutMs: Long = DEFAULT_QUEUE_TIMEOUT_MS,
) : AutoCloseable {
    private val threadCounter = AtomicInteger()
    private val dispatcher = Executors.newFixedThreadPool(maxConcurrentRenders) { runnable ->
        Thread(runnable, "snapshot-render-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val slots = Semaphore(maxConcurrentRenders)
    private val waiting = AtomicInteger()

    suspend fun <T> run(block: () -> T): T {
        // 空闲槽位直接取用，不受队列上限影响；只有真正需要排队时才计入队列。
        if (!slots.tryAcquire()) {
            val waitingNow = waiting.incrementAndGet()
            if (waitingNow > maxQueueSize) {
                Metrics.renderPending.set(waiting.decrementAndGet().toLong().coerceAtLeast(0L))
                Metrics.renderQueueRejected(REJECT_REASON_QUEUE_FULL)
                throw RenderQueueFullException(maxQueueSize)
            }

            Metrics.renderPending.set(waitingNow.toLong())
            val waitStartedAt = System.nanoTime()
            try {
                withTimeout(queueTimeoutMs) { slots.acquire() }
            } catch (error: TimeoutCancellationException) {
                Metrics.renderQueueRejected(REJECT_REASON_QUEUE_TIMEOUT)
                throw RenderQueueTimeoutException(queueTimeoutMs)
            } finally {
                Metrics.observeRenderQueueWait(System.nanoTime() - waitStartedAt)
                Metrics.renderPending.set(waiting.decrementAndGet().toLong().coerceAtLeast(0L))
            }
        }

        try {
            Metrics.renderInFlight.inc()
            try {
                return withContext(dispatcher) { block() }
            } finally {
                Metrics.renderInFlight.dec()
            }
        } finally {
            slots.release()
        }
    }

    /** 停机后调用：不再接受新任务，已提交任务继续完成。 */
    override fun close() {
        dispatcher.close()
    }

    companion object {
        const val DEFAULT_MAX_QUEUE_SIZE: Int = 32
        const val DEFAULT_QUEUE_TIMEOUT_MS: Long = 5_000L

        const val REJECT_REASON_QUEUE_FULL: String = "full"
        const val REJECT_REASON_QUEUE_TIMEOUT: String = "timeout"
    }
}
