package com.learning.messaging.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The event payload.
 *
 * INTERVIEW: "How do you evolve an event schema without breaking consumers?"
 *
 *   - ADD optional fields only; never remove or repurpose one. Old consumers ignore what
 *     they do not know (Jackson needs FAIL_ON_UNKNOWN_PROPERTIES=false — set in
 *     application.yml, and the single most common cause of "the new field broke prod").
 *   - Never change a field's TYPE or meaning. Add a new field and deprecate the old one.
 *   - Carry a schema/version marker so consumers can branch explicitly.
 *   - For real systems use a schema registry (Avro/Protobuf/JSON Schema) which ENFORCES
 *     compatibility at publish time rather than hoping reviewers notice.
 *
 * `eventId` exists for idempotency — see IdempotentOrderConsumer.
 */
public record OrderEvent(
        String eventId,
        String orderId,
        String customerId,
        BigDecimal amount,
        String status,
        Instant occurredAt,
        int schemaVersion
) {
    public static OrderEvent created(String eventId, String orderId, String customerId, BigDecimal amount) {
        return new OrderEvent(eventId, orderId, customerId, amount, "CREATED", Instant.now(), 1);
    }
}
