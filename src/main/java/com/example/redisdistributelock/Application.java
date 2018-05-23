package com.example.redisdistributelock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootApplication
public class Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext applicationContext = SpringApplication.run(Application.class, args);
        RedisTemplate redisTemplate = applicationContext.getBean("redisTemplate",RedisTemplate.class);

        try (RedisLock lock = new RedisLock(redisTemplate,"test_key","test_val",60)){
            //获取锁
            if (lock.getLock()){
                //模拟执行业务
                Thread.sleep(5*1000);
                LOGGER.info("获取到锁，执行业务操作耗时5s");
            }
        }catch (Exception e){
            LOGGER.error(e.getMessage(),e);
        }
    }
}
