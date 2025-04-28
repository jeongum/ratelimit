package com.doteloper.ratelimit.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.scripting.support.ResourceScriptSource

@Configuration
class RedisScriptConfig {
    @Bean
    fun rateLimiterScript(): RedisScript<Long> = DefaultRedisScript(
        ResourceScriptSource(ClassPathResource("rate_limit_script.lua")).scriptAsString,
        Long::class.java
    )
}