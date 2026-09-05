package kr.co.cudo.authoring.meta.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.export.json.VlmDescriptionPolicy;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaResponse;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.transfer.ImportMetaKeys;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 5 — V1.7 정책: 외부 시스템이 생성한 시계열 메타의 검토·수정만 제공.
 *  - 메타 자동 생성 엔드포인트 없음 (외부 시스템 책임).
 *  - VLM/외부 메타에 대한 검토 상태 (LS_DATA_META_REVIEW) approve/reject API 제공.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class MetaService {

    /**
     * VLM 적재 키 네임스페이스 — 이 접두 안에서 <b>편집 허용은 화이트리스트</b>다({@link #EDITABLE_VLM_KEYS}).
     * 판정 술어({@link #isReadOnlyKey})는 이 서비스가 <b>단독 소유</b>한다. 접두·키 문자열을 DTO·컨트롤러·FE 로
     * 복제하면 키가 늘 때 조용히 드리프트한다({@code video.*} 판정에서 실제로 겪은 문제).
     */
    private static final String VLM_KEY_PREFIX = "vlm.";

    /**
     * {@code vlm.*} 중 <b>사람이 편집할 수 있는</b> 키. 여기 없는 {@code vlm.*} 는 전부 읽기 전용이다(fail-closed)
     * — 향후 읽기 전용 키가 늘어도 편집·저장 경로로 새지 않는다. [req: R12]
     *
     * <p>화이트리스트를 {@code vlm.*} 네임스페이스 <b>안으로 한정</b>하는 것이 핵심이다. 전체 키에 대해
     * 화이트리스트를 걸면 레거시 구간 키({@code 0-8} 등)와 {@code manual-timeseries}(FE 가 메타 0건일 때 쓰는
     * 수동 등록 슬롯)까지 막혀 정상 작업이 400 이 된다.
     */
    private static final Set<String> EDITABLE_VLM_KEYS = Set.of(VlmResultService.META_KEY_DESCRIPTION);

    private final LsDataMetaRepository metaRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    private final LsDataMetaReviewRepository metaReviewRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 검수 완료(APPROVED) 여부 판정용 영상 상태 조회. */
    private final ReviewApprovalGate approvalGate;

    public MetaResponse getByFrame(Long srcSn, TokenClaims actor) {
        LsDataSrc src = verifyAccess(srcSn, actor);
        List<LsDataMeta> metas = metaRepository.findByRawSn(src.getRawSn());
        return toResponse(metas);
    }

    /**
     * 메타 목록을 <b>편집 가능(시계열) / 기술({@code video.*}) / 화면 전용 읽기 / 이관 원문({@code import.*})</b>
     * 네 갈래로 분류한 뒤 검토상태를 조인해 응답 생성. metaSn 집합으로 검토행을 배치 조회(N+1 금지)하며,
     * 메타가 0건이면 검토행 조회조차 생략한다. 검토행 없는 메타는 검토 필드 null. [design: API-066]
     *
     * <p>분류 술어는 세 개이며 각각 <b>소유자가 하나</b>다 — {@code video.*} 는
     * {@link VideoMetaService#isTechnicalKey}(소유자 {@code VideoMetaService}) 를 재사용하고,
     * 읽기 전용 판정은 {@link #isReadOnlyKey}, 이관 원문 판정은 {@link #isImportedKey}(둘 다 소유자 = 이
     * 서비스)다. 접두 문자열을 DTO·FE 로 복제하면 소유자가 키를 늘릴 때 조용히 어긋난다 — 그래서 이관 접두는
     * 쓰는 쪽인 {@link ImportMetaKeys#PREFIX} 를 <b>참조</b>하고 여기서 다시 선언하지 않는다.
     *
     * <p><b>분기 순서</b>: 기존 두 판정({@code video.*} → 읽기 전용)을 <b>앞에 그대로 두고</b> 이관 판정을
     * 그 뒤·여집합 앞에 넣는다. 현재 세 접두({@code video.} · {@code vlm.} · {@code import.})는 서로 겹치지
     * 않아 순서를 바꿔도 결과가 같지만, 소유자가 접두를 늘렸을 때 기존 두 판정이 먼저 걸리도록 순서로
     * 못박아 둔다(기존 계약이 신규 분류에 잠식되지 않는다).
     *
     * <p>네 목록 모두 <b>버리지 않고</b> 반환한다(정보 유실 없음). 화면은 {@code items} 만 편집 가능하게 그리고
     * 나머지는 읽기 전용으로 표시한다. [req: R12]
     *
     * <p>배치 조회 대상은 <b>분류 전 전체 metaSn</b> 이다 — 기술메타·읽기 전용·이관 원문 키에는 통상 검토행이
     * 없지만(있다면 과거 수동 등록분) 조회 쿼리를 쪼개 왕복을 늘릴 이유가 없다.
     */
    private MetaResponse toResponse(List<LsDataMeta> metas) {
        if (metas.isEmpty()) {
            return MetaResponse.empty();
        }
        List<LsDataMeta> timeseries = new ArrayList<>();
        List<LsDataMeta> technical = new ArrayList<>();
        List<LsDataMeta> readOnly = new ArrayList<>();
        List<LsDataMeta> imported = new ArrayList<>();
        for (LsDataMeta meta : metas) {
            String key = meta.getMetaKey();
            if (VideoMetaService.isTechnicalKey(key)) {
                technical.add(meta);
            } else if (isReadOnlyKey(key)) {
                readOnly.add(meta);
            } else if (isImportedKey(key)) {
                imported.add(meta);
            } else {
                timeseries.add(meta);
            }
        }
        List<Long> metaSns = metas.stream().map(LsDataMeta::getMetaSn).toList();
        Map<Long, LsDataMetaReview> reviewByMetaSn = metaReviewRepository.findByDataMetaSnIn(metaSns).stream()
                .collect(Collectors.toMap(LsDataMetaReview::getDataMetaSn, r -> r, (a, b) -> a));
        return MetaResponse.of(timeseries, technical, readOnly, imported, reviewByMetaSn);
    }

    /**
     * <b>화면 전용 읽기 키</b> 판정 — {@code vlm.*} 네임스페이스 중 편집 화이트리스트 밖의 키.
     * 현재 해당하는 것은 {@code vlm.accuracy}(일치도)뿐이며, 그 값은 <b>과거 적재분에 한한다</b> —
     * 판정 창구를 연동하지 않게 되어 새로 생기지 않는다. 분류는 그대로 둔다(기존 행이 편집 대상으로
     * 승격되면 사람이 자동 산출값을 덮을 수 있다). [req: R12]
     *
     * <p>fail-closed 다 — "편집 허용한 것만 통과"라 향후 {@code vlm.*} 읽기 전용 키가 늘어도 편집 목록·저장
     * 경로로 새지 않는다. 판정 범위를 {@code vlm.} <b>접두 안으로 한정</b>하므로 레거시 구간 키({@code 0-8})와
     * {@code manual-timeseries} 는 영향을 받지 않고 계속 편집 가능하다.
     *
     * <p>{@code video.*} 는 이 술어의 대상이 아니다 — 그 판정의 소유자는 {@link VideoMetaService} 이며
     * 여기서 재해석하지 않는다.
     */
    private static boolean isReadOnlyKey(String metaKey) {
        return metaKey != null
                && metaKey.startsWith(VLM_KEY_PREFIX)
                && !EDITABLE_VLM_KEYS.contains(metaKey);
    }

    /**
     * <b>이관 원문 키</b> 판정 — 외부 산출물 이관이 저작도구 스키마에 착지할 컬럼이 없어
     * {@code LS_DATA_META} 에 원문 보관한 값의 열쇠({@link ImportMetaKeys#PREFIX} 접두).
     * 좌표·위치·카메라 설치 높이/방위/관리번호·데이터 출처·이벤트 기록·이벤트 상위 계층 이름·외부 영상
     * 식별자·원천 축 개인정보 판정이 여기 담긴다. [design: API-066]
     *
     * <p>이 값들은 <b>시계열 분석 결과가 아니다.</b> 분류의 여집합으로 떨어져 {@code items} 로 내려가면
     * ①화면이 시계열 메타로 표시해 검토 대상이 아닌 값이 검토 대상처럼 보이고 ②학습데이터 산출물의
     * 상황묘사 조달이 그 값을 서술로 집을 여지가 생긴다.
     *
     * <p><b>접두 문자열을 여기서 다시 선언하지 않는다</b> — 쓰는 쪽({@link ImportMetaKeys})이 소유한 상수를
     * 참조한다. 같은 문자열이 두 벌이 되면 한쪽만 바뀌었을 때 조용히 어긋난다({@code ImportMetaKeys} 가
     * 개인정보 3필드에서 이미 쓰는 규약과 같다).
     *
     * <p>이관으로 들어오지 않은 영상(대다수)에서는 이 술어에 걸리는 키가 하나도 없어 목록이 비지만,
     * 그 경우에도 {@code null} 이 아니라 <b>빈 배열</b>로 내려간다(화면이 분기 없이 그린다).
     */
    private static boolean isImportedKey(String metaKey) {
        return metaKey != null && metaKey.startsWith(ImportMetaKeys.PREFIX);
    }

    /**
     * 시계열 메타 저장(upsert) + 검수 완료 영상이면 {@code TASK_MODIFIED} 통지 발행.
     *
     * <h3>★ export 재생성은 <b>두 축이 모두</b> 참일 때만 건다 (@req R10, CWE-770)</h3>
     * <ol>
     *   <li><b>조달 참여 키</b>인가 — 단일 소유자는 {@link VlmDescriptionPolicy#participates} 다.
     *       키 규칙(전문 키 + 수동 전문 키 + 레거시 구간 키)을 여기에 복제하면 규칙이 늘 때 이쪽만
     *       조용히 뒤처진다(이 저장소의 반복 결함).</li>
     *   <li><b>값이 실제로 바뀌었는가</b> — 웹훅 경로({@code VlmResultService.applyResults} 의
     *       {@code changed} 가드)와 동형이다.</li>
     * </ol>
     *
     * <p><b>왜 키만으로는 부족한가</b>: 재생성은 {@code ControlNotifyDebouncer} 를 거쳐
     * {@code force=true} 로 위임되므로 {@code DatasetExportService} 의 콘텐츠 해시 멱등 skip
     * ({@code isUnchangedFromLastExport})을 <b>타지 않는다</b>. 즉 같은 값으로 저장을 반복하면
     * {@code v2·v3·v4…} 가 <b>이미지 2벌 전량 복사와 함께</b> 쌓이고(전 버전 보존 정책이라 삭제도
     * 안 된다) 관제 통지도 매번 나간다. FE 에 dirty 체크가 있지만 클라이언트가 임의 payload 를 보낼 수
     * 있으므로 서버가 직접 막는다(같은 이유로 {@link #rejectUneditableKeys} 도 FE 에 의존하지 않는다).
     *
     * <p>반대로 조달 키의 <b>실제 변경</b>을 {@code false} 로 두면 <b>저장은 됐는데 산출물이 안 바뀐다</b>.
     *
     * <p>값이 안 바뀐 저장 자체는 <b>정상 성공(200)</b> 이다 — 재생성·통지 플래그만 생략한다.
     */
    @Transactional("controlTransactionManager")
    public MetaResponse update(Long srcSn, MetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = verifyAccess(srcSn, actor);
        Long rawSn = src.getRawSn();
        rejectUneditableKeys(req);

        boolean regeneratesExport = false;
        boolean anyValueChanged = false;
        for (MetaUpdateRequest.Item item : req.items()) {
            boolean valueChanged = upsertItem(rawSn, item);
            if (valueChanged) {
                anyValueChanged = true;
                if (VlmDescriptionPolicy.participates(item.metaKey())) {
                    regeneratesExport = true;
                }
            }
        }
        log.info("[Meta] upserted rawSn={} count={} exportRegenerated={} anyValueChanged={}",
                rawSn, req.items().size(), regeneratesExport, anyValueChanged);
        // TASK_MODIFIED 통지는 검수 완료(APPROVED) 후 수정 시에만 발행한다(CLAUDE.md 작업 단위 통지 정책).
        // 검수 전 저장은 일반 작업이므로 통지 미발행 (라벨 경로와 동일 가드).
        // Phase 7a-2(PM 결정) — needsRecheck 를 exportRegenerated 와 분리한다(7a-1 의 "같은 축 공유"는 폐기).
        //   두 축은 다른 질문에 답한다: exportRegenerated="디스크 산출물을 다시 만들어야 하는가"(=조달 참여
        //   키가 실제로 바뀌었는가) / needsRecheck="사람이 검수자가 보지 않은 내용을 바꿨는가"(=편집 가능
        //   항목 중 하나라도 실제로 바뀌었는가, 조달 참여 여부와 무관). 묶어 두면 export 조달에 참여하지
        //   않는 메타 키(예: 레거시 구간 키·manual-timeseries)를 사람이 고쳐도 재검토가 요구되지 않는데,
        //   그 값은 데이터마트 뷰가 <b>라이브로</b> 읽으므로 재검토 없이 그대로 관제에 나간다 — 정책이
        //   막으려는 우회 그 자체다. exportRegenerated 조건(참여 키 + 값 변경)은 그대로 유지한다.
        if (approvalGate.isApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, srcSn, ChangeType.META_UPDATED, parseUserNo(actor.sub()),
                    regeneratesExport, anyValueChanged));
        }
        return toResponse(metaRepository.findByRawSn(rawSn));
    }

    /**
     * <b>편집 대상이 아닌 키</b>의 수정 요청을 거부한다(400) — 저장 경로 fail-closed 가드.
     * 대상은 ①{@code video.*} 기술메타 ②화면 전용 읽기 키({@link #isReadOnlyKey}, 예 {@code vlm.accuracy})
     * ③이관 원문 키({@link #isImportedKey}, {@code import.*}) 다.
     *
     * <p>②는 검수큐에 진입하지 않아 데이터마트·export 로 나가지 않지만, 편집을 허용하면 <b>외부가 산출한
     * 일치도가 사람의 산문으로 덮여</b> 작업자·검수자가 서술의 신뢰도를 판단할 근거가 사라진다. 또 미존재
     * {@code vlm.*} 키를 보내면 신규 검토행(PENDING)이 생겨 검수 큐와 데이터마트 뷰
     * {@code V_COMPLETED_META} 에 화면 전용 값이 흘러든다(R12 의 "검수큐 미진입" 계약 위반). [req: R12]
     *
     * <p>①은 ffprobe/관제 인입이 채우고 {@link VideoMetaService} 가 소유하는 값이라 사람이 산문으로
     * 고칠 대상이 아니다. 허용하면 ⓐ{@code video.fps} 가 자유 텍스트로 덮여 소비처
     * ({@code VideoFpsResolver}·{@code DatasetVideoMetaSnapshotService}) 파싱이 깨지고
     * ⓑ미존재 {@code video.*} 키를 보내면 신규 검토행(PENDING)이 생겨 검토 큐와
     * 데이터마트 뷰 {@code V_COMPLETED_META} 에 기술메타가 흘러든다.
     *
     * <p>③은 외부 산출물 이관이 저작도구 스키마에 착지할 컬럼이 없어 <b>원문 그대로 보관</b>한 값이라
     * (좌표·위치·카메라 설치 정보·데이터 출처·이벤트 기록 등) 사람이 산문으로 고칠 대상이 아니다. 허용하면
     * ⓐ외부가 준 <b>원문이 덮여</b> 사후 대조·재이관의 근거가 사라지고(되돌릴 수단이 없다)
     * ⓑ미존재 {@code import.*} 키를 보내면 신규 검토행(PENDING)이 생겨 검토 큐와 데이터마트 뷰
     * {@code V_COMPLETED_META} 에 이관 원문이 흘러든다. 이관 원문은 <b>검토 대상이 아니다</b>. [design: API-066]
     *
     * <p>조회에서 세 부류를 {@code items} 밖으로 빼는 것만으로도 화면발 요청은 사라지지만,
     * <b>그것에 의존하지 않고</b> 서버가 직접 막는다 — 클라이언트가 임의 payload 를 보낼 수 있기 때문
     * (CWE-20/915).
     *
     * <p>검증은 <b>첫 upsert 이전</b>에 전체 항목을 한 번에 훑는다 — 중간에 던지면 앞 항목만 저장된
     * 부분 반영이 남는다(같은 트랜잭션이라 롤백되긴 하나, 경계를 코드로 명확히 둔다).
     *
     * <p>메시지·로그 어디에도 <b>요청받은 키 문자열을 되돌려 담지 않는다</b> — {@code GlobalExceptionHandler}
     * 가 {@code e.getMessage()} 를 그대로 로깅하므로 개행이 섞인 키를 echo 하면 로그 위조가 된다
     * (CWE-117/209). 화면은 어떤 키가 걸렸는지 알 필요가 없다(FE 는 이 두 부류를 애초에 보내지 않는다).
     */
    private void rejectUneditableKeys(MetaUpdateRequest req) {
        List<String> keys = req.items().stream().map(MetaUpdateRequest.Item::metaKey).toList();

        long technical = keys.stream().filter(VideoMetaService::isTechnicalKey).count();
        if (technical > 0) {
            log.warn("[Meta] rejected technical meta update count={}", technical);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 기술 정보(영상 길이·해상도 등)는 수정할 수 없습니다.");
        }

        long readOnly = keys.stream().filter(MetaService::isReadOnlyKey).count();
        if (readOnly > 0) {
            log.warn("[Meta] rejected read-only meta update count={}", readOnly);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "자동 산출된 읽기 전용 항목(일치도 등)은 수정할 수 없습니다.");
        }

        long imported = keys.stream().filter(MetaService::isImportedKey).count();
        if (imported > 0) {
            log.warn("[Meta] rejected imported meta update count={}", imported);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "외부에서 이관된 원문 항목(좌표·설치 정보 등)은 수정할 수 없습니다.");
        }
    }

    /**
     * (rawSn, metaKey) upsert — 기존이면 값만 수정, 없으면 신규 등록 + 검토 큐(PENDING) 진입.
     *
     * <p>원자적 {@code ON CONFLICT} upsert({@link LsDataMetaRepository#upsertMeta})로 동일
     * (rawSn, metaKey) 동시 INSERT race(CWE-362)에도 UNIQUE 위반 크래시 없이 멱등하게 동작한다.
     * 신규 판정은 upsert 직전 조회로 하고, upsert 후 재조회로 신규 metaSn 을 얻어 검토행을 만든다.
     *
     * <p><b>옛 값은 그 직전 조회에서 함께 읽는다</b> — 재생성 판정({@code update} 의
     * {@code valueChanged})용 비교값이며, 신규 판정과 <b>같은 한 번의 조회</b>를 재사용하므로 쿼리가
     * 늘지 않는다. 값 문자열은 upsert <b>이전</b>에 뽑아 둔다({@code upsertMeta} 가
     * {@code clearAutomatically} 로 1차 캐시를 비우므로 이후 엔티티 접근에 의존하지 않는다).
     *
     * <p>{@code upsertMetaReturning}(삽입/갱신 원자 판정)을 쓰지 않는 이유: 그 반환값은 "삽입인가"만
     * 알려줄 뿐 <b>옛 값</b>을 주지 않아 어차피 선행 조회가 필요하고, 이 경로는 사람이 화면에서 저장하는
     * 단건 흐름이라 신규 판정 경합이 웹훅 경로만큼 첨예하지 않다(기존 구조 유지 — Surgical).
     *
     * @return 이 항목이 <b>실제로 값을 바꿨는가</b>(신규 등록 포함). 무변경 저장이면 {@code false}.
     */
    private boolean upsertItem(Long rawSn, MetaUpdateRequest.Item item) {
        Optional<LsDataMeta> before = metaRepository.findByRawSnAndMetaKey(rawSn, item.metaKey());
        String previousValue = before.map(LsDataMeta::getMetaVl).orElse(null);
        metaRepository.upsertMeta(rawSn, item.metaKey(), item.metaVal());
        if (before.isEmpty()) {
            LsDataMeta saved = metaRepository.findByRawSnAndMetaKey(rawSn, item.metaKey())
                    .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_ERROR,
                            "메타 저장에 실패했습니다."));
            ensureReviewRow(saved, rawSn);
            // 값이 없다가 생긴 것도 산출 내용 변경이다.
            return true;
        }
        return !Objects.equals(previousValue, item.metaVal());
    }

    /**
     * 신규 메타는 VLM 경로와 동일하게 PENDING 검토 큐(LS_DATA_META_REVIEW)에 진입시킨다.
     * 수동 입력 메타는 VLM 세그먼트가 아니므로 유형은 EXTERNAL 로 분류한다.
     * 이미 검토행이 있으면(경합/재저장) 재사용하여 단일 검토행 중복 생성을 막는다.
     */
    private void ensureReviewRow(LsDataMeta meta, Long rawSn) {
        if (metaReviewRepository.existsByDataMetaSn(meta.getMetaSn())) {
            return;
        }
        metaReviewRepository.save(LsDataMetaReview.createAuto(
                meta.getMetaSn(), rawSn, null,
                LsDataMetaReview.META_TYPE_EXTERNAL, null,
                LsDataMetaReview.STTS_PENDING));
    }

    /** REVIEWER 가 자동/외부 메타 검토 승인. */
    @Transactional("controlTransactionManager")
    public void approveReview(Long metaReviewSn, TokenClaims actor) {
        ensureReviewer(actor);
        LsDataMetaReview review = metaReviewRepository.findById(metaReviewSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "메타 검토를 찾을 수 없습니다: " + metaReviewSn));
        review.approve(actor.sub(), LocalDateTime.now());
        log.info("[Meta] review approved metaReviewSn={} actor={}", metaReviewSn, actor.sub());
    }

    /**
     * 영상 검수 승인({@code ReviewService.approve}) 시점에 해당 영상(rawSn)의 시계열 메타 검토행
     * (LS_DATA_META_REVIEW)을 <b>자동 확정(APPROVED)</b>한다 — 별도 메타 승인 단계 없이도 export/
     * 데이터마트 뷰 {@code V_COMPLETED_META}(RVW_STTS_CD='APPROVED'만 노출)에 시계열 메타가 누락되지
     * 않도록 하기 위함(버그 F 완성). event_annotation 자동 확정
     * ({@code EvntAnnoReviewService.autoApproveOnVideoApproval})과 동일한 전이 규칙을 미러링한다.
     *
     * <p>전이 규칙(검토행별):
     * <ul>
     *   <li>{@code AUTO_GENERATED} 또는 {@code PENDING} → {@code APPROVED} (자동 확정).</li>
     *   <li>{@code REJECTED}: REVIEWER 가 명시 반려한 메타는 자동 승인하지 않는다(반려 존중, 동결 제외).</li>
     *   <li>{@code APPROVED}: 이미 승인 — 멱등 skip(재전이 없음).</li>
     *   <li>검토행이 없는 영상: no-op(정상 승인).</li>
     * </ul>
     *
     * <p>{@link LsDataMetaReview#approve}는 내부 {@code ensureReviewable()}이 APPROVED/REJECTED 면
     * CONFLICT(409)를 던지므로, <b>반드시 상태 필터링을 먼저</b> 하고 AUTO_GENERATED/PENDING 에만 approve 를
     * 호출한다(예외를 삼키지 않음). 하나라도 전이하면 {@code flush()}로 영속성 컨텍스트 APPROVED 상태를
     * 즉시 반영해, 이어지는 {@code materialize}의 조회가 이를 관측하도록 flush 순서를 보장한다.
     *
     * <p>인가: 이 메서드는 {@code ReviewService.approve}(이미 {@code requireReviewer} 로 REVIEWER 게이트
     * 통과) 에서만 호출되므로 별도 role 검증 없이 {@code reviewer.sub()} 만 전이 rvwId 로 사용한다
     * (actor null 방어만 최소 수행). 로그는 rawSn/건수만 남긴다(본문/PII 미노출, CWE-359/117).
     *
     * @param rawSn    검수 승인된 영상 PK
     * @param reviewer 승인한 REVIEWER(전이 rvwId/로그 식별자)
     */
    @Transactional("controlTransactionManager")
    public void autoApproveOnVideoApproval(Long rawSn, TokenClaims reviewer) {
        if (reviewer == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        List<LsDataMetaReview> reviews = metaReviewRepository.findAllByDataRawSn(rawSn);
        if (reviews.isEmpty()) {
            // 시계열 메타 검토행 없는 영상 — 정상 no-op(확정 대상 없음).
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int approved = 0;
        for (LsDataMetaReview review : reviews) {
            String status = review.getRvwSttsCd();
            if (LsDataMetaReview.STTS_APPROVED.equals(status)) {
                // 이미 승인 — 멱등 skip(재전이 없이 이어지는 materialize 가 동결).
                continue;
            }
            if (LsDataMetaReview.STTS_REJECTED.equals(status)) {
                // 명시 반려 존중 — 자동 승인 제외(동결 안 됨).
                log.info("[Meta] auto-approve skipped — rejected meta rawSn={} metaReviewSn={}",
                        rawSn, review.getDataMetaReviewSn());
                continue;
            }
            // AUTO_GENERATED / PENDING → APPROVED.
            review.approve(reviewer.sub(), now);
            approved++;
        }
        if (approved > 0) {
            // flush 로 materialize 조회 전 APPROVED 상태 반영 보장.
            metaReviewRepository.flush();
            log.info("[Meta] auto-approved timeseries meta on video approval rawSn={} count={}",
                    rawSn, approved);
        }
    }

    /** REVIEWER 가 자동/외부 메타 검토 반려. 사유 필수. */
    @Transactional("controlTransactionManager")
    public void rejectReview(Long metaReviewSn, String reason, TokenClaims actor) {
        ensureReviewer(actor);
        LsDataMetaReview review = metaReviewRepository.findById(metaReviewSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "메타 검토를 찾을 수 없습니다: " + metaReviewSn));
        review.reject(reason, actor.sub(), LocalDateTime.now());
        log.info("[Meta] review rejected metaReviewSn={} actor={}", metaReviewSn, actor.sub());
    }


    private LsDataSrc verifyAccess(Long srcSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        LsDataSrc src = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        // [design: ADR-055] 계층 반영 — 관리자는 검수자에게 열린 이 자리를 그대로 통과한다.
        //   동등 비교로 두면 관리자가 두 분기 어디에도 안 걸려 메타 접근이 통째로 403 이 된다.
        if (actor.hasRole(Role.REVIEWER)) {
            return src;
        }
        if (actor.hasRole(Role.WORKER)) {
            Long selfNo = parseUserNo(actor.sub());
            boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                    selfNo, LsTaskAssignment.TASK_LABELER, src.getRawSn());
            if (!assigned) {
                throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
            }
            return src;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "메타 접근 권한이 없습니다.");
    }

    private void ensureReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // [design: ADR-055] 창구의 「검수자 전용」은 「검수자 이상」으로 읽는다 — 관리자는 물려받는다.
        if (!actor.hasRole(Role.REVIEWER)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
