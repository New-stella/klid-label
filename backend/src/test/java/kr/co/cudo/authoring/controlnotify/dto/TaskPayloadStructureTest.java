package kr.co.cudo.authoring.controlnotify.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 (통지 인프라) — TaskCompletedPayload / TaskModifiedPayload 구조 검증.
 *
 * <p>CWE-359: 페이로드에 PII(비밀번호, 주민번호 등), 토큰, 원본 이미지 경로가
 * 포함되지 않도록 필드 이름 화이트리스트로 구조를 검증한다.
 */
class TaskPayloadStructureTest {

    /** 페이로드에 절대 포함되면 안 되는 필드명 (PII / Credential / 원본경로). */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "password", "secret", "token", "credential",
            "ssn", "jumin", "cardNumber", "phoneNumber", "email",
            "rawPath", "originalPath", "imagePath", "filePath");

    @Test
    @DisplayName("TaskCompletedPayload에_PII_미포함")
    void taskCompletedPayloadNoPii() {
        // given
        RecordComponent[] components = TaskCompletedPayload.class.getRecordComponents();

        // when
        Set<String> fieldNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // then
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(fieldNames).doesNotContain(forbidden);
        }
        // 필수 필드 존재 확인
        assertThat(fieldNames).contains("eventType", "rawSn", "requestId");
    }

    @Test
    @DisplayName("TaskCompletedPayload_record_정상_생성")
    void taskCompletedPayloadConstruction() {
        // given / when
        TaskCompletedPayload payload = new TaskCompletedPayload(
                "TASK_COMPLETED", 100L, "reviewer1",
                Instant.now(), 30, 25, "req-uuid-001");

        // then
        assertThat(payload.eventType()).isEqualTo("TASK_COMPLETED");
        assertThat(payload.rawSn()).isEqualTo(100L);
        assertThat(payload.requestId()).isEqualTo("req-uuid-001");
    }

    @Test
    @DisplayName("TaskModifiedPayload에_PII_미포함")
    void taskModifiedPayloadNoPii() {
        // given
        RecordComponent[] components = TaskModifiedPayload.class.getRecordComponents();

        // when
        Set<String> fieldNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // then
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(fieldNames).doesNotContain(forbidden);
        }
        assertThat(fieldNames).contains("eventType", "rawSn", "requestId");
    }

    @Test
    @DisplayName("TaskModifiedPayload_record_정상_생성")
    void taskModifiedPayloadConstruction() {
        // given / when
        TaskModifiedPayload payload = new TaskModifiedPayload(
                "TASK_MODIFIED", 200L, Instant.now(),
                List.of(1L, 2L, 3L),
                List.of("LABEL_ADDED", "LABEL_UPDATED", "META_UPDATED"),
                "req-uuid-002");

        // then
        assertThat(payload.eventType()).isEqualTo("TASK_MODIFIED");
        assertThat(payload.rawSn()).isEqualTo(200L);
        assertThat(payload.frameIds()).hasSize(3);
        assertThat(payload.changeTypes()).contains("LABEL_ADDED");
    }
}
