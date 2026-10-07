package com.kk.project.service;

import com.kk.common.service.AppConfigService;
import com.kk.config.KMessageProperties;
import com.kk.project.entity.DeadlineReminderMessage;
import com.kk.project.entity.Project;
import com.kk.project.repo.DeadlineReminderMessageRepository;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.XxlJobRefRepository;
import com.kk2004.kmessage.sdk.KMessageClient;
import com.kk2004.kmessage.sdk.KMessageClient.CardMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectDeadlineReminderServiceTest {
    final ProjectRepository projects = mock(ProjectRepository.class);
    final XxlJobRefRepository refs = mock(XxlJobRefRepository.class);
    final AppConfigService config = mock(AppConfigService.class);
    final DeadlineReminderMessageRepository snapshots = mock(DeadlineReminderMessageRepository.class);
    final KMessageClient client = mock(KMessageClient.class);
    final Map<String, DeadlineReminderMessage> stored = new HashMap<>();
    Project project;

    ProjectDeadlineReminderService service() {
        var service = new ProjectDeadlineReminderService(projects, refs, config, new KMessageProperties(), snapshots);
        ReflectionTestUtils.setField(service, "kMessageClient", client);
        return service;
    }

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(28L);
        project.setName("Original project");
        project.setEndAt(Instant.now().plusSeconds(1800));
        project.setDeadlineNotifyHours(1);
        when(projects.findById(28L)).thenReturn(Optional.of(project));
        when(config.getRaw(AppConfigService.KEY_KMESSAGE_GROUP_ID)).thenReturn("group-original");
        when(snapshots.findByIdempotencyKey(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(snapshots.saveAndFlush(any())).thenAnswer(i -> {
            DeadlineReminderMessage snapshot = i.getArgument(0);
            stored.put(snapshot.getIdempotencyKey(), snapshot);
            return snapshot;
        });
    }

    @Test
    void retryAfterRestartReusesCommittedCardAndDestination() {
        when(client.sendToGroup(anyString(), any(CardMessage.class), anyString()))
                .thenThrow(new RuntimeException("response lost")).thenReturn(null);
        assertThatThrownBy(() -> service().sendReminder(28L)).hasMessageContaining("response lost");
        assertThat(stored).hasSize(1);
        project.setName("Changed during retry");
        project.setTotalSubmitters(99);
        when(config.getRaw(AppConfigService.KEY_KMESSAGE_GROUP_ID)).thenReturn("group-changed");
        service().sendReminder(28L);
        var calls = mockingDetails(client).getInvocations().stream().toList();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).getArguments()).containsExactly(calls.get(0).getArguments());
        verify(snapshots, times(1)).saveAndFlush(any());
    }

    @Test
    void changedDeadlineCreatesNewReminder() {
        service().sendReminder(28L);
        project.setEndAt(project.getEndAt().plusSeconds(60));
        service().sendReminder(28L);
        assertThat(stored).hasSize(2);
        verify(client, times(2)).sendToGroup(eq("group-original"), any(CardMessage.class), anyString());
    }

    @Test
    void cannotSendBeforeSnapshotCommit() {
        doThrow(new IllegalStateException("database unavailable")).when(snapshots).saveAndFlush(any());
        assertThatThrownBy(() -> service().sendReminder(28L)).hasMessageContaining("database unavailable");
        verifyNoInteractions(client);
    }

    @Test
    void concurrentInsertUsesWinningSnapshot() {
        doAnswer(i -> {
            DeadlineReminderMessage winner = i.getArgument(0);
            winner.setGroupId("group-winner");
            winner.setCardJson("{\"winner\":true}");
            stored.put(winner.getIdempotencyKey(), winner);
            throw new DataIntegrityViolationException("duplicate key");
        }).when(snapshots).saveAndFlush(any());
        service().sendReminder(28L);
        verify(client).sendToGroup(eq("group-winner"), eq(CardMessage.of(Map.of("winner", true))), anyString());
    }
}
