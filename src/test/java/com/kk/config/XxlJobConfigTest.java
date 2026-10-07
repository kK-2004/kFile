package com.kk.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class XxlJobConfigTest {
    @TempDir Path root;

    @Test
    void createsMissingParentsAndLeavesNoProbeFiles() throws Exception {
        Path directory = root.resolve("logs/xxl-job/jobhandler");
        assertThat(XxlJobConfig.prepareLogDirectory(directory.toString())).isEqualTo(directory);
        assertThat(directory).isDirectory();
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsAFileInPlaceOfDirectory() throws Exception {
        Path file = Files.createFile(root.resolve("logs"));
        assertThatThrownBy(() -> XxlJobConfig.prepareLogDirectory(file.resolve("jobhandler").toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("XXL_JOB_LOGPATH");
    }

    @Test
    void rejectsReadOnlyMountBeforeExecutorStarts() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")));
        Path directory = Files.createDirectory(root.resolve("readonly"));
        var original = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            assertThatThrownBy(() -> XxlJobConfig.prepareLogDirectory(directory.toString()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("日志目录不可写");
        } finally {
            Files.setPosixFilePermissions(directory, original);
        }
    }
}
