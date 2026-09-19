package com.digitalbluebird.once4k.spring

import com.digitalbluebird.once4k.Once
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.context.expression.MethodBasedEvaluationContext
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.expression.spel.standard.SpelExpressionParser

/**
 * Wraps every [Idempotent]-annotated method call in [once], so the method runs at most once per key.
 *
 * Register it as a Spring bean alongside a [Once] bean, with `@EnableAspectJAutoProxy`:
 * ```
 * @Bean fun once() = Once(InMemoryStore())
 * @Bean fun idempotentAspect(once: Once) = IdempotentAspect(once)
 * ```
 */
@Aspect
public class IdempotentAspect(private val once: Once) {
    private val parser = SpelExpressionParser()
    private val parameterNames = DefaultParameterNameDiscoverer()

    @Around("@annotation(idempotent)")
    public fun runAtMostOnce(joinPoint: ProceedingJoinPoint, idempotent: Idempotent): Any? {
        val key = resolveKey(idempotent.key, joinPoint)
        return once.execute<Any?>(key) { joinPoint.proceed() }
    }

    /** Evaluate the SpEL [expression] against the intercepted call's arguments to get the key. */
    private fun resolveKey(expression: String, joinPoint: ProceedingJoinPoint): String {
        val method = (joinPoint.signature as MethodSignature).method
        val context = MethodBasedEvaluationContext(joinPoint.target, method, joinPoint.args, parameterNames)
        val value = parser.parseExpression(expression).getValue(context)
            ?: error("@Idempotent key expression \"$expression\" evaluated to null")
        return value.toString()
    }
}
