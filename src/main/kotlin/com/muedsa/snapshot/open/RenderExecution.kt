package com.muedsa.snapshot.open

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 渲染执行器：把阻塞式 Skia 渲染从 Netty 工作线程搬到专用线程池。
 *
 * 渲染是 CPU 密集且不可挂起的调用，直接在请求协程里执行会占用连接处理线程，
 * 长渲染会拖慢甚至阻塞其他请求。这里用固定大小线程池隔离：
 * - 池大小与 `snapshot.max-concurrent-renders` 一致，也是并发渲染的硬上限；
 * - 线程命名 `snapshot-render-*`，便于线程转储与排查；
 * - 线程为 daemon，进程退出不受影响；
 * - 同一时刻并发执行数等于池大小，因此池内不会积压排队任务。
 *
 * 注意：阻塞调用无法被协程取消中断，超时只会在渲染返回后生效，
 * 因此渲染槽位会在任务真正结束后才释放。
 */
internal class RenderExecutor(private val maxConcurrentRenders: Int) : AutoCloseable {
    private val threadCounter = AtomicInteger()
    private val dispatcher = Executors.newFixedThreadPool(maxConcurrentRenders) { runnable ->
        Thread(runnable, "snapshot-render-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val slots = Semaphore(maxConcurrentRenders)

    suspend fun <T> run(block: () -> T): T {
        Metrics.renderPending.inc()
        try {
            slots.acquire()
        } finally {
            Metrics.renderPending.dec()
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
}
