## Redis分布式锁的try-with-resources实现

### 一、简介

在当今这个时代，单体应用（standalone）已经很少了，java提供的synchronized已经不能满足需求，大家自然
而然的想到了分布式锁。谈到分布式锁，比较流行的方法有3中：
1. 基于数据库实现的
2. 基于redis实现的
3. 基于zookeeper实现的

今天我们重点说一下基于redis的分布式锁，redis分布式锁的实现我们可以参照[redis的官方文档](http://redis.cn/topics/distlock.html)。
实现Redis分布式锁的最简单的方法就是在Redis中创建一个key，这个key有一个失效时间（TTL)，以保证锁最终会被自动释放掉。当客户端释放资源(解锁）的时候，会删除掉这个key。

获取锁使用命令:
```
SET resource_name my_random_value NX PX 30000
```

这个命令仅在不存在key的时候才能被执行成功（NX选项），并且这个key有一个30秒的自动失效时间（PX属性）。这个key的值是“my_random_value”(一个随机值），
这个值在所有的客户端必须是唯一的，所有同一key的获取者（竞争者）这个值都不能一样。

value的值必须是随机数主要是为了更安全的释放锁，释放锁的时候使用脚本告诉Redis:只有key存在并且存储的值和我指定的值一样才能告诉我删除成功。

可以通过以下Lua脚本实现：

```
if redis.call("get",KEYS[1]) == ARGV[1] then
    return redis.call("del",KEYS[1])
else
    return 0
end
```
使用这种方式释放锁可以避免删除别的客户端获取成功的锁。举个例子：客户端A取得资源锁，但是紧接着被一个其他操作阻塞了，当客户端A运行完毕其他操作后要释放锁时，
原来的锁早已超时并且被Redis自动释放，并且在这期间资源锁又被客户端B再次获取到。如果仅使用DEL命令将key删除，那么这种情况就会把客户端B的锁给删除掉。
使用Lua脚本就不会存在这种情况，因为脚本仅会删除value等于客户端A的value的key（value相当于客户端的一个签名）。

这种方法已经足够安全,如果担心redis故障转移时，锁失效的问题，请参照Redis官方文档中的RedLock，这里不做具体讨论。

### 二、try-with-resources的实现

知道了Redis锁的实现原理，我们再来看看如何实现。其实关键的步骤只有两步：
1. 获取锁；
2. 释放锁；

大家在写程序的时候是不是总忘记释放锁呢？就像以前对流操作时，忘记了关闭流。从java7开始，加入了try-with-resources的方式，它可以
自动的执行close()方法，释放资源，再也不用写finally块了。我们就按照这种思路编写Redis锁，在具体写代码之前，我们先谈谈
Redis的客户端，Redis的客户端官方推荐有3种：
1. Jedis;
2. Lecttuce;
3. Redisson;

Redis官方比较推荐Redisson，但是Spring-data中并没有这种方式，Spring-Data-Redis支持Jedis和Lecttuce两种方式。
国内用的比较多的是Jedis，但是Spring-Data默认用Lecttuce。不管那么多了，直接用Spring-Boot，配置好连接，直接使用就好了。

Redis锁的try-with-resources实现：
```java
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
```
只要实现了`Closeable`接口，并重写了`close()`方法，就可以使用try-with-resources的方式了。

具体的使用代码如下：
```java
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
```
这样我们就不用关心锁的释放问题了。
