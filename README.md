# 레디스 명령어들을 Atomic하게 실행하기 - Lua Script

개발을 하다 보면 여러 Redis 명령어를 하나의 흐름으로 묶어 실행해야 하는 경우가 있다.

나의 경우, **Rate Limit** 기능을 Redis로 구현하면서 이런 상황을 마주했다.

```markdown
1. 현재 window 만큼 sorted set 자르기
2. sorted set의 크기 가져오기
3. 해당 크기가 limit보다 작다면, 현재의 timestamp를 sorted set에 추가하기
```

이 모든 과정은 반드시 **원자적(atomic)** 으로 보장돼야 한다.

만약 2번 → 3번 실행 사이에 다른 요청이 들어오면, 잘못된 limit 체크 결과가 나올 수 있기 때문이다.

이를 해결하기 위해, Redis에서는 **여러 명령어를 하나의 단위로 실행**할 수 있는 방법을 제공한다. 그중 하나가 **Lua Script**이다.

---

# Lua Script란?

Redis 공식 문서에 따르면, Lua Script는 다음과 같은 특징을 가진다:

- **데이터가 존재하는 서버**에서 로직을 실행하여, 네트워크 지연을 줄이고 리소스를 절약할 수 있다.
- **스크립트 전체가 블로킹 방식**으로 실행되어, 중간 개입 없이 **완전한 원자성**을 보장한다.

---

## 프로그래밍 레벨 Lock vs Lua Script

그렇다면, **프로그래밍 레벨에서 Lock을 잡는 것과 Lua Script를 사용하는 것**은 어떤 차이가 있을까?

### 프로그래밍 레벨에서의 Lock

- 클라이언트가 먼저 SET resource_key unique_id NX PX timeout 으로 락을 획득
- 락이 잡히면 일련의 Redis 명령을 순차적으로 실행
- 끝나면 if redis.call("GET", key) == unique_id then DEL key end 형태로 해제

**장점**

- 분산 락을 적용하여 여러 Redis 인스턴스 간에도 락 조율 가능
- 복잡한 비즈니스 로직 흐름 제어가 자유로움
    - 즉, Redis 명령어 뿐 아니라 다른 비즈니스 로직이 포함되어도 OK

**단점**

- 코드 구현이 복잡합 - 락 획득/해제 실패 대비, 타임아웃 관리, 재시도 등
- 락을 잡은 동안에도 각 명령어는 개별 네트워크 왕복

### Lua Script

- 여러 명령을 하나의 Lua 스크립트로 묶어 EVAL 호출
- Redis 서버가 스크립트를 한 덩어리로 **단일 스레드**에서 실행

**장점**

- 완전한 원자성 보장: 중간에 다른 클라이언트 명령이 개입 불가
- 네트워크 왕복 제거: 스크립트 내부 명령은 하나의 네트워크 Call로 처리 가능

**단점**

- 단일 인스턴스 내에서만 원자성 보장
    - 클러스터 모드라면 키가 같은 슬롯에 있어야 함

### **정리**

- 단순히 Redis 명령어 간의 **원자성**만 필요하다면 → **Lua Script** 추천
- 복잡한 비즈니스 로직(외부 API 호출, 다양한 데이터 연산 등)이 포함된다면 → **Lock** 기반 처리 추천

---

# Spring Boot에서 Lua Script 사용하기

이제 위 시나리오를 **Spring Boot** 환경에서 어떻게 구현하는지 살펴보자.

### Lua Script 작성

resource 디렉토리에 필요한 레디스 명령들을 작성한 `.lua` 파일을 생성

```lua
-- rate_limit_script.lua
-- KEYS[1]: rate limit key
-- ARGV[1]: 현재 타임스탬프 (밀리초)
-- ARGV[2]: 윈도우 크기 (밀리초)
-- ARGV[3]: 허용 횟수(limit)
local key       = KEYS[1]
local now       = tonumber(ARGV[1])
local window    = tonumber(ARGV[2])
local limit     = tonumber(ARGV[3])
local window_start = now - window

-- 1) 윈도우 밖 오래된 기록 삭제
redis.call("ZREMRANGEBYSCORE", key, 0, window_start)

-- 2) 현재 윈도우 내 요청 수 확인
local cnt = redis.call("ZCARD", key)
if cnt >= limit then
  return 0
end

-- 3) 새 요청 기록
redis.call("ZADD", key, now, now)

return 1
```

- KEYS와 ARGV를 통해 외부에서 값을 입력받는다.

### RedisScriptConfig

위에 작성한 lua 스크립트를 가져와 RedisScript Bean 등록

```kotlin
@Configuration
class RedisScriptConfig {
    @Bean
    fun rateLimiterScript(): RedisScript<Long> = DefaultRedisScript(
			  // 1) Script의 ClassPath를 지정하여 가져온 후 등록
        ResourceScriptSource(ClassPathResource("rate_limit_script.lua")).scriptAsString,
				// 2) Return Type
        Long::class.java
    )
}
```

- LuaScript에서 리턴하는 타입에 맞게, 타입을 명시한다.

### Service 사용

Bean으로 등록한 스크립트를 사용

```kotlin
@Component
class RedisRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val rateLimiterScript: RedisScript<Long>
) {
    companion object {
        private const val ALLOW = 1L
    }

    fun isAllowed(key: String, timestamp: Long, windowMs: Long, limit: Int): Boolean {
        // 1) Script 실행
        val result = redisTemplate.execute(
            rateLimiterScript,
            listOf(key),
            timestamp.toString(),
            windowMs.toString(),
            limit.toString()
        )
        return result == ALLOW
    }
}
```

- `redisTemplate.execute` 명령어를 사용하여 script 실행한다.

![image](https://github.com/user-attachments/assets/ae9ba82f-e663-4e82-915a-482dce2f2534)

- 두번째 파라미터`keys` 에는 스크립트에서 사용될 key 들을 넣는다.
    - 사용: `local key = KEYS[1]`
- 이후 파라미터는 스크립트에서 사용될 argument들을 나열한다.
    - 사용: `local limit = tonumber(ARGV[3])`

---

이렇게 하면, **Spring Boot** 프로젝트에서도 **Lua Script를 통한 Redis의 원자적 작업 처리**를 쉽게 구현할 수 있다.

- 단일 명령이 아닌 **복합 작업이 필요한 경우**
- 레이스 컨디션이나 데이터 꼬임 없이 **안정적으로 처리하고 싶을 때**

Lua Script는 Redis에서 매우 강력한 무기가 될 수 있다.
