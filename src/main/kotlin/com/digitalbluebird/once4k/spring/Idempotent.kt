package com.digitalbluebird.once4k.spring

/**
 * Marks a method whose effect should happen at most once per idempotency key.
 *
 * [key] is a SpEL expression evaluated against the method's arguments (available by name, for example
 * `#orderId` or `'charge:' + #order.id`); its value becomes the idempotency key. A repeat call, or a
 * concurrent call, with the same key returns the first result without invoking the method again.
 *
 * Requires the once4k [IdempotentAspect] bean, Spring's `@EnableAspectJAutoProxy`, and a
 * [com.digitalbluebird.once4k.Once] bean.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Idempotent(val key: String)
