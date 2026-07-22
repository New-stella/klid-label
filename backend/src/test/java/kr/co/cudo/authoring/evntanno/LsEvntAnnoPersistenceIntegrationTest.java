package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload.CaptionCandidate;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload.EvidenceCandidate;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LS_EVNT_ANNO / LS_EVNT_ANNO_REVIEW 영속화 통합 테스트
 * (@SpringBootTest + Testcontainers PostgreSQL, Flyway V127 적용 + ddl-auto=validate).
 *
 * <p>jsonb 컬럼(ANNO_CN) 왕복 무손실 + 4000자 초과 저장(길이 제한 없음) + 검토 상태 머신 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsEvntAnnoPersistenceIntegrationTest {

    @Autowired private LsEvntAnnoRepository evntAnnoRepository;
    @Autowired private LsEvntAnnoReviewRepository reviewRepository;
    @Autowired private VideoRepository rawRepository;

    private Long newRawSn() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "clip-" + System.nanoTime(), "cctv-1", "INTRUSION", "11110",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
        return raw.getRawSn();
    }

    private String samplePayloadJson() {
        return new EventAnnotationPayload(
                "INTRUSION", "무슨 일이 발생했는가?",
                Map.of("c1", new CaptionCandidate("담을 넘는 인물",
                        List.of("1단계 인물탐지", "2단계 근접", "3단계 월담"))),
                "무단 침입",
                Map.of("c1", new EvidenceCandidate("프레임 10~12", List.of(10, 11, 12),
                        List.of("obj-1"), List.of(List.of(0.1, 0.2, 0.5, 0.8)), List.of("person")))
        ).toJson();
    }

    @Test
    @DisplayName("LsEvntAnno_생성시_rawSn과_payload가_저장되고_재조회_일치한다")
    void create_persist_and_reload_matches() {
        // given
        Long rawSn = newRawSn();
        String payloadJson = samplePayloadJson();

        // when
        LsEvntAnno saved = evntAnnoRepository.saveAndFlush(
                LsEvntAnno.create(rawSn, payloadJson, "system"));
        evntAnnoRepository.flush();

        // then: findByRawSn 재조회 + jsonb 왕복 무손실
        LsEvntAnno reloaded = evntAnnoRepository.findByRawSn(rawSn).orElseThrow();
        assertThat(reloaded.getEvntAnnoSn()).isEqualTo(saved.getEvntAnnoSn());
        assertThat(reloaded.getRawSn()).isEqualTo(rawSn);
        assertThat(reloaded.getRegDt()).isNotNull();
        EventAnnotationPayload restored = EventAnnotationPayload.fromJson(reloaded.getAnnoCn());
        assertThat(restored.eventClass()).isEqualTo("INTRUSION");
        assertThat(restored.caption().get("c1").cot()).hasSize(3);
    }

    @Test
    @DisplayName("updatePayload_호출시_MDFCN_DT_갱신되고_payload_교체된다")
    void updatePayload_replaces_and_touches_mdfcn() {
        // given
        Long rawSn = newRawSn();
        LsEvntAnno saved = evntAnnoRepository.saveAndFlush(
                LsEvntAnno.create(rawSn, samplePayloadJson(), "system"));
        assertThat(saved.getMdfcnDt()).isNull();

        // when: payload 교체
        String newJson = new EventAnnotationPayload("FALL", "q2", null, "a2", null).toJson();
        saved.updatePayload(newJson, "reviewer1");
        evntAnnoRepository.saveAndFlush(saved);

        // then
        LsEvntAnno reloaded = evntAnnoRepository.findByRawSn(rawSn).orElseThrow();
        assertThat(reloaded.getMdfcnDt()).isNotNull();
        assertThat(reloaded.getMdfcnId()).isEqualTo("reviewer1");
        assertThat(EventAnnotationPayload.fromJson(reloaded.getAnnoCn()).eventClass()).isEqualTo("FALL");
    }

    @Test
    @DisplayName("ANNO_CN_4000자_초과_payload_저장_성공")
    void anno_cn_over_4000_chars_persists() {
        // given: 4000자를 크게 초과하는 caption(jsonb 는 길이 제한 없음)
        String huge = "가".repeat(6000);
        String payloadJson = new EventAnnotationPayload(
                "INTRUSION", "q", Map.of("c1", new CaptionCandidate(huge, List.of("s1"))), "a", null).toJson();
        assertThat(payloadJson.length()).isGreaterThan(4000);
        Long rawSn = newRawSn();

        // when
        LsEvntAnno saved = evntAnnoRepository.saveAndFlush(
                LsEvntAnno.create(rawSn, payloadJson, "system"));
        evntAnnoRepository.flush();

        // then: 절단 없이 저장·재조회
        LsEvntAnno reloaded = evntAnnoRepository.findByRawSn(rawSn).orElseThrow();
        EventAnnotationPayload restored = EventAnnotationPayload.fromJson(reloaded.getAnnoCn());
        assertThat(restored.caption().get("c1").captionText()).hasSize(6000);
        assertThat(saved.getEvntAnnoSn()).isNotNull();
    }

    @Test
    @DisplayName("LsEvntAnnoReview_영속화_승인전이_저장된다")
    void review_persist_and_approve() {
        // given
        Long rawSn = newRawSn();
        LsEvntAnno anno = evntAnnoRepository.saveAndFlush(
                LsEvntAnno.create(rawSn, samplePayloadJson(), "system"));
        LsEvntAnnoReview review = reviewRepository.saveAndFlush(LsEvntAnnoReview.createAuto(
                anno.getEvntAnnoSn(), LsEvntAnnoReview.META_TYPE_VLM,
                LsEvntAnnoReview.STTS_PENDING, "system"));

        // when
        review.approve("reviewer1");
        reviewRepository.saveAndFlush(review);

        // then
        List<LsEvntAnnoReview> found = reviewRepository.findByEvntAnnoSn(anno.getEvntAnnoSn());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRvwSttsCd()).isEqualTo(LsEvntAnnoReview.STTS_APPROVED);
        assertThat(found.get(0).getRvwId()).isEqualTo("reviewer1");
    }
}
