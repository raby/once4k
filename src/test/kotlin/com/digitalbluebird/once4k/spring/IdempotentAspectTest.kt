package com.digitalbluebird.once4k.spring

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.digitalbluebird.once4k.InMemoryStore
import com.digitalbluebird.once4k.Once
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IdempotentAspectTest {

    // The service is AOP-proxied, so its own fields are off-limits from the test (a CGLIB proxy skips
    // the constructor). The run counter is a separate bean, incremented by the target and read by the
    // test. Methods are open so Spring can proxy them; the SpEL keys reference arguments by name.
    open class ChargeService(private val runs: AtomicInteger) {
        @Idempotent(key = "'charge:' + #orderId")
        open fun charge(orderId: String): String {
            runs.incrementAndGet()
            return "receipt-$orderId"
        }

        @Idempotent(key = "#orderId")
        open fun chargeOrFail(orderId: String, fail: Boolean): String {
            if (fail) throw IllegalStateException("boom")
            runs.incrementAndGet()
            return "ok"
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    open class TestConfig {
        @Bean open fun runs() = AtomicInteger(0)

        @Bean open fun once() = Once(InMemoryStore())

        @Bean open fun idempotentAspect(once: Once) = IdempotentAspect(once)

        @Bean open fun chargeService(runs: AtomicInteger) = ChargeService(runs)
    }

    private fun context() = AnnotationConfigApplicationContext(TestConfig::class.java)

    @Test
    fun `an annotated method runs at most once per key`() {
        context().use { ctx ->
            val service = ctx.getBean(ChargeService::class.java)
            val runs = ctx.getBean(AtomicInteger::class.java)

            assertThat(service.charge("42")).isEqualTo("receipt-42")
            assertThat(service.charge("42")).isEqualTo("receipt-42") // replayed, not re-run
            assertThat(runs.get()).isEqualTo(1)

            assertThat(service.charge("99")).isEqualTo("receipt-99") // a different key runs
            assertThat(runs.get()).isEqualTo(2)
        }
    }

    @Test
    fun `a failing annotated call leaves the key retryable`() {
        context().use { ctx ->
            val service = ctx.getBean(ChargeService::class.java)
            val runs = ctx.getBean(AtomicInteger::class.java)

            assertFailsWith<IllegalStateException> { service.chargeOrFail("k", fail = true) }
            assertThat(service.chargeOrFail("k", fail = false)).isEqualTo("ok") // retried
            assertThat(runs.get()).isEqualTo(1)
        }
    }
}
