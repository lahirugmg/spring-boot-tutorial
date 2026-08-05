package com.learning.resilience.service;

import com.learning.resilience.config.CacheConfig;
import com.learning.resilience.domain.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cache annotations, with the traps that get asked about.
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    /** Counts REAL executions, so a test/endpoint can prove the cache was consulted. */
    private final AtomicInteger databaseHits = new AtomicInteger();

    private final Map<Long, Product> store = new ConcurrentHashMap<>(Map.of(
            1L, new Product(1L, "Mechanical keyboard", new BigDecimal("129.99"), "peripherals"),
            2L, new Product(2L, "27\" monitor", new BigDecimal("349.00"), "displays"),
            3L, new Product(3L, "USB-C dock", new BigDecimal("189.50"), "peripherals"),
            4L, new Product(4L, "Ergonomic chair", new BigDecimal("642.00"), "furniture")));

    /**
     * @Cacheable — check the cache FIRST; run the method only on a miss, then store.
     *
     * KEY GENERATION: with no `key` attribute, SimpleKeyGenerator is used —
     *   no args      -> SimpleKey.EMPTY
     *   one arg      -> that argument
     *   several args -> SimpleKey(a, b, ...)
     * Two overloads of the same method with the same arg values therefore COLLIDE in the
     * same cache. Naming the key explicitly (as here) avoids that whole class of bug.
     *
     * `unless` is evaluated AFTER the call, on the result (#result); `condition` is
     * evaluated BEFORE, on the arguments. Use condition to skip the cache lookup entirely,
     * unless to skip only the store.
     */
    @Cacheable(cacheNames = CacheConfig.PRODUCTS, key = "'product:' + #id", unless = "#result == null")
    public Product findById(long id) {
        databaseHits.incrementAndGet();
        simulateSlowLookup();
        log.info("CACHE MISS -> loaded product {} from the 'database'", id);
        return store.get(id);
    }

    /**
     * @CachePut ALWAYS runs the method and then updates the cache with the result.
     * Use it for writes. The classic mistake is putting @Cacheable on an update method:
     * that returns the STALE cached value and never performs the write at all.
     */
    @CachePut(cacheNames = CacheConfig.PRODUCTS, key = "'product:' + #id")
    public Product updatePrice(long id, BigDecimal newPrice) {
        Product existing = store.get(id);
        if (existing == null) {
            return null;
        }
        Product updated = new Product(existing.id(), existing.name(), newPrice, existing.category());
        store.put(id, updated);
        log.info("Updated product {} price -> {} (cache refreshed via @CachePut)", id, newPrice);
        return updated;
    }

    /**
     * @Caching composes several cache operations on one method — here: refresh this
     * product's entry AND invalidate the derived per-category listing, which is now stale.
     *
     * Keeping derived caches consistent is the hard part of caching; this is where most
     * real bugs live.
     */
    @Caching(
            put = @CachePut(cacheNames = CacheConfig.PRODUCTS, key = "'product:' + #id"),
            evict = @CacheEvict(cacheNames = CacheConfig.PRODUCTS, key = "'category:' + #result.category()"))
    public Product rename(long id, String newName) {
        Product existing = store.get(id);
        Product updated = new Product(existing.id(), newName, existing.price(), existing.category());
        store.put(id, updated);
        return updated;
    }

    @Cacheable(cacheNames = CacheConfig.PRODUCTS, key = "'category:' + #category")
    public List<Product> findByCategory(String category) {
        databaseHits.incrementAndGet();
        simulateSlowLookup();
        log.info("CACHE MISS -> scanning for category {}", category);
        return store.values().stream().filter(p -> p.category().equals(category)).toList();
    }

    @CacheEvict(cacheNames = CacheConfig.PRODUCTS, key = "'product:' + #id")
    public void evictOne(long id) {
        log.info("Evicted product {} from the cache", id);
    }

    /**
     * allEntries = true clears the WHOLE cache.
     *
     * beforeInvocation = false (the default) means the eviction happens AFTER the method
     * returns — so if the method throws, the cache is NOT cleared and you are left serving
     * stale data. Set beforeInvocation = true when a failure must still invalidate.
     */
    @CacheEvict(cacheNames = CacheConfig.PRODUCTS, allEntries = true, beforeInvocation = true)
    public void evictAll() {
        log.info("Evicted the entire '{}' cache", CacheConfig.PRODUCTS);
    }

    /**
     * THE SELF-INVOCATION TRAP AGAIN — same mechanism as @Transactional in module 02.
     * This method is not cached and calls this.findById(), so the CacheInterceptor is
     * bypassed: every call hits the "database" no matter what is in Redis.
     */
    public Product findByIdBypassingCache(long id) {
        return this.findById(id);
    }

    public int databaseHits() {
        return databaseHits.get();
    }

    public void resetCounter() {
        databaseHits.set(0);
    }

    /** Stands in for a slow query or a remote call. */
    private static void simulateSlowLookup() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
