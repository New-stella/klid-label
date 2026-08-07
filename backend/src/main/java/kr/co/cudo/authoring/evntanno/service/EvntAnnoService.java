package kr.co.cudo.authoring.evntanno.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationInfo;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Phase 2 — event_annotation(외부 VLM VQA/CoT) 입력/조회 서비스.
 *
 * <p>영상(RAW_SN) 단위로 payload 를 저장·수정한다. 인가는 프레임 라벨 경로와 동일한
 * {@link LabelAccessGuard#verifyRawAccess}(WORKER 본인 배정만 / REVIEWER 전체)를 재사용한다.
 * 신규 저장 시 검토 row({@link LsEvntAnnoReview}, AUTO_GENERATED)를 함께 만들어 검수 진입을 준비한다.
 *
 * <p>검수 완료(APPROVED) 후 수정 시에만 {@link TaskModifiedEvent}(META_UPDATED)를 발행한다
 * (CLAUDE.md 작업 단위 통지 정책 — MetaService 와 동일 가드).
 *
 * <p><b>비식별 누락 신고 게이트</b>: 저장({@link #upsertOnce})은 인가 직후
 * {@link LabelAccessGuard#requireNotUnderDeidentReport} 로 신고 구간을 412 로 차단한다(역할 무관).
 * <b>조회({@link #get})는 차단하지 않는다</b> — 이 게이트의 조회 차단 범위는 라벨 좌표·프레임 이미지처럼
 * PII 위치를 특정하는 산출물에 한정되며, 여기까지 넓히지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class EvntAnnoService {

    /** 직렬화 payload 바이트 상한(CWE-770 부분 DoS 방어). jsonb 원문 저장이라 크기만 제한한다. */
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final LsEvntAnnoRepository annoRepository;
    private final LsEvntAnnoReviewRepository reviewRepository;
    private final LabelAccessGuard accessGuard;
    private final ReviewApprovalGate approvalGate;
    private final ApplicationEventPublisher eventPublisher;
    private final Validator validator;
    /**
     * 자기 프록시 참조 — {@link #upsert} 재시도 래퍼가 {@code @Transactional} 이 적용된 {@link #upsertOnce}
     * 를 <b>프록시 경유</b>로 호출하기 위함(self-invocation 은 AOP 프록시를 우회). 생성자 주입
     * ({@code ObjectProvider})으로 순환참조 없이 지연 해석하며 필드 주입 규칙을 지킨다.
     */
    private final ObjectProvider<EvntAnnoService> self;

    /** 영상 단위 event_annotation 조회. WORKER 는 본인 배정 영상만. */
    public EventAnnotationInfo get(Long rawSn, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        LsEvntAnno anno = annoRepository.findByRawSn(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "event_annotation 이 존재하지 않습니다."));
        return EventAnnotationInfo.from(anno, currentReviewStatus(anno.getEvntAnnoSn()));
    }

    /**
     * event_annotation 저장/수정(upsert) — <b>최초삽입 TOCTOU 안전 재시도 래퍼</b>(CWE-362).
     *
     * <p>동시 첫 저장 경합에서 한쪽은 {@code UK_LS_EVNT_ANNO_RAW} 위반으로 실패한다. PostgreSQL 은
     * unique 위반 시 <b>트랜잭션 전체를 abort</b> 시켜 같은 tx 안에서 재조회·update 폴백이 불가능하다.
     * 그래서 실제 작업({@link #upsertOnce})을 트랜잭션 경계로 두고, {@link DataIntegrityViolationException}
     * 이면 <b>새 트랜잭션으로 한 번 재시도</b>한다. 재시도 시점엔 승자 row 가 커밋돼 있어 update 경로로
     * 깨끗이 수렴한다(500 미노출). 각 시도는 커넥션을 순차로 하나만 점유하므로 커넥션 풀 고갈이 없다
     * (REQUIRES_NEW 중첩 방식은 스레드당 2커넥션을 잡아 소형 풀에서 교착 — 회피).
     *
     * <p>{@code NOT_SUPPORTED} — 이 래퍼 자신은 트랜잭션을 열지 않는다(클래스 기본 readOnly 트랜잭션이
     * 걸리면 {@link #upsertOnce} 가 그 read-only tx 에 편승해 INSERT 가 거부되므로). 각 {@code upsertOnce}
     * 호출이 프록시 경유로 <b>새 쓰기 트랜잭션</b>을 연다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.NOT_SUPPORTED)
    public EventAnnotationInfo upsert(Long rawSn, EventAnnotationPayload payload, TokenClaims actor) {
        try {
            return self.getObject().upsertOnce(rawSn, payload, actor);
        } catch (DataIntegrityViolationException e) {
            log.warn("[EvntAnno] concurrent first-insert conflict rawSn={} — retry as update", rawSn);
            return self.getObject().upsertOnce(rawSn, payload, actor);
        }
    }

    /**
     * upsert 단일 시도(트랜잭션 경계). 있으면 payload 교체(+REJECTED→PENDING 재제출 리셋), 없으면
     * 신규 생성 + 검토 row 생성. 최초삽입 경합 시 {@code save()} 가 IDENTITY 전략이라 즉시 flush 되어
     * {@link DataIntegrityViolationException} 을 던지고 이 tx 는 롤백된다(호출자 {@link #upsert} 가 재시도).
     * 검수 완료(APPROVED) 후 수정 시 TASK_MODIFIED(META_UPDATED) 통지 발행.
     */
    @Transactional("controlTransactionManager")
    public EventAnnotationInfo upsertOnce(Long rawSn, EventAnnotationPayload payload, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        // 비식별 누락 신고 구간(DE_IDNTF_YN='F')이면 저장 차단(412) — 인가 이후 평가되는 프리컨디션.
        //   신고는 "이 영상의 비식별이 잘못됐다"는 신호이므로, 그 구간에 사람이 새로 쓴 event_annotation 은
        //   검수를 거치지 않은 채 resolve 후 동결·export 를 타고 관제로 나간다(라벨 저장 412 와 같은 축 —
        //   LabelService.bulkUpsert). 판정은 DeidentReportGate 단일 원천에 위임한다(역할 무관).
        //   게이트는 payload 검증(400)보다 먼저 평가해 <b>어떤 쓰기도 시작되지 않게</b> 한다.
        accessGuard.requireNotUnderDeidentReport(rawSn);
        validatePayload(payload);
        String json = payload.toJson();
        validatePayloadSize(json);
        String actorSub = actor.sub();

        LsEvntAnno anno = annoRepository.findByRawSn(rawSn).orElse(null);
        if (anno == null) {
            // IDENTITY 전략이라 save() 가 INSERT 를 즉시 flush → 동시 첫 저장 경합 시 여기서 DIVE 발생,
            // tx 롤백 후 upsert() 가 재시도(그때는 승자 row 존재 → update 경로).
            anno = annoRepository.save(LsEvntAnno.create(rawSn, json, actorSub));
            reviewRepository.save(LsEvntAnnoReview.createAuto(
                    anno.getEvntAnnoSn(), LsEvntAnnoReview.META_TYPE_VLM,
                    LsEvntAnnoReview.STTS_AUTO_GENERATED, actorSub));
            log.info("[EvntAnno] created rawSn={} evntAnnoSn={}", rawSn, anno.getEvntAnnoSn());
        } else {
            anno.updatePayload(json, actorSub);
            resetReviewIfRejected(anno);
            log.info("[EvntAnno] updated rawSn={} evntAnnoSn={}", rawSn, anno.getEvntAnnoSn());
        }

        // 검수 완료(APPROVED) 후 수정 시에만 관제 outbound TASK_MODIFIED 통지 발행 (MetaService 와 동일 가드).
        // Phase 7a-1 — exportRegenerated=false 는 클래스 주석 "재동결(materialize)을 하지 않아…" 대로 유지하되,
        //   needsRecheck=true (사람이 콘텐츠를 고치는 경로): 재검토 표시만 세운다.
        if (approvalGate.isApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, null, ChangeType.META_UPDATED, accessGuard.parseUserNo(actorSub), false, true));
        }
        return EventAnnotationInfo.from(anno, currentReviewStatus(anno.getEvntAnnoSn()));
    }

    /**
     * payload 명시 검증 (CWE-20). Controller @Valid 와 별개로, 서비스 진입점에서도
     * Bean Validation 제약(event_class @NotBlank, 크기 @Size 등)을 강제해 우회 입력을 차단한다.
     *
     * <p>CWE-209 — 검증 상세(위반 메시지·입력값)는 <b>서버 로그에만</b> 남기고, 클라이언트에는 고정
     * 문구만 반환한다(입력 원문·내부 제약 상세 에코 금지). 로그에는 필드 경로만 남기고 값은 남기지 않는다.
     */
    private void validatePayload(EventAnnotationPayload payload) {
        if (payload == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation payload 는 필수입니다.");
        }
        Set<ConstraintViolation<EventAnnotationPayload>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            log.warn("[EvntAnno] payload validation failed count={} firstPath={}",
                    violations.size(), violations.iterator().next().getPropertyPath());
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation payload 검증에 실패했습니다.");
        }
    }

    /** 직렬화 payload 바이트 상한 검증 (CWE-770 부분 DoS 방어). */
    private void validatePayloadSize(String json) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAYLOAD_BYTES) {
            log.warn("[EvntAnno] payload too large bytes={} limit={}", bytes, MAX_PAYLOAD_BYTES);
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation payload 가 허용 크기를 초과했습니다.");
        }
    }

    /**
     * 반려(REJECTED)된 검토가 있으면 재저장(수정)을 재제출로 간주해 PENDING 으로 리셋한다(재검수 대기).
     * REJECTED 고정으로 인한 재승인 데드엔드를 해소한다. AUTO_GENERATED/PENDING/APPROVED 는 그대로 둔다.
     */
    private void resetReviewIfRejected(LsEvntAnno anno) {
        reviewRepository.findByEvntAnnoSn(anno.getEvntAnnoSn())
                .forEach(LsEvntAnnoReview::resubmit);
    }

    private String currentReviewStatus(Long evntAnnoSn) {
        return reviewRepository.findByEvntAnnoSn(evntAnnoSn).stream()
                .findFirst()
                .map(LsEvntAnnoReview::getRvwSttsCd)
                .orElse(null);
    }

}
