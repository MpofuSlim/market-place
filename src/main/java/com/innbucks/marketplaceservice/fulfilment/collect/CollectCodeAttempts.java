package com.innbucks.marketplaceservice.fulfilment.collect;

import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The online-guessing budget for one parcel's collection code.
 *
 * <p><b>{@code REQUIRES_NEW} is the entire point of this class existing.</b>
 * A wrong code is refused by throwing, and a throw rolls the caller's
 * transaction back — so an increment written on that same transaction is
 * undone by the very exception it was counting, and the budget never moves
 * however many codes are tried. Its own transaction commits before the refusal
 * is raised, so the attempt is recorded whatever happens next. (This is the
 * failed-PIN counter lesson from the InnBucks middleware, imported rather than
 * rediscovered.)
 *
 * <p>The outer transaction holds no lock on the parcel when this runs — it has
 * only read the row — so the inner transaction cannot deadlock against it.
 */
@Component
@RequiredArgsConstructor
public class CollectCodeAttempts {

    private final OrderFulfilmentRepository fulfilmentRepository;

    /** Counts one wrong attempt and returns the budget spent, including it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int bumpAndCount(UUID fulfilmentId) {
        fulfilmentRepository.bumpCollectCodeAttempts(fulfilmentId, Instant.now());
        Integer spent = fulfilmentRepository.collectCodeAttempts(fulfilmentId);
        return spent == null ? 0 : spent;
    }
}
