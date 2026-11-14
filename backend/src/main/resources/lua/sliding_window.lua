-- 滑动窗口计数：计数、清理过期、写入在同一脚本内原子完成。
-- KEYS[1] 限流 key
-- ARGV[1] 当前毫秒时间戳
-- ARGV[2] 窗口长度（毫秒）
-- ARGV[3] 阈值
-- ARGV[4] 模式：peek=只读计数；hit=计数并占用一次配额
-- ARGV[5] 本次占用的唯一成员（毫秒时间戳 + 随机后缀）
-- 返回 {allowed, count, retryAfterSeconds}

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local mode = ARGV[4]

-- 先清掉窗口外的记录，避免 ZSET 无限增长
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local count = redis.call('ZCARD', key)

if mode == 'hit' then
    if count >= limit then
        local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
        local retry = 1
        if oldest[2] then
            retry = math.max(1, math.ceil((window - (now - tonumber(oldest[2]))) / 1000))
        end
        return {0, count, retry}
    end
    redis.call('ZADD', key, now, ARGV[5])
    redis.call('PEXPIRE', key, window + 1000)
    return {1, count + 1, 0}
end

if count == 0 then
    return {1, 0, 0}
end
return {1, count, 0}
