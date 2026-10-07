package com.kk.share.controller;

import com.kk.share.ShareCleanupTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareCleanupControllerTest {

    @Mock private ShareCleanupTask cleanupTask;

    @Test
    void runsCleanupForXxlJobCurl() {
        ShareCleanupController controller = new ShareCleanupController(cleanupTask);
        ShareCleanupTask.CleanupResult expected = new ShareCleanupTask.CleanupResult(
                Instant.parse("2026-09-21T17:30:00Z"), 1, 2, 3, 4, 10);
        when(cleanupTask.cleanup()).thenReturn(expected);

        assertThat(controller.cleanup()).isSameAs(expected);
        verify(cleanupTask).cleanup();
    }
}
