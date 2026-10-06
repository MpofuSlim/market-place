package com.innbucks.marketplaceservice.catalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * How many public {@code ?w=} resizes may DECODE at once.
 *
 * <p>Each decode is bounded ({@link ImageResizer#DECODE_CEILING_BYTES} of
 * raster, the same again at most of libjpeg's native buffer), but nothing
 * bounded how many run together — and the endpoint is public and
 * unauthenticated, so a burst of requests for one large seller image could
 * still add those bounds up past a ~450 MiB heap, or pin every core on JPEG
 * entropy decoding (subsampling drops pixels AFTER the IDCT, so it saves memory
 * but not CPU on a JPEG).
 *
 * <p><b>Never waits.</b> {@link #tryAcquire()} either gets a permit now or the
 * caller serves the ORIGINAL bytes — the endpoint's degrade-don't-fail
 * contract: a heavy image beats a queue of request threads parked behind a
 * decode, which would turn a burst on one image into an outage of every page.
 * Such a response is marked uncacheable by the controller, so a busy moment
 * does not pin a full-size original under a thumbnail URL for an hour.
 *
 * <p>Default 2 ({@code marketplace.listing.image-resize-concurrency}): a normal
 * photo decodes subsampled in tens of milliseconds, so two permits absorb
 * ordinary traffic (and the 1h public cache absorbs repeats), while the worst
 * case stays at two bounded decodes and two cores.
 *
 * <p>Since V25 a public read decodes only for an image stored before the
 * variants table (once per rendition); the decodes that matter are an upload's,
 * which take the SAME permits through {@link #waitingUpTo(Duration)} — a short
 * bounded wait, because an upload is authenticated and rare and would rather
 * wait a moment than leave a rendition to be made later. Sharing the semaphore
 * keeps the bound the whole service's, not per path.
 */
@Component
public class ImageDecodePermits {

    public static final int DEFAULT_PERMITS = 2;

    private final Semaphore semaphore;
    /** Null = never wait (the public read). */
    private final Duration wait;

    @Autowired
    public ImageDecodePermits(@Value("${marketplace.listing.image-resize-concurrency:2}") int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("marketplace.listing.image-resize-concurrency must be positive");
        }
        this.semaphore = new Semaphore(permits);
        this.wait = null;
    }

    private ImageDecodePermits(Semaphore semaphore, Duration wait) {
        this.semaphore = semaphore;
        this.wait = wait;
    }

    /**
     * The same permits, but {@link #tryAcquire()} waits up to {@code wait} for
     * one before giving up. For the upload path only — a public read must never
     * queue behind a decode.
     */
    public ImageDecodePermits waitingUpTo(Duration wait) {
        return new ImageDecodePermits(semaphore, wait);
    }

    /** The production default — for code built without Spring (tests). */
    public static ImageDecodePermits defaults() {
        return new ImageDecodePermits(DEFAULT_PERMITS);
    }

    /** A permit now (or within the {@link #waitingUpTo} bound), or false.
     *  Pair every true with {@link #release()}. */
    boolean tryAcquire() {
        if (wait == null) {
            return semaphore.tryAcquire();
        }
        try {
            return semaphore.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void release() {
        semaphore.release();
    }
}
