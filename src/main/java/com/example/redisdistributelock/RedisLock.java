package com.example.redisdistributelock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

public class RedisLock implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisLock.class);

    private RedisTemplate redisTemplate;
    private String lockKey;
    private String lockValue;
    private int expireTime;

    public RedisLock(RedisTemplate redisTemplate,String lockKey,String lockValue,int expireTime){
        this.redisTemplate = redisTemplate;
        //redis key
        this.lockKey = lockKey;
        //redis value
        this.lockValue = lockValue;
        //过期时间 单位：s
        this.expireTime = expireTime;
    }

    /**
     * 获取分布式锁
     */
    public boolean getLock(){
        //获取锁的操作
        return (boolean) redisTemplate.execute((RedisCallback) connection -> {
            //过期时间 单位：s
            Expiration expiration = Expiration.seconds(expireTime);
            //执行NX操作
            SetOption setOption = SetOption.ifAbsent();
            //序列化key
            byte[] serializeKey = redisTemplate.getKeySerializer().serialize(lockKey);
            //序列化value
            byte[] serializeVal = redisTemplate.getValueSerializer().serialize(lockValue);
            //获取锁
            boolean result = connection.set(serializeKey, serializeVal, expiration, setOption);
            LOGGER.info("获取redis锁结果：" + result);
            return result;
        });
    }

    /**
     * 自动释放锁
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        //释放锁的lua脚本
        String script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
        RedisScript<String> redisScript = RedisScript.of(script,Boolean.class);
        //是否redis锁
        Boolean result = (Boolean) redisTemplate.execute(redisScript, Arrays.asList(lockKey), lockValue);
        LOGGER.info("释放redis锁结果："+result);
    }
}
