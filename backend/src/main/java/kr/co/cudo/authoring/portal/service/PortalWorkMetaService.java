package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.portal.dto.PortalMetaResponse;
import kr.co.cudo.authoring.portal.dto.PortalMetaScope;
import kr.co.cudo.authoring.portal.dto.PortalMetaSource;
import kr.co.cudo.authoring.portal.dto.PortalMetaUpdateRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUserMeta;
import kr.co.cudo.authoring.portal.repository.LsPortalUserMetaRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 포털 작업 화면의 <b>메타 Load·저장</b> — 촬영환경·프레임 설명·개인정보 판정·시계열 메타.
 *
 * <h3>★ 다섯 축 가운데 셋은 키/값이 아니다</h3>
 * <p>시계열 메타만 메타 원장({@code LS_DATA_META})의 키/값이고, <b>촬영환경 · 프레임 설명 ·
 * 개인정보 판정</b> 셋은 원장의 <b>컬럼</b>({@code LS_DATA_RAW}·{@code LS_DATA_SRC})이다. 그래서
 * 조회 시점에 키/값으로 바꿔 내리고 저장 시점에 되돌린다 — 그 변환 규칙의 소유자는
 * {@link PortalColumnMetaField} 한 곳이며 읽기·쓰기가 같은 규칙을 쓴다.
 * <p>⚠ 키/값만 읽는 구현은 컴파일도 되고 시험도 대부분 통과한다. 그러면 <b>셋이 화면에 아예 나오지
 * 않는다</b> — 값이 비는 것이 아니라 항목 자체가 없다.
 *
 * <h3>★ 시계열 메타를 미제공으로 다루지 않는다</h3>
 * <p>포털 미제공의 축은 <b>외부 시계열 분석 서버로 나가는 위탁 연동</b>(호출·콜백)이지, 그 결과물을
 * 화면에 표시하고 사람이 고치는 것이 아니다. 과거에 그것을 AI 보조(분할·추적·오토라벨)와 한 묶음에
 * 넣은 <b>범주 오류</b>가 있었고 이미 바로잡혔다. 되돌리지 말 것.
 *
 * <h3>★★ 저장처는 자산 출처가 가른다 (이 기능에서 가장 틀리기 쉬운 지점)</h3>
 * <p>판정은 {@link PortalWorkTargetResolver} 한 곳이 한다. 화면은 어느 출처든 <b>같은 저장 요청</b>을
 * 보내고 어느 쪽인지 알지 못한다.
 * <ul>
 *   <li><b>데이터마트 자산</b> — 원본(원장 키/값 + 원장 컬럼)과 본인 오버레이
 *       ({@code LS_PORTAL_USER_META})를 병합해 내려주고, 저장은 <b>오버레이에만</b> 한다.
 *       ★원본과 동결 스냅샷을 수정하지 않는다 — 단방향이라 데이터마트로 되돌아가지 않고
 *       관제 통지·산출물 재생성을 일으키지 않는다.</li>
 *   <li><b>본인 업로드 자산</b> — 오버레이를 거치지 않고 <b>그 자산의 원장에 그대로</b> 읽고 쓴다.
 *       가려야 할 남의 원본이 없어 병합할 것이 없고, 그래서 원본을 가렸다는 표시도 서지 않는다.
 *       컬럼 축 셋은 그 자산의 원장 <b>컬럼</b>에 앉으며, 그 쓰기도 <b>소유자와 출처 판별자</b>를
 *       문장 자체에 걸어 관제 영상에 구조적으로 닿지 않는다(흡수 이후 표 자체는 울타리가 아니다).</li>
 * </ul>
 *
 * <h3>★★★ 원본을 수정하지 않기 위해 기존 저장 창구를 재사용하지 않는다</h3>
 * <p>같은 컬럼을 고치는 내부 창구는 저장하면서 원장 컬럼을 직접 고치고, 재검토 표시를 세우고,
 * 관제 통지를 발행하고, 승인 동결본을 다시 굳힌다. 그것을 그대로 부르면 이 창구의 최상위 불변 둘을
 * <b>한 번에</b> 위반한다. 이 위반은 「중복 구현을 피하자」는 가장 자연스러운 판단에서 나오므로
 * 금지로 못박는다.
 * <table>
 *   <caption>재사용 경계</caption>
 *   <tr><th>재사용한다</th><td>읽기 경로 · 값 유효성 규칙(허용 코드값·최대 길이) · 자동 계산 규칙</td></tr>
 *   <tr><th>재사용하지 않는다</th><td>저장 동작(원장 컬럼 쓰기 · 재검토 표시 · 관제 통지 · 동결본 재동결)</td></tr>
 * </table>
 * <p>이 서비스는 <b>어떤 이벤트도 발행하지 않는다</b>. 이벤트 발행자를 여기에 주입하지 말 것.
 *
 * <h3>★ 자동 계산값이 사람의 판정으로 승격되지 않는다</h3>
 * <p>컬럼 축 셋의 유효값은 <b>수동 저장값이 있으면 그 값, 없으면 자동으로 계산한 값</b>이다. 화면이
 * 그 자동 계산값을 그대로 되돌려 보내면 오버레이 행이 생겨 「사용자가 고른 값」으로 굳는데, 서버는
 * 받은 값만으로는 둘을 구분하지 못한다. 그래서 방어가 두 겹이고 그중 <b>서버 방어는 단독으로
 * 성립</b>한다 — 받은 값이 원본의 현재 유효값과 같으면 아무것도 쌓지 않고, 이미 쌓여 있으면 지운다.
 * <p>이 방어가 여기서 성립하는 이유는 <b>오버레이가 별도 저장소라 「행이 없다」가 곧 「원본 값을
 * 그대로 쓴다」</b>이기 때문이다. 원장에 직접 쓰는 창구는 「행이 없다」가 「수동값을 지운다」는 다른
 * 뜻이라 같은 방어를 쓸 수 없다(그쪽이 이것을 알려진 한계로 안고 있다 — 포털은 그 한계가 없다).
 * <p>인지·수용하는 대가: 사용자가 자동 계산값과 <b>우연히 같은 값</b>을 일부러 골라도 덮었다는 표시가
 * 서지 않는다. 보이는 값은 같으므로 무해하다.
 *
 * <h4>★★ 이 방어를 <b>어디에</b> 거는가 — 두 축을 함께 좁힌다</h4>
 * <p>적용 범위가 근거와 갈리면 다음 사람이 같은 실수를 한다. 그래서 근거를 함께 적는다.
 * <ul>
 *   <li><b>컬럼 축에만</b> 건다 — 키/값 축에는 자동 계산 프리필이 없어 승격이 성립하지 않고, 거기까지
 *       넓히면 「원본과 같은 값을 일부러 저장했다」가 사라져 기존 계약이 바뀐다.</li>
 *   <li><b>데이터마트 자산에만</b> 건다 — 근거는 위의 <b>오버레이의 「행 없음 = 원본을 그대로 쓴다」</b>
 *       성질이다. 본인 업로드 자산의 저장 대상은 오버레이가 아니라 <b>원장 그 자체</b>라 그 성질이
 *       성립하지 않는다(「행 없음」은 「수동값이 없다」는 다른 뜻이다). 그래서 업로드 자산에는
 *       <b>항상 원장에 쓴다</b> — 받은 값이 자동 계산값과 같아도 그렇다.
 *       <p>⚠ 넓히면 <b>원장 컬럼이 비어 있는 자산에서 사용자가 자동 계산값과 같은 값을 일부러 골랐을 때
 *       아무 쓰기도 일어나지 않는다</b>. 화면과 산출물이 같은 파생을 다시 적용해 값이 우연히 일치하므로
 *       <b>오차가 눈에 드러나지 않는다</b> — 드러나는 것은 ①사용자의 명시 판정이 기록되지 않고
 *       ②원장 컬럼을 직접 읽는 이후 경로(내려받기·산출 조립)가 빈 값을 보며 ③파생 기본값이 바뀌는 날
 *       그 자산의 값이 조용히 따라 바뀐다는 것이다.</li>
 * </ul>
 *
 * <h3>비식별 누락 신고 게이트 (412)</h3>
 * <p>이 창구가 다루는 축에 <b>개인정보 판정</b>이 들어 있고, 같은 채널의 형제 창구(포털 프레임 라벨
 * 조회·저장)가 이미 그 구간을 막는다. <b>읽기·쓰기 양쪽</b>에 걸며 축별로 가르지 않는다 — 한 요청이
 * 다섯 축을 함께 받으므로 일부만 허용하면 우회가 생긴다. 판정은 복제하지 않고
 * {@link LabelAccessGuard#requireNotUnderDeidentReport} 단일 원천을 그대로 부른다.
 * <p>★<b>본인 업로드 자산은 이 게이트의 대상이 아니다</b> — 내부 파이프라인의 비식별 라이프사이클이
 * 없어 그 구간 자체가 존재하지 않는다.
 * <p>⚠ 구 주석은 이 게이트를 걸지 않는 근거를 적고 있었고 그 판단은 <b>폐기</b>됐다. 되살리지 말 것.
 *
 * @design API-234
 * @design API-235
 * @design ERD-018
 * @design UC-024
 * @design AC-1068
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalWorkMetaService {

    private final PortalWorkTargetResolver targetResolver;
    private final LsDataMetaRepository metaRepository;
    private final LsPortalUserMetaRepository overlayRepository;
    private final PortalUploadAssetRepository assetRepository;
    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;

    // ======================== Load ========================

    /** 프레임 메타 Load — 자산 출처에 따라 병합하거나 원장 그대로 내려준다. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public PortalMetaResponse load(Long srcSn, String portalUserNo) {
        PortalWorkTargetResolver.Target target = targetResolver.resolveByFrame(srcSn, portalUserNo);
        requireNotUnderDeidentReport(target);
        return read(target, portalUserNo);
    }

    // ======================== 저장 ========================

    /**
     * 프레임 메타 저장. 검증 전량을 <b>첫 쓰기 이전</b>에 끝낸다 — 중간에 던지면 앞 항목만 반영된
     * 부분 저장이 남는다(같은 트랜잭션이라 롤백되긴 하나 경계를 코드로 명확히 둔다).
     *
     * <p>원본 유효값 스냅샷도 <b>첫 쓰기 이전</b>에 한 번만 뜬다. 항목마다 다시 읽으면 앞 항목의 쓰기가
     * 뒤 항목의 비교 기준을 바꿔, 같은 요청 안에서 승격 방어의 기준이 흔들린다.
     *
     * @return 저장 후 값(다시 병합해 읽은 결과)
     */
    @Transactional("controlTransactionManager")
    public PortalMetaResponse save(Long srcSn, String portalUserNo, PortalMetaUpdateRequest request) {
        PortalWorkTargetResolver.Target target = targetResolver.resolveByFrame(srcSn, portalUserNo);
        requireNotUnderDeidentReport(target);

        LsDataRaw raw = findRaw(target.rawSn());
        LsDataSrc frame = findFrame(target.srcSn());

        // ---- 검증 전량 (첫 쓰기 이전) ----
        List<Planned> plan = plan(request.items(), target);
        // ---- 원본 유효값 스냅샷 (첫 쓰기 이전, 한 번만) ----
        Map<PortalColumnMetaField, String> original = snapshotOriginal(raw, frame);

        boolean nativeWritten = false;
        for (Planned p : plan) {
            if (!target.isUpload() && p.column() != null
                    && Objects.equals(p.value(), original.get(p.column()))) {
                // ★승격 방어 — 원본의 현재 유효값과 같으면 아무것도 쌓지 않고, 쌓여 있으면 지운다.
                //   ★★업로드 자산은 이 방어의 대상이 아니다 — 저장처가 오버레이가 아니라 원장이라
                //   「행 없음 = 원본 그대로」가 성립하지 않는다(클래스 주석의 「어디에 거는가」 참조).
                deleteOverlay(target, portalUserNo, p);
                continue;
            }
            if (target.isUpload()) {
                nativeWritten = true;
                writeToOwnedLedger(target, portalUserNo, p);
            } else if (p.scope() == PortalMetaScope.VIDEO) {
                // ★영상 축은 프레임 참조를 비워 적재한다 — 채우면 없는 프레임을 가리킨다.
                overlayRepository.upsertVideoScoped(portalUserNo, target.rawSn(),
                        p.metaKey(), p.value());
            } else {
                overlayRepository.upsertFrameScoped(portalUserNo, target.rawSn(), target.srcSn(),
                        p.metaKey(), p.value());
            }
        }
        if (nativeWritten) {
            // 손으로 쓴 UPDATE 는 1차 캐시를 갱신하지 않는다 — 비우지 않으면 바로 아래 재조회가
            //   <옛 값>을 응답으로 내보낸다(오류가 없어 어떤 시험에도 걸리지 않는다).
            assetRepository.clearPersistenceContext();
        }
        log.info("[Portal] work meta saved rawSn={} srcSn={} origin={} count={}",
                target.rawSn(), target.srcSn(), target.origin(), plan.size());
        return read(target, portalUserNo);
    }

    // ======================== 내부 — 게이트 ========================

    /**
     * 비식별 누락 신고 구간이면 412. <b>데이터마트 자산에만</b> 건다.
     *
     * <p>본인 업로드 자산은 내부 파이프라인의 비식별 라이프사이클이 없어 그 구간이 존재하지 않는다 —
     * 게이트를 씌우면 정상 자산이 막히고, 반대로 데이터마트 자산에서 빼면 유출이 열린다.
     */
    private void requireNotUnderDeidentReport(PortalWorkTargetResolver.Target target) {
        if (!target.isUpload()) {
            accessGuard.requireNotUnderDeidentReport(target.rawSn());
        }
    }

    // ======================== 내부 — 읽기 ========================

    /**
     * 자산 출처별 읽기.
     *
     * <p>업로드 자산은 <b>오버레이 저장소를 아예 건드리지 않는다</b> — 조회 필터로 거르는 것이 아니라
     * 경로 자체가 갈린다. 반면 <b>컬럼 축은 두 출처가 같다</b> — 어느 쪽이든 그 자산의 원장 행을 읽는다.
     */
    private PortalMetaResponse read(PortalWorkTargetResolver.Target target, String portalUserNo) {
        LsDataRaw raw = findRaw(target.rawSn());
        LsDataSrc frame = findFrame(target.srcSn());

        Map<Axis, Origin> origin = new LinkedHashMap<>();

        // ① 원장 키/값 — (영상, 키) 단위라 전부 영상 축이고 출처 구분이 없는 저장값이다.
        for (LsDataMeta meta : metaRepository.findByRawSn(target.rawSn())) {
            origin.put(new Axis(PortalMetaScope.VIDEO, meta.getMetaKey()),
                    new Origin(meta.getMetaVl(), PortalMetaSource.STORED));
        }
        // ② 원장 컬럼 — 유효값(수동값 우선, 없으면 자동 계산)과 그 출처. 키/값과 같은 키가 있어도
        //    이쪽이 이긴다(그 키의 소유자가 컬럼 축이다).
        for (PortalColumnMetaField field : PortalColumnMetaField.values()) {
            if (field.scope() == PortalMetaScope.FRAME && frame == null) {
                continue;
            }
            PortalColumnMetaField.Effective effective = field.effective(raw, frame);
            origin.put(new Axis(field.scope(), field.metaKey()),
                    new Origin(effective.value(), effective.source()));
        }

        Map<Axis, PortalMetaResponse.Item> merged = new LinkedHashMap<>();
        origin.forEach((axis, base) -> merged.put(axis, new PortalMetaResponse.Item(
                axis.metaKey(), base.value(), axis.scope(), false, base.source())));

        if (!target.isUpload()) {
            for (LsPortalUserMeta mine : overlayRepository
                    .findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(portalUserNo, target.rawSn())) {
                putOverlay(merged, origin, PortalMetaScope.VIDEO, mine);
            }
            if (target.srcSn() != null) {
                for (LsPortalUserMeta mine : overlayRepository
                        .findByPortalUserNoAndSrcDataSrcSn(portalUserNo, target.srcSn())) {
                    putOverlay(merged, origin, PortalMetaScope.FRAME, mine);
                }
            }
        }

        List<PortalMetaResponse.Item> editable = new ArrayList<>();
        List<PortalMetaResponse.Item> readOnly = new ArrayList<>();
        List<PortalMetaResponse.Item> technical = new ArrayList<>();
        for (PortalMetaResponse.Item item : merged.values()) {
            switch (PortalMetaKeyPolicy.bucketOf(item.metaKey())) {
                case TECHNICAL -> technical.add(item);
                case READ_ONLY -> readOnly.add(item);
                case EDITABLE -> editable.add(item);
            }
        }
        // 조회 순서가 실행마다 흔들리면 화면 순서가 흔들린다 — 키로 고정한다.
        Comparator<PortalMetaResponse.Item> byKey = Comparator.comparing(PortalMetaResponse.Item::metaKey);
        editable.sort(byKey);
        readOnly.sort(byKey);
        technical.sort(byKey);
        return new PortalMetaResponse(target.rawSn(), target.srcSn(), editable, readOnly, technical);
    }

    /**
     * 오버레이 한 칸을 병합 결과에 얹는다.
     *
     * <p>가림 표시는 <b>원본 쪽에 실제 값이 있었을 때만</b> 선다 — 값 자체가 없던 자리에 새로 채운 것은
     * 가린 것이 아니다. 출처는 <b>덮인 쪽</b>의 사실이므로 오버레이가 얹혀도 그대로 유지한다.
     */
    private void putOverlay(Map<Axis, PortalMetaResponse.Item> merged, Map<Axis, Origin> origin,
                            PortalMetaScope scope, LsPortalUserMeta mine) {
        Axis axis = new Axis(scope, mine.getMetaKey());
        Origin base = origin.get(axis);
        boolean covered = base != null && base.value() != null;
        PortalMetaSource source = base == null ? PortalMetaSource.NONE : base.source();
        merged.put(axis, new PortalMetaResponse.Item(
                mine.getMetaKey(), mine.getMetaVl(), scope, covered, source));
    }

    // ======================== 내부 — 검증·계획 ========================

    /**
     * 저장 요청을 <b>전량 검증</b>해 실행 계획으로 바꾼다. 한 건이라도 걸리면 아무것도 저장되지 않는다.
     *
     * <p>거부 메시지에 <b>요청받은 키·값을 되돌려 싣지 않는다</b> — 전역 예외 처리기가 메시지를 그대로
     * 로깅하므로 개행이 섞인 입력을 echo 하면 로그 위조가 된다(CWE-117/209).
     */
    private List<Planned> plan(List<PortalMetaUpdateRequest.Item> items,
                               PortalWorkTargetResolver.Target target) {
        List<Planned> plan = new ArrayList<>(items.size());
        for (PortalMetaUpdateRequest.Item item : items) {
            if (!PortalMetaKeyPolicy.isEditable(item.metaKey())) {
                log.warn("[Portal] rejected uneditable meta save");
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "표시 전용 항목(영상 기술 정보·자동 산출값·처리 상태 등)은 수정할 수 없습니다.");
            }
            Optional<PortalColumnMetaField> column =
                    PortalColumnMetaField.of(item.scope(), item.metaKey());
            if (column.isEmpty() && PortalColumnMetaField.isColumnKey(item.metaKey())) {
                // ★축이 어긋난 컬럼 축 키 — 조용히 한쪽으로 접으면 사용자가 저장했다고 믿는 값이
                //   <다시는 읽히지 않는 자리>에 들어간다.
                log.warn("[Portal] rejected column meta with mismatched scope");
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "이 항목은 요청한 축(영상/프레임)에 저장할 수 없습니다.");
            }
            String value = normalize(item.metaVl());
            if (column.isPresent()) {
                column.get().validate(value);
            } else if (target.isUpload() && item.scope() == PortalMetaScope.FRAME) {
                rejectFrameScopeKeyValueOnUpload();
            }
            plan.add(new Planned(item.scope(), item.metaKey(), value, column.orElse(null)));
        }
        return plan;
    }

    /**
     * 본인 업로드 자산에는 <b>프레임 축 키/값 저장이 없다</b> — 그 자산의 메타 원장이 (영상, 키)
     * 단위라 프레임 축 값을 담을 자리가 없다. 조용히 영상 축으로 접거나 버리면 사용자가 저장했다고
     * 믿는 값이 사라지므로 fail-closed 로 거부한다.
     *
     * <p>⚠ <b>컬럼 축은 여기 해당하지 않는다</b> — 프레임 설명·프레임 개인정보 판정은 프레임 원장의
     * 컬럼에 자리가 있으므로 업로드 자산에서도 정상 저장된다. 이 가드를 프레임 축 전체로 넓히면
     * 그 셋이 통째로 막힌다.
     */
    private void rejectFrameScopeKeyValueOnUpload() {
        log.warn("[Portal] rejected frame-scoped key/value meta on upload asset");
        throw new CustomException(ErrorCode.INVALID_INPUT,
                "업로드한 자산에는 프레임 단위 메타를 저장할 수 없습니다.");
    }

    /** 원본 컬럼 축의 유효값 스냅샷 — 승격 방어의 비교 기준. 첫 쓰기 이전에 한 번만 뜬다. */
    private Map<PortalColumnMetaField, String> snapshotOriginal(LsDataRaw raw, LsDataSrc frame) {
        Map<PortalColumnMetaField, String> snapshot = new EnumMap<>(PortalColumnMetaField.class);
        for (PortalColumnMetaField field : PortalColumnMetaField.values()) {
            snapshot.put(field, field.effective(raw, frame).value());
        }
        return snapshot;
    }

    // ======================== 내부 — 쓰기 ========================

    /**
     * 본인 업로드 자산의 원장에 그대로 쓴다. 컬럼 축은 원장 <b>컬럼</b>, 나머지는 메타 원장이며
     * 어느 쪽이든 <b>소유자와 출처 판별자를 문장 자체에</b> 건다.
     *
     * <p>★ 반영 행 수를 <b>버리지 않는다</b> — 0 은 그 판별자가 막았다는 뜻이라 저장이 아니다.
     * 판정은 {@link PortalOwnedLedgerGuard} 한 곳이 소유한다(거부를 고른 근거도 그쪽에 있다).
     */
    private void writeToOwnedLedger(PortalWorkTargetResolver.Target target, String portalUserNo,
                                    Planned p) {
        PortalColumnMetaField column = p.column();
        if (column == null) {
            PortalOwnedLedgerGuard.requireApplied(
                    assetRepository.upsertOwnedMeta(target.rawSn(), portalUserNo, p.metaKey(), p.value()),
                    "meta", target.rawSn());
            return;
        }
        switch (column.target()) {
            case VIDEO_ROW -> PortalOwnedLedgerGuard.requireApplied(
                    assetRepository.updateOwnedVideoColumn(
                            target.rawSn(), portalUserNo, column.name(), p.value()),
                    "videoColumn", target.rawSn());
            case FRAME_ROW -> PortalOwnedLedgerGuard.requireApplied(
                    assetRepository.updateOwnedFrameColumn(
                            target.srcSn(), portalUserNo, column.name(), p.value()),
                    "frameColumn", target.srcSn());
        }
    }

    /** 오버레이를 지워 원본으로 되돌린다 — 축에 맞는 문장을 골라야 다른 축의 행이 남는다. */
    private void deleteOverlay(PortalWorkTargetResolver.Target target, String portalUserNo, Planned p) {
        if (p.scope() == PortalMetaScope.VIDEO) {
            overlayRepository.deleteVideoScoped(portalUserNo, target.rawSn(), p.metaKey());
        } else {
            overlayRepository.deleteFrameScoped(portalUserNo, target.rawSn(), target.srcSn(),
                    p.metaKey());
        }
    }

    // ======================== 내부 — 조회 보조 ========================

    private LsDataRaw findRaw(Long rawSn) {
        return videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없습니다."));
    }

    private LsDataSrc findFrame(Long srcSn) {
        return srcSn == null ? null : srcRepository.findById(srcSn).orElse(null);
    }

    /** 빈 값은 <b>지움</b>이다 — 빈 문자열과 없음을 갈라 두면 비교·저장이 축마다 갈린다. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 병합 키 — 같은 키라도 축이 다르면 다른 값이다. */
    private record Axis(PortalMetaScope scope, String metaKey) {
    }

    /** 원본 쪽 사실 — 값과 그 출처. 본인 오버레이는 여기 반영되지 않는다. */
    private record Origin(String value, PortalMetaSource source) {
    }

    /** 검증을 통과한 저장 한 건. {@code column} 이 {@code null} 이면 키/값 축이다. */
    private record Planned(PortalMetaScope scope, String metaKey, String value,
                           PortalColumnMetaField column) {
    }
}
