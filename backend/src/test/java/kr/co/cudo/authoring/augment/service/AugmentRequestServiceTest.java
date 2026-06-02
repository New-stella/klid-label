package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class AugmentRequestServiceTest {

    @Autowired private AugmentRequestService service;
    @Autowired private LsRawDataStatusRepository statusRepository;

    @MockBean private ExternalAugmentClient externalClient;

    private TokenClaims reviewer;
    private TokenClaims worker;

    @BeforeEach
    void setup() {
        reviewer = new TokenClaims("1",   Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        worker   = new TokenClaims("100", Role.WORKER,   Channel.INTERNAL, Instant.now().plusSeconds(3600));
        statusRepository.deleteAll();
        // 외부 통보는 기본적으로 성공 응답
        given(externalClient.requestAugment(anyList(), anyList())).willReturn(true);
    }

    private void seedStatus(Long rawDataId, String dataSttsCd) {
        LsRawDataStatus status = LsRawDataStatus.initial(rawDataId);
        status.transitionTo(dataSttsCd);
        statusRepository.saveAndFlush(status);
    }

    @Test
    @DisplayName("AugmentRequestService_검수_완료_영상_3건_요청시_정상_jobId_반환")
    void requestSucceedsWhenAllVideosApproved() {
        seedStatus(1001L, LsRawDataStatus.STTS_APPROVED);
        seedStatus(1002L, LsRawDataStatus.STTS_APPROVED);
        seedStatus(1003L, LsRawDataStatus.STTS_APPROVED);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(1001L, 1002L, 1003L),
                List.of(AugmentTypeCode.WINTER, AugmentTypeCode.NIGHT, AugmentTypeCode.RAIN)
        );

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.jobId()).isNotNull();
        assertThat(resp.videoCount()).isEqualTo(3);
        assertThat(resp.typeCount()).isEqualTo(3);
        assertThat(resp.requestedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
    }

    @Test
    @DisplayName("AugmentRequestService_검수_미완료_영상_포함시_NOT_REVIEWED_blockedVideoIds_포함")
    void rejectsWhenSomeVideosNotApproved() {
        seedStatus(2001L, LsRawDataStatus.STTS_APPROVED);
        seedStatus(2002L, LsRawDataStatus.STTS_IN_REVIEW);
        seedStatus(2003L, LsRawDataStatus.STTS_PENDING);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(2001L, 2002L, 2003L),
                List.of(AugmentTypeCode.WINTER)
        );

        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.NOT_REVIEWED);
                    assertThat(ce.getDetails()).isNotNull();
                    @SuppressWarnings("unchecked")
                    var details = (java.util.Map<String, Object>) ce.getDetails();
                    @SuppressWarnings("unchecked")
                    List<Long> blocked = (List<Long>) details.get("blockedVideoIds");
                    assertThat(blocked).containsExactlyInAnyOrder(2002L, 2003L);
                });
    }

    @Test
    @DisplayName("AugmentRequestService_LsRawDataStatus_row가_없는_영상_포함시_NOT_REVIEWED")
    void rejectsWhenStatusRowMissing() {
        seedStatus(3001L, LsRawDataStatus.STTS_APPROVED);
        // 3002L row 없음 → 미검수로 간주

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(3001L, 3002L),
                List.of(AugmentTypeCode.NIGHT)
        );

        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.NOT_REVIEWED);
                    @SuppressWarnings("unchecked")
                    var details = (java.util.Map<String, Object>) ce.getDetails();
                    @SuppressWarnings("unchecked")
                    List<Long> blocked = (List<Long>) details.get("blockedVideoIds");
                    assertThat(blocked).containsExactly(3002L);
                });
    }

    @Test
    @DisplayName("AugmentRequestService_videoIds_중복_입력시_distinct_처리되어_정상_진행")
    void distinctVideoIds() {
        seedStatus(4001L, LsRawDataStatus.STTS_APPROVED);
        seedStatus(4002L, LsRawDataStatus.STTS_APPROVED);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(4001L, 4002L, 4001L, 4002L),
                List.of(AugmentTypeCode.WINTER)
        );

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.videoCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AugmentRequestService_types_중복_입력시_distinct_처리되어_정상_진행")
    void distinctTypes() {
        seedStatus(5001L, LsRawDataStatus.STTS_APPROVED);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(5001L),
                List.of(AugmentTypeCode.WINTER, AugmentTypeCode.WINTER, AugmentTypeCode.NIGHT)
        );

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.typeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AugmentRequestService_ExternalAugmentClient_실패시_트랜잭션_영향_없이_성공_응답")
    void externalFailureDoesNotBlockSuccess() {
        seedStatus(6001L, LsRawDataStatus.STTS_APPROVED);
        given(externalClient.requestAugment(anyList(), anyList()))
                .willThrow(new RuntimeException("외부 시스템 장애 (mock)"));

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(6001L),
                List.of(AugmentTypeCode.WINTER)
        );

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.jobId()).isNotNull();
        assertThat(resp.videoCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AugmentRequestService_WORKER_요청시_FORBIDDEN")
    void workerCannotRequest() {
        seedStatus(7001L, LsRawDataStatus.STTS_APPROVED);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(7001L),
                List.of(AugmentTypeCode.WINTER)
        );

        assertThatThrownBy(() -> service.request(req, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
