package com.kk.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UploadContentTypeResolverTest {

    @Test
    void keepsExplicitNonBinaryContentType() {
        assertThat(UploadContentTypeResolver.resolve("cover.png", "application/custom"))
                .isEqualTo("application/custom");
    }

    @Test
    void infersPreviewableTypesWhenTypeIsMissingOrGeneric() {
        assertThat(UploadContentTypeResolver.resolve("cover.PNG", null)).isEqualTo("image/png");
        assertThat(UploadContentTypeResolver.resolve("song.mp3", "application/octet-stream"))
                .isEqualTo("audio/mpeg");
        assertThat(UploadContentTypeResolver.resolve("movie.webm", "binary/octet-stream"))
                .isEqualTo("video/webm");
    }

    @Test
    void leavesUnknownExtensionsAsBinary() {
        assertThat(UploadContentTypeResolver.resolve("archive.unknown", null))
                .isEqualTo(UploadContentTypeResolver.BINARY);
    }
}
