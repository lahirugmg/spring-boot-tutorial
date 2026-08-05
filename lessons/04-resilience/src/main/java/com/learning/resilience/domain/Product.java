package com.learning.resilience.domain;

import java.math.BigDecimal;

/**
 * Cached values must round-trip through the serializer. A record works fine with
 * Jackson (Jackson 2.12+ understands the canonical constructor), which is another
 * argument for JSON over JDK serialization — a record is not Serializable by default.
 */
public record Product(Long id, String name, BigDecimal price, String category) {
}
