-- 获取锁  KEY[1]
-- 获取线程标识 ARGV[1]

if(redis.call('GET',KEYS[1])==ARGV[1])then
    -- 一致，删除锁
    return redis.call('DEL',KEYS[1])
end
-- 不一致，直接返回
return 0