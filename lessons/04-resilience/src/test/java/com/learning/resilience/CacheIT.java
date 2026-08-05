package com.learning.resilience;

import com.learning.resilience.config.CacheConfig;
import com.learning.resilience.domain.Product;
import com.learning.resilience.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class CacheIT {

    @Autowired
    private ProductService productService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        productService.evictAll();
        productService.resetCounter();
    }

    @Test
    @DisplayName("the cache manager is Redis-backed, not the ConcurrentHashMap fallback")
    void cacheManagerIsRedis() {
        // If spring-boot-starter-data-redis were missing, Boot would silently fall back to
        // an in-memory map — caching would "work" locally and break across instances.
        assertThat(cacheManager.getClass().getSimpleName()).isEqualTo("RedisCacheManager");
    }

    @Test
    @DisplayName("@Cacheable runs the method once for N identical calls")
    void cacheableSkipsTheMethodOnHit() {
        IntStream.range(0, 5).forEach(i -> productService.findById(1L));

        assertThat(productService.databaseHits())
                .as("5 calls, 1 real lookup")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("values round-trip through JSON serialization intact")
    void valuesRoundTripThroughRedis() {
        Product first = productService.findById(2L);      // miss, stores in Redis
        Product second = productService.findById(2L);     // hit, deserialized from Redis

        assertThat(productService.databaseHits()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.price()).isEqualByComparingTo(first.price());
        // This is the assertion that would have caught the record/NON_FINAL typing bug:
        // with a missing @class type id the deserialize throws instead of returning null.
        assertThat(second.name()).isEqualTo("27\" monitor");
    }

    @Test
    @DisplayName("different keys are cached independently")
    void keysAreIndependent() {
        productService.findById(1L);
        productService.findById(2L);
        productService.findById(1L);
        productService.findById(2L);

        assertThat(productService.databaseHits()).isEqualTo(2);
    }

    @Test
    @DisplayName("@CachePut updates the cache without a subsequent lookup")
    void cachePutRefreshesTheEntry() {
        productService.findById(3L);                       // 1 hit, now cached
        productService.resetCounter();

        productService.updatePrice(3L, new BigDecimal("199.99"));
        Product readBack = productService.findById(3L);     // served from cache

        assertThat(productService.databaseHits())
                .as("@CachePut wrote the new value straight into the cache")
                .isZero();
        assertThat(readBack.price()).isEqualByComparingTo(new BigDecimal("199.99"));
    }

    @Test
    @DisplayName("@CacheEvict removes a single entry")
    void cacheEvictRemovesEntry() {
        productService.findById(1L);
        productService.evictOne(1L);
        productService.findById(1L);

        assertThat(productService.databaseHits()).isEqualTo(2);
    }

    @Test
    @DisplayName("@CacheEvict(allEntries) clears everything")
    void cacheEvictAllClearsCache() {
        productService.findById(1L);
        productService.findById(2L);
        productService.resetCounter();

        productService.evictAll();
        productService.findById(1L);
        productService.findById(2L);

        assertThat(productService.databaseHits()).isEqualTo(2);
    }

    @Test
    @DisplayName("collection results cache too")
    void collectionsAreCacheable() {
        productService.findByCategory("peripherals");
        var second = productService.findByCategory("peripherals");

        assertThat(productService.databaseHits()).isEqualTo(1);
        assertThat(second).hasSize(2);
    }

    /**
     * The bug this whole repo keeps circling back to. Asserting it keeps the demo honest.
     */
    @Test
    @DisplayName("self-invocation bypasses the CacheInterceptor entirely")
    void selfInvocationIsNotCached() {
        IntStream.range(0, 3).forEach(i -> productService.findByIdBypassingCache(4L));

        assertThat(productService.databaseHits())
                .as("`this.findById()` never reaches the proxy, so nothing is cached")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("configured caches exist with their own settings")
    void cachesAreConfigured() {
        assertThat(cacheManager.getCacheNames())
                .contains(CacheConfig.PRODUCTS, CacheConfig.PRICES, CacheConfig.EXPENSIVE);
    }
}
