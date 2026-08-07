package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 영상 단위 개인정보(익명·가명·개인정보 포함여부) 메타 조회/저장 서비스 (V163).
 *
 * <p>선행 Phase 에서 업로드 시 {@code PRVC_TYPE_CD} 입력이 사라지고 {@code PRVC} 고정(fail-closed)이
 * 되었으므로, <b>이 화면이 영상의 개인정보 판정을 정정하는 유일한 통로</b>다. 저장값은 학습데이터 export
 * JSON 의 <b>video 블록</b> 개인정보 3필드 <b>비식별(DEIDENTIFIED) 축</b>으로 나간다
 * ({@link ExportPrivacyPolicy}). 같은 블록의 <b>원천(ORIGINAL) 축</b>은 이 화면이 아니라
 * <b>관제 인입값</b>({@code LS_DATA_INGEST}, V166/V170)에서 온다 — 두 축은 대상이 다르므로 이 화면의
 * 저장값이 원천 산출물에 실리지 않는다(2026-08-04 원천 축 전환. 구 "원천은 판정하지 않고 null" 서술 폐기).
 *
 * <p>조회는 <b>수동 저장값 우선 + 프리필</b>이다 — 값이 있으면 그 값({@code MANUAL}), 없으면 비식별
 * 기본상수({@link ExportPrivacyPolicy#DEID_DEFAULT_ANONYMITY} 등)를 {@code DERIVED} 출처로 채워 반환한다.
 * 상수의 단일 원천은 {@code ExportPrivacyPolicy} 다. 저장은 <b>PUT 전체 교체</b>다.
 *
 * <h3>★ 적재 기본값 (2026-08-04) — 프리필이 타는 경로가 좁아졌다</h3>
 * <p>이제 <b>영상 생성 시점에 비식별 3필드가 실제 값({@code Y}/{@code N}/{@code N})으로 INSERT</b> 된다
 * ({@code LsDataRaw} 의 {@code @Builder} 생성자). 따라서 신규 영상은 항상 저장값을 갖고 프리필을 타지
 * 않으며 {@code *Source} 는 {@code MANUAL} 이다. 프리필({@code DERIVED})이 남는 경로는
 * <b>레거시 행(이 변경 이전에 적재된 행) 하나뿐</b>이다. 그래서 프리필과 상수는 <b>안전망으로 존치</b>
 * 한다(제거하면 그 행들의 export 가 빈 값이 된다).
 *
 * <p>⚠ <b>의미 축소(2026-08-04 확정) — 그러나 존치한다</b>: 이 프리필·출처 병기({@code *Source})는
 * 이제 <b>레거시 행</b> 표시용이다. 신규 영상은 INSERT 시점에 값이 채워지므로 항상 {@code MANUAL} 로
 * 보이며, <b>"적재 기본값"과 "사람이 고른 값"을 구분하지 않는 것이 확정 정책</b>이다 — 이 변화는
 * 의도된 것이다. 그럼에도 제거하지 않는 이유는 ①레거시 행이 실재하고 ②응답 필드 삭제가 외부 FE 계약
 * 파괴이기 때문이다.
 *
 * <p>⚠⚠ <b>구 존치 근거는 폐기됐다 (2026-08-04) — 그러나 결론(존치)은 그대로다</b>:
 * 구 서술은 존치 근거를 <b>"비식별 누락 신고 리셋이 항상 명시적으로 NULL 을 쓰므로
 * ({@code DeidentReportService} → {@code changePrivacyMeta(null,null,null)} ·
 * {@code resetPrivacyMetaByRawSn}) 리셋 직후에도 NULL 이 생기고, 그때 {@code DERIVED} 가
 * '아직 재판정하지 않았다'는 신호로 살아난다"</b>로 들었다. <b>신고 시 개인정보 3필드 리셋 자체가
 * 폐기</b>됐으므로(2026-08-04 사용자 확정 — 라벨 보존 정책과 같은 취지, 경위는
 * {@code DeidentReportService} 클래스/5-1 주석) "리셋 직후"라는 경로는 <b>더 이상 존재하지 않는다</b>.
 * 남는 경로가 레거시 행 하나로 줄었을 뿐 프리필·{@code *Source}·상수는 <b>전부 유지</b>한다.
 *
 * <h3>★ 저장소를 라이브({@code LS_DATA_RAW})로 두고 동결 스냅샷을 만들지 않는 이유</h3>
 * 촬영환경({@code EnvironmentMetaService})은 수정 시 동결 스냅샷({@code LS_DATASET_VIDEO_META})을
 * <b>재동결</b>하는데, 이는 그 값의 <b>두 번째 소비자</b>인 데이터마트 뷰
 * ({@code V_COMPLETED_VIDEO.WTHR_NM}/{@code DAY_NGT_CD}/{@code SESN_CD})가 스냅샷을 읽기 때문이다.
 * 개인정보 3필드는 <b>뷰에 없고 소비자가 export JSON 하나뿐</b>이며, export 는 라이브 {@code LS_DATA_RAW}
 * 를 이미 로드한다({@code DatasetExportTxService}). 따라서 스냅샷 컬럼을 신설하면 얻는 것 없이
 * <b>스냅샷 해시 인코딩만 바뀌어 기존 승인 영상이 전량 재동결</b>되는 부작용만 남는다.
 * 대신 승인 후 수정이 산출물에 반영되도록 ①{@code TaskModifiedEvent(exportRegenerated=true)} 로
 * export 를 새 버전으로 재생성하고 ②이 3필드를 {@code LabelContentHasher} 입력에 편입해 멱등 skip 으로
 * stale 고착되지 않게 한다(둘 중 하나라도 빠지면 저장은 됐는데 산출물이 안 바뀐다).
 *
 * <h3>동시성 (CWE-362) — ★ 구 주석 "경합 창 자체가 없다" 는 <b>틀렸다</b> (2026-08-03 정정)</h3>
 * 구 주석은 "{@code materialize} 가 이 3컬럼을 읽지 않으므로 동시 승인과의 창이 없다"고 단정했으나
 * <b>절반만 참</b>이었다. 동결({@code LS_DATASET_VIDEO_META})이 이 컬럼을 읽지 않는 것은 사실이지만,
 * <b>이 필드의 실제 소비자는 export</b> 이고 {@code DatasetExportTxService} 는 라이브
 * {@code LS_DATA_RAW} 를 {@code findById} 로 직독한다. 즉 창은 사라진 게 아니라 <b>동결 → 산출로
 * 옮겨갔다</b>. 재현 시나리오:
 * <ol>
 *   <li>PUT 이 상태를 읽어 {@code PENDING} → <b>통지 미발행 확정</b></li>
 *   <li>직후 승인 트랜잭션 커밋 → AFTER_COMMIT {@code @Async} export 가 <b>새 트랜잭션</b>에서
 *       MVCC 구 스냅샷(수동값 null)을 읽어 {@code v1} 에 기본상수({@code N})를 기록</li>
 *   <li>PUT 커밋 → <b>재산출 트리거 없음</b> → 관제가 픽업하는 최신 폴더가 영구히 "개인정보 없음"</li>
 * </ol>
 * 비가역 <b>과소 신고(under-declaration)</b> 라 무시할 수 없다.
 *
 * <p><b>차단 방법</b>: 승인 판정 전에 ①{@code flush} 로 3컬럼 UPDATE 를 내보내 대상 raw 행을 잠그고
 * ②{@code materialize}(승인 경로의 동결)와 <b>동일한 rawSn advisory 락</b>
 * ({@code pg_advisory_xact_lock})을 획득한다 — {@code EnvironmentMetaService} 와 완전히 같은 순서다.
 * 승인이 먼저 커밋되면 우리가 {@code APPROVED} 를 관측해 재생성 통지를 발행하고, 우리가 먼저면 승인의
 * {@code materialize} 가 우리 커밋 후에야 락을 얻어 <b>최신 수동값</b>으로 동결·export 한다.
 * 어느 순서든 산출물이 stale 로 고착되지 않는다. advisory 는 트랜잭션 종료 시 자동 해제된다.
 *
 * <h3>★ 왜 {@code FOR SHARE}(상태 행 공유락)로 하면 안 되는가 — 교착(40P01) (2026-08-03 DEV_FIX 2차)</h3>
 * 1차 수정은 ②를 {@code LS_RAW_DATA_STATUS} 행 {@code PESSIMISTIC_READ}
 * ({@code findByRawDataIdForShare})로 구현하면서 "{@code approve} 는 {@code LS_DATA_RAW} 를 잠그지
 * 않으므로 사이클이 없다"를 근거로 삼았다. <b>그 근거 자체는 참이지만 상대를 잘못 봤다</b> — 교착 상대는
 * {@code approve} 가 아니라 <b>배치 상태 전이</b>({@code BatchTransitionService})다.
 * <pre>
 *   T1 = 이 PUT            : LS_DATA_RAW(A)  →  LS_RAW_DATA_STATUS(B)
 *   T2 = BatchTransition   : LS_RAW_DATA_STATUS(B)  →  LS_DATA_RAW(A)
 * </pre>
 * {@code markRawDataProcessingBlocked}/{@code markRawDataCompleted}/{@code markRawDataFailed} 은 모두
 * 같은 {@code REQUIRES_NEW} 트랜잭션 안에서 <b>먼저</b> 조건부 벌크 UPDATE 로 B 를 잠그고 <b>그 다음</b>
 * dirty checking 으로 A 를 UPDATE 한다. 그 진입점({@code markRawDataProcessingBlocked})은 주기 배치·수동
 * 재처리 등 <b>모든 배치 진입</b>에서 돌기 때문에, 라벨링 화면에서 개인정보 메타를 저장하는 동안 배치가
 * 걸리면 순환 대기가 성립한다(PostgreSQL 은 한쪽을 40P01 로 죽인다).
 *
 * <p><b>그래서 상태 행을 잠그지 않는다</b>: 승인 판정은 잠금 없는 조회({@code findByRawDataIdIn})로
 * 하고, 직렬화는 advisory 가 담당한다. 이렇게 하면 이 트랜잭션이 만드는 잠금 간선은
 * <b>{@code raw 행락 → advisory}</b> 하나뿐이라 기존 불변식({@code EnvironmentMetaService}·
 * {@code materialize})에 그대로 합류하고 <b>{@code raw → status} 간선이 새로 생기지 않는다</b>.
 * (승인 경로는 {@code status → advisory}, 배치는 {@code status → raw} 라 어느 조합에도 사이클이 없다.)
 * 근거를 남기는 이유: 이 정정에 근거가 없으면 다음 사람이 "판정 원천을 잠가야 정확하다"는 직관으로
 * 다시 {@code FOR SHARE} 로 되돌린다. 회귀는 {@code LockOrderGuardTest} 가 정적으로 막는다.
 *
 * <p><b>잠금 순서 불변식 유지</b>: {@code EnvironmentMetaService} 와 동일하게 <b>raw 행락 → advisory</b>
 * 단방향이다. 저장은 dirty checking 3컬럼 UPDATE 라 배치가 갱신하는 다른 컬럼을 덮지 않는다
 * (lost update 방어 유지).
 *
 * <p>보안: 인가는 라벨 경로와 동일한 {@link LabelAccessGuard#verifyRawAccess}(REVIEWER 전체 / WORKER 본인
 * 배정 영상만, 미존재 404·타인 403) 재사용으로 IDOR(CWE-639)을 차단한다. 입력은 {@code Y}/{@code N}
 * 화이트리스트만 허용하며, 거부 시 필드명만 알리고 입력 원문은 응답·로그 어디에도 싣지 않는다
 * (CWE-117/209). 판단값 자체도 로그에 남기지 않는다(CWE-359 — 개인정보 유무는 민감 신호).
 *
 * <p><b>비식별 신고 게이트(412) — 유지, 근거만 교체 (2026-08-04)</b>: 저장(PUT)은 신고 구간
 * ({@code DE_IDNTF_YN='F'})에서 차단한다. <b>근거</b>: 신고 구간은 <b>"비식별이 잘못됐다"고 알려진
 * 구간</b>이며, 그 잘못된 비식별본 위에서 내린 개인정보 판정을 이 구간에 새로 쓰면 resolve 후 재산출
 * 때 그 값이 그대로 관제로 나간다. 같은 구간에 라벨 조회를 412 로 막는 것과 <b>같은 축</b>이고,
 * 라벨은 작업락으로 409 차단되는데 개인정보 선언만 열려 있는 <b>비대칭</b>도 없앤다.
 * 게이트는 <b>영상 축·프레임 축 PUT 양쪽</b>에 그대로 건다(한쪽만 막으면 비대칭을 옮긴 것에 불과하다).
 *
 * <p>⚠ <b>구 근거 폐기(2026-08-04)</b>: 구 서술은 <b>"신고 접수가 이 3필드를 재판정 대상으로 리셋하는데
 * ({@code DeidentReportService}) 같은 구간에 PUT 으로 옛 판정을 되돌리면…"</b> 이었다. 신고는 이제
 * 3필드를 <b>리셋하지 않으므로</b> 그 근거는 성립하지 않는다. <b>게이트 자체는 리셋 여부와 무관하게
 * 성립</b>하므로 <b>제거하지 말 것</b> — 위 새 근거가 단독으로 게이트를 지탱한다.
 * <b>조회(GET)는 차단하지 않는다</b> — 값 자체는 PII 가 아니고, 막으면 신고 구간에 화면이 뜨지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VideoPrivacyMetaService {

    /** 허용 입력값(화이트리스트) — DTO {@code @Pattern} 과 동일 집합의 서비스단 이중 방어. */
    private static final Set<String> ALLOWED_YN = Set.of("Y", "N");

    private final VideoRepository videoRepository;
    private final LabelAccessGuard accessGuard;
    private final ReviewApprovalGate approvalGate;
    /** 승인({@code materialize})과 동일한 rawSn advisory 락 — 동시 승인 직렬화용(클래스 주석 "동시성"). */
    private final LsDatasetVideoMetaRepository videoMetaRepository;
    private final LsTaskEventLogRepository taskEventLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 영상 개인정보 메타 조회 — 수동 저장값 우선, 없으면 비식별 기본상수 프리필. */
    public VideoPrivacyMetaResponse get(Long rawSn, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        return toResponse(findRaw(rawSn));
    }

    /**
     * 영상 개인정보 메타 저장 — <b>전체 교체</b>(3필드 함께). 생략된 필드는 수동값 삭제로 처리된다.
     *
     * <p>검수 완료(APPROVED) 후 수정이면 관제 outbound {@code TASK_MODIFIED}(META_UPDATED,
     * {@code exportRegenerated=true})를 발행해 export 폴더를 <b>새 버전으로 전량 재생성</b>한 뒤 통지가
     * 나가게 한다 — 저장만 하고 파일이 옛 값이면 "데이터마트 학습데이터셋 동기화" 요구가 미충족된다.
     * 미검수 영상은 발행하지 않는다(최초 승인 시점 export 가 최신 값을 그대로 산출한다).
     */
    @Transactional("controlTransactionManager")
    public VideoPrivacyMetaResponse update(Long rawSn, VideoPrivacyMetaUpdateRequest req, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        // 인가 통과 후 평가하는 프리컨디션 — 신고 구간에는 개인정보 선언을 되돌릴 수 없다(412, 역할 무관).
        accessGuard.requireNotUnderDeidentReport(rawSn);
        if (req == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "개인정보 메타 입력이 필요합니다.");
        }
        String anonymity = validate(req.anonymity(), "anonymity");
        String pseudonymity = validate(req.pseudonymity(), "pseudonymity");
        String privacyIncluded = validate(req.privacyIncluded(), "privacyIncluded");

        LsDataRaw raw = findRaw(rawSn);
        boolean changed = differs(raw, anonymity, pseudonymity, privacyIncluded);
        Long actorNo = accessGuard.parseUserNo(actor.sub());
        // 영속 엔티티 dirty checking — 3필드만 UPDATE (전체 save/merge 금지).
        raw.changePrivacyMeta(anonymity, pseudonymity, privacyIncluded);
        // 감사(OWASP A09) — 판단값(Y/N)은 민감 신호라 남기지 않되(CWE-359/117), <b>누가</b> 언제 어느
        //   영상의 선언을 바꿨는지는 남긴다. actor 는 PII 가 아니며 이게 없으면 사후 추적이 불가능하다.
        //   ★ 로그만으로는 부족하다 — 행 단위 감사는 아래 LS_TASK_EVENT_LOG 가 담당한다.
        log.info("[VideoPrivacyMeta] updated rawSn={} actorNo={} changed={}", rawSn, actorNo, changed);
        auditPrivacyMetaUpdate(rawSn, actorNo, changed);

        // 동시 승인과의 경합 창 차단(클래스 주석 "동시성" 참조) — raw 행락(flush) → advisory 락 순서.
        //   ★ 상태 행을 잠그지 않는다(FOR SHARE 금지) — 배치 전이가 status → raw 순서라 사이클이 된다.
        videoRepository.flush();
        videoMetaRepository.acquireRawLock(rawSn);
        // Phase 7a-1 — needsRecheck=true (사람이 콘텐츠를 고치는 경로): 재검토 표시만 세운다.
        //   통지·export 흐름을 이 표시로 바꾸는 것은 후속(7a-2).
        if (approvalGate.isApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, null, ChangeType.META_UPDATED, actorNo, true, true));
        }
        return toResponse(raw);
    }

    // ---------- 내부 ----------

    /**
     * 허용값 검증 (CWE-20/79). null/공백은 "수동값 없음"으로 정규화하고, 그 외에는 {@code Y}/{@code N}
     * 이어야 한다. 실패 시 400 — 클라이언트에는 필드명만 알리고 입력 원문은 노출하지 않는다.
     */
    private String validate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!ALLOWED_YN.contains(normalized)) {
            log.warn("[VideoPrivacyMeta] rejected value field={}", field);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "개인정보 메타 " + field + " 값은 Y 또는 N 이어야 합니다.");
        }
        return normalized;
    }

    private LsDataRaw findRaw(Long rawSn) {
        return videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
    }

    /** 수동값 우선 + 비식별 기본상수 프리필 + 항목별 출처 표기. */
    private VideoPrivacyMetaResponse toResponse(LsDataRaw raw) {
        String anonymity = raw.getAnonyInclYn();
        String pseudonymity = raw.getPsdoInclYn();
        String privacyIncluded = raw.getPrvcInclYn();
        boolean manualAnonymity = isPresent(anonymity);
        boolean manualPseudonymity = isPresent(pseudonymity);
        boolean manualPrivacyIncluded = isPresent(privacyIncluded);
        return new VideoPrivacyMetaResponse(
                raw.getRawSn(),
                manualAnonymity ? anonymity : ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY,
                manualPseudonymity ? pseudonymity : ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY,
                manualPrivacyIncluded ? privacyIncluded : ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED,
                source(manualAnonymity), source(manualPseudonymity), source(manualPrivacyIncluded));
    }

    private static boolean isPresent(String yn) {
        return yn != null && !yn.isBlank();
    }

    private static String source(boolean manual) {
        return manual ? VideoPrivacyMetaResponse.SOURCE_MANUAL : VideoPrivacyMetaResponse.SOURCE_DERIVED;
    }

    /**
     * 영상 개인정보 선언 변경의 <b>행 단위 감사</b>(OWASP A09) — {@code LS_TASK_EVENT_LOG} 에 1행.
     *
     * <p><b>왜 이 축인가</b>: 이 테이블은 이미 <b>rawSn(영상) 스코프 + actor + 이벤트 종류 + 사유</b>를
     * 갖고 있고 배정/재배정/제출/승인/반려가 같은 타임라인에 누적된다(SCR-TASK-003). 개인정보 선언 정정은
     * 검수 워크플로우와 같은 입도의 영상 단위 행위이므로 신규 테이블 없이 그대로 합류시킨다.
     * (라벨 이력 테이블 {@code LS_DATA_LBL_HSTRY} 는 {@code SRC_SN NOT NULL} 인 <b>프레임</b> 스코프라
     * 영상 축 행을 담을 수 없다 — 1차 DEV_FIX 가 "그래서 행 단위 감사가 불가능하다"고 결론냈던 것은
     * <b>틀렸고</b>, 불가능한 것은 그 테이블 하나였다.)
     *
     * <p><b>판단값(Y/N)은 남기지 않는다</b>(CWE-359) — 개인정보 유무는 그 자체로 민감 신호이며 이 이력은
     * 작업 이력 화면에 노출된다. 남기는 것은 "누가·언제·어느 영상의 선언을 손댔는가"와 실제 값이
     * 달라졌는지 여부뿐이다. {@code RSN} 은 자유 입력이 아닌 고정 문구라 로그 위조(CWE-117) 표면이 없다.
     */
    private void auditPrivacyMetaUpdate(Long rawSn, Long actorNo, boolean changed) {
        taskEventLogRepository.save(LsTaskEventLog.privacyMetaUpdated(rawSn, actorNo, changed));
    }

    /** 저장 전후 값이 실제로 달라지는지 — 감사 로그의 {@code changed} 축(값 자체는 남기지 않는다). */
    private static boolean differs(LsDataRaw raw, String anonymity, String pseudonymity,
                                   String privacyIncluded) {
        return !Objects.equals(raw.getAnonyInclYn(), anonymity)
                || !Objects.equals(raw.getPsdoInclYn(), pseudonymity)
                || !Objects.equals(raw.getPrvcInclYn(), privacyIncluded);
    }
}
