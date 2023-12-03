local voucherId = ARGV[1]
local userId = ARGV[2]
-- 连接字符串用 ..
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 判断库存,返回字符串，tonumber转成数字
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --库存不足
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    --已经下过单
    return 2
end
-- 下单减库存，成功下单
redis.call('SADD', orderKey, userId)
redis.call('INCRBY', stockKey, -1)
return 0