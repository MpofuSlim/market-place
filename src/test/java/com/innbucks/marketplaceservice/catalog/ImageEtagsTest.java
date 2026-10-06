package com.innbucks.marketplaceservice.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImageEtagsTest {

    private static final String HEX = ImageEtags.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII));

    @Test
    void theTagIsTheSha256OfTheBytes() {
        assertThat(HEX).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ImageEtags.quoted(HEX)).isEqualTo('"' + HEX + '"');
    }

    @Test
    void ifNoneMatch_strongWeakListAndStar() {
        assertThat(ImageEtags.matches('"' + HEX + '"', HEX)).isTrue();
        assertThat(ImageEtags.matches("W/\"" + HEX + '"', HEX)).as("weak comparison").isTrue();
        assertThat(ImageEtags.matches("\"other\", \"" + HEX + '"', HEX)).isTrue();
        assertThat(ImageEtags.matches("*", HEX)).isTrue();
    }

    @Test
    void ifNoneMatch_misses() {
        assertThat(ImageEtags.matches(null, HEX)).isFalse();
        assertThat(ImageEtags.matches("", HEX)).isFalse();
        assertThat(ImageEtags.matches("\"other\"", HEX)).isFalse();
        assertThat(ImageEtags.matches('"' + HEX.substring(1) + '"', HEX)).isFalse();
        assertThat(ImageEtags.matches("*", null)).isFalse();
    }
}
