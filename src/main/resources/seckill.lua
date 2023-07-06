local voucherId=ARGV[1]
local userId=ARGV[2]
local orderId=ARGV[3]

--库存是否充足
local stockKey='seckill:stock:'..voucherId
--订单id
local orderKey='seckill:order'..voucherId

if(tonumber(redis.call('GET',stockKey))<=0)then
    --库存不足
    return 1
end
if(redis.call('sismember',orderKey,userId)==1)then
    --不能重复下单
    return 2
end

redis.call('incrby',stockKey,-1)
redis.call('sadd',orderKey,userId)

--发送消息到队列中，使用stream xadd stream.orders * voucherId voucherId userId userId id orderId
redis.call('xadd','stream.orders','*','voucherId',voucherId,'userId',userId,'id',orderId)

return 0