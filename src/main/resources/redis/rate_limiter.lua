local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local max = tonumber(ARGV[3])
local member = ARGV[4]

local windowStart = now - window * 1000
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
local count = redis.call('ZCARD', key)

if count < max then
    redis.call('ZADD', key, now, member)
    redis.call('EXPIRE', key, window + 1)

    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local resetAt = 0
    if #oldest >= 2 then
        resetAt = tonumber(oldest[2]) + window * 1000
    end

    return {1, max - count - 1, resetAt}
end

local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local resetAt = 0
if #oldest >= 2 then
    resetAt = tonumber(oldest[2]) + window * 1000
end

return {0, 0, resetAt}
