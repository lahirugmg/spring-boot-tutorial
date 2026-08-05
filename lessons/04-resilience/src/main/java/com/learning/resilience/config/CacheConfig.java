package com.learning.resilience.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * INTERVIEW: "How does Spring's cache abstraction work?"
 *
 * @EnableCaching registers a CacheInterceptor (AOP again). On a @Cacheable call it asks
 * the CacheManager for a Cache by name, builds a key with the KeyGenerator, and returns
 * the cached value if present — the method body is skipped entirely.
 *
 * The abstraction is provider-agnostic: swap Redis for Caffeine, Hazelcast or EhCache by
 * changing the CacheManager bean, with no change to the annotated code. With no provider
 * on the classpath Boot silently uses a ConcurrentHashMap — which is why "caching works
 * locally but not in prod" happens: two pods each get their own private map.
 *
 * SERIALIZATION is the detail that bites people. The default is JDK serialization, which
 * requires Serializable, produces opaque binary blobs you cannot inspect with redis-cli,
 * and breaks the moment a class's serialVersionUID changes. JSON is readable, portable and
 * survives most refactors.
 */
@Configuration
public class CacheConfig {

    public static final String PRODUCTS = "products";
    public static final String PRICES = "prices";
    public static final String EXPENSIVE = "expensive";

    /**
     * PER-CACHE TTLs. A single global TTL is almost never right: reference data can live
     * for hours, a price for seconds.
     *
     * Note entryTtl is a hard expiry, not a sliding window — Redis TTL is absolute.
     */
    @Bean
    RedisCacheManager cacheManager(org.springframework.data.redis.connection.RedisConnectionFactory factory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                // Do NOT store nulls. Caching a null is sometimes deliberate (negative
                // caching, to stop repeated misses hammering the DB) but it also hides
                // bugs. Be explicit either way.
                .disableCachingNullValues()
                // Keys become "products::42" in Redis — readable with `redis-cli KEYS '*'`.
                .computePrefixWith(cacheName -> cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        PRICES, defaults.entryTtl(Duration.ofSeconds(5)),      // volatile
                        PRODUCTS, defaults.entryTtl(Duration.ofMinutes(30)),   // stable
                        EXPENSIVE, defaults.entryTtl(Duration.ofMinutes(1))))
                // Fail loudly if a @Cacheable names a cache that was never configured,
                // instead of silently creating one with default settings.
                .disableCreateOnMissingCache()
                .build();
    }

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        /*
         * activateDefaultTyping embeds the concrete class name in the JSON so the value can
         * be read back as the right type. The cache API is Object-typed, so without it
         * deserialization has nothing to target.
         *
         * WHY `EVERYTHING` AND NOT THE USUAL `NON_FINAL` — a real bug this module hit:
         *
         *   Product is a RECORD, and records are FINAL. With NON_FINAL, Jackson decides no
         *   type id is needed when WRITING (the runtime type is final), but when READING
         *   the declared type is Object — which is not final — so it demands one:
         *
         *       InvalidTypeIdException: Could not resolve subtype of
         *       [simple type, class java.lang.Object]: missing type id property '@class'
         *
         *   Writes succeed, reads blow up, and only on a cache HIT — so it looks like an
         *   intermittent Redis fault rather than a serializer misconfiguration. Every
         *   NON_FINAL + record codebase hits this.
         *
         * TRADE-OFF: EVERYTHING also tags Strings and collections, making entries more
         * verbose. The alternatives are a typed Jackson2JsonRedisSerializer<Product> per
         * cache (cleanest when each cache holds one type), or making the DTO a non-final
         * class.
         *
         * SECURITY: default typing instantiates classes named in the payload, which is a
         * deserialization-gadget risk if anything untrusted can write to your Redis. The
         * PolymorphicTypeValidator below is the guard — in a hardened service, restrict it
         * to your own package rather than allowing the default.
         */
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
