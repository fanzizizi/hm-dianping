local key = "blog:liked:" .. ARGV[1]
local userId = ARGV[2]
local score = ARGV[3]
--if(redis.call('sismember', key, userId) == 1) then
--    --已经点过赞了,移除Userid
--    redis.call('srem',key, userId)
--    return 1
--end
----点赞
--redis.call('sadd', key , userId)
--return 0


if(redis.call('zscore', key, userId)) then
    redis.call('zrem', key, userId)
    return 1
    --代表没点过赞

end
redis.call('zadd', key, score,userId)
return 0