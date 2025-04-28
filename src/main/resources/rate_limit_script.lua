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
-- 4) TTL 갱신 (윈도우 유지)
redis.call("PEXPIRE", key, window)

return 1