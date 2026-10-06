package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.catalog.ImageVariantRenderer.RenderedVariant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real codecs, no mocks: a stored rendition must be the bytes the per-request
 * resize used to return, so the comparison is against
 * {@link ImageResizer#resize} itself.
 */
class ImageVariantRendererTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ImageVariantRenderer renderer(ImageDecodePermits permits) {
        return new ImageVariantRenderer(ImagePixelBudget.defaults(), permits, registry);
    }

    private double resizes(String trigger) {
        return registry.get("marketplace.listing.image.resizes").tag("trigger", trigger).counter().count();
    }

    private static Map<ImageVariant, RenderedVariant> byVariant(List<RenderedVariant> rendered) {
        return rendered.stream().collect(Collectors.toMap(RenderedVariant::variant, Function.identity()));
    }

    @Test
    void anUploadRendersEveryWidth_byteIdenticalToTheOnReadResize() {
        for (String format : List.of("png", "jpeg")) {
            byte[] source = TestImages.gradient(1600, 1200, format);
            String contentType = "image/" + format;

            Map<ImageVariant, RenderedVariant> out =
                    byVariant(renderer(ImageDecodePermits.defaults()).renderAll(source, contentType));

            assertThat(out).containsOnlyKeys(ImageVariant.values());
            for (ImageVariant variant : ImageVariant.RESIZED) {
                RenderedVariant rendered = out.get(variant);
                ImageResizer.Resized onRead = ImageResizer.resize(source, contentType, variant.width(),
                        ImagePixelBudget.defaults(), ImageDecodePermits.defaults());
                assertThat(rendered.resized()).isTrue();
                assertThat(rendered.cacheable()).isTrue();
                assertThat(rendered.bytes()).as("%s %s", format, variant).isEqualTo(onRead.bytes());
                assertThat(rendered.contentType()).isEqualTo(contentType);
                assertThat(rendered.width()).isEqualTo(variant.width());
                assertThat(rendered.height()).isEqualTo(variant.width() * 3 / 4);
                assertThat(rendered.byteSize()).isEqualTo(rendered.bytes().length);
                assertThat(rendered.etag()).isEqualTo(ImageEtags.sha256Hex(rendered.bytes()));
            }
            RenderedVariant original = out.get(ImageVariant.ORIGINAL);
            assertThat(original.resized()).isFalse();
            assertThat(original.bytes()).as("never a second copy of the original").isNull();
            assertThat(original.etag()).isEqualTo(ImageEtags.sha256Hex(source));
            assertThat(original.width()).isEqualTo(1600);
            assertThat(original.height()).isEqualTo(1200);
        }
        assertThat(resizes("upload")).isEqualTo(8);
        assertThat(resizes("lazy")).isZero();
    }

    @Test
    void aWidthTheImageIsAlreadyNarrowerThanPassesTheOriginalThrough() {
        byte[] source = TestImages.gradient(300, 200, "png");

        Map<ImageVariant, RenderedVariant> out =
                byVariant(renderer(ImageDecodePermits.defaults()).renderAll(source, "image/png"));

        assertThat(out.get(ImageVariant.W120).resized()).isTrue();
        assertThat(out.get(ImageVariant.W240).resized()).isTrue();
        for (ImageVariant passThrough : List.of(ImageVariant.W480, ImageVariant.W960)) {
            RenderedVariant row = out.get(passThrough);
            assertThat(row.resized()).isFalse();
            assertThat(row.cacheable()).isTrue();
            assertThat(row.bytes()).isNull();
            assertThat(row.etag()).isEqualTo(ImageEtags.sha256Hex(source));
            assertThat(row.byteSize()).isEqualTo(source.length);
        }
    }

    @Test
    void webpIsStoredAsPassThroughEverywhere_withoutADecode() {
        byte[] source = HeaderOnlyImages.webpLossy(2000, 1500);

        List<RenderedVariant> out = renderer(ImageDecodePermits.defaults()).renderAll(source, "image/webp");

        assertThat(out).hasSize(ImageVariant.values().length)
                .allSatisfy(row -> {
                    assertThat(row.resized()).isFalse();
                    assertThat(row.contentType()).isEqualTo("image/webp");
                    assertThat(row.etag()).isEqualTo(ImageEtags.sha256Hex(source));
                });
    }

    @Test
    void anUploadThatCannotGetADecodePermitLeavesThoseWidthsToTheFirstRequest() {
        byte[] source = TestImages.gradient(800, 600, "png");
        ImageDecodePermits permits = new ImageDecodePermits(1);
        assertThat(permits.tryAcquire()).isTrue(); // held by someone else throughout

        List<RenderedVariant> out = renderer(permits).renderAll(source, "image/png");

        // 800 px wide: w960 needs no decode (never upscaled), so it is stored;
        // the three that would decode are left out, never stored as a busy answer.
        assertThat(out).extracting(RenderedVariant::variant)
                .containsExactly(ImageVariant.ORIGINAL, ImageVariant.W960);
        assertThat(out).allSatisfy(row -> assertThat(row.cacheable()).isTrue());
    }

    @Test
    void aLazyRenderIsCountedAsLazy_andTheOriginalNeedsNoResize() {
        byte[] source = TestImages.gradient(800, 600, "png");
        ImageVariantRenderer renderer = renderer(ImageDecodePermits.defaults());

        RenderedVariant original = renderer.render(source, "image/png", ImageVariant.ORIGINAL);
        assertThat(resizes("lazy")).isZero();
        assertThat(original.etag()).isEqualTo(ImageEtags.sha256Hex(source));

        RenderedVariant w240 = renderer.render(source, "image/png", ImageVariant.W240);
        assertThat(resizes("lazy")).isEqualTo(1);
        assertThat(w240.bytes()).isEqualTo(ImageResizer.resize(source, "image/png", 240).bytes());
    }

    @Test
    void aLazyRenderUsesTheNonWaitingPermits_anUploadTheWaitingView() {
        byte[] source = TestImages.gradient(800, 600, "png");
        RecordingPermits read = new RecordingPermits();
        ImageVariantRenderer renderer = renderer(read);

        RenderedVariant busy = renderer.render(source, "image/png", ImageVariant.W240);

        assertThat(busy.cacheable()).isFalse();
        assertThat(busy.etag()).isNull();
        assertThat(read.calls).isEqualTo(1);
        assertThat(read.uploadView.calls).as("a public read never queues behind a decode").isZero();

        renderer.renderAll(source, "image/png");
        assertThat(read.calls).isEqualTo(1);
        assertThat(read.uploadView.calls).isEqualTo(3); // w120, w240, w480; w960 needs no decode
        assertThat(read.uploadView.wait).isEqualTo(ImageVariantRenderer.UPLOAD_PERMIT_WAIT);
    }

    /** Never grants a permit; counts who asked. */
    private static final class RecordingPermits extends ImageDecodePermits {
        int calls;
        java.time.Duration wait;
        RecordingPermits uploadView;

        RecordingPermits() {
            super(1);
        }

        @Override
        public ImageDecodePermits waitingUpTo(java.time.Duration wait) {
            uploadView = new RecordingPermits();
            uploadView.wait = wait;
            return uploadView;
        }

        @Override
        boolean tryAcquire() {
            calls++;
            return false;
        }
    }
}
