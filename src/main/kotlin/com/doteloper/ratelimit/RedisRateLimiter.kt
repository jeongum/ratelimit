package com.doteloper.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

@Component
class RedisRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val script: RedisScript<Long>
) {
    companion object {
        private const val ALLOW = 1L
    }

    fun isAllowed(key: String, timestamp: Long, windowMs: Long, limit: Int): Boolean {
        val result = redisTemplate.execute(
            script,
            listOf(key),
            timestamp.toString(),
            windowMs.toString(),
            limit.toString()
        )
        return result == ALLOW
    }
}