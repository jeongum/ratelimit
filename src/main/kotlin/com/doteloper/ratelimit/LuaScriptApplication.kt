package com.doteloper.ratelimit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LuaScriptApplication

fun main(args: Array<String>) {
	runApplication<LuaScriptApplication>(*args)
}
