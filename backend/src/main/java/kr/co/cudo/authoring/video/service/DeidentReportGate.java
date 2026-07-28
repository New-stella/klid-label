package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * S7 — <b>비식별 누락 신고 구간 판정</b>(actor 무관, 데이터 상태 전용) 단일 원천.
 *
 * <h3>왜 별도 컴포넌트인가</h3>
 * 신고 구간({@code LS_DATA_RAW.DE_IDNTF_YN='F'}) 차단은 두 성격의 호출부가 쓴다.
 * <ul>
 *   <li><b>인가 경로</b>: {@code LabelAccessGuard.requireNotUnderDeidentReport} — 사용자 요청 컨텍스트라
 *       {@code TokenClaims} 인가를 먼저 통과시킨 뒤 4xx({@code PRECONDITION_FAILED})로 끝낸다.</li>
 *   <li><b>산출(export) 경로</b>: {@code DatasetExportService.export} — {@code @Async} 배치 컨텍스트라
 *       actor 자체가 없다. 인가 게이트가 아니라 <b>데이터 상태 게이트</b>다.</li>
 * </ul>
 * 두 곳이 각자 {@code "F".equals(...)} 를 재구현하면 정책이 갈라진다(Phase 6 에서 실제로 export 경로만
 * 누락돼 신고된 영상의 프레임 이미지가 새 버전 폴더로 산출됐다 — CWE-359). 그래서 <b>상태 판정만</b>
 * 여기로 분리하고, 예외/응답 규약은 각 호출부가 자기 컨텍스트에 맞게 결정한다.
 *
 * <h3>판정 범위 = 자기 자신 + 조상(ORGNL_RAW_SN) 체인</h3>
 * 파생영상(해상도·증강)의 프레임은 부모의 <b>비식별 프레임을 복사·리스케일</b>한 것이라
 * ({@code ResolutionSnapshotService.buildFrameSpecs} → {@code deidFrameSourceStrict}), 부모의 마스킹이
 * 실패한 그 픽셀이 파생본에도 그대로 있다. 그런데 신고({@code DeidentReportService.report})는 신고된
 * <b>부모 행만</b> {@code 'F'} 로 바꾸고 파생 행은 {@code 'Y'} 로 남는다. 자기 행만 보는 판정은 파생
 * 프레임에서 fail-open 이 되어 실제 PII 이미지가 200 으로 나갔다(적대검증 반증). 따라서 판정은
 * {@code ORGNL_RAW_SN} 체인을 따라 올라가며 <b>하나라도</b> {@code 'F'} 면 신고 구간으로 본다.
 *
 * <p>노드당 조회는 2컬럼 projection 1회(PK 인덱스 lookup)다 — 원본 영상은 1회, 현 데이터 모델의
 * 1단계 파생영상은 2회로 끝난다. 체인은 {@link #MAX_ANCESTOR_DEPTH} 상한과 방문 집합으로 보호되어
 * 자기참조·사이클 오염 데이터에서도 무한 루프에 빠지지 않는다. 호출부는 루프 밖에서 1회만 호출한다.
 *
 * <h3>★ 전역 불변식 — {@code LS_DATA_RAW} 잠금 순서는 <b>조상 → 자손</b></h3>
 * {@code LS_DATA_RAW} 행을 {@code SELECT … FOR UPDATE}({@code VideoRepository.findByRawSnForUpdate})로
 * 잠그는 <b>모든 경로</b>는, 한 트랜잭션에서 둘 이상의 행을 잠글 때 반드시 <b>조상(원본) 먼저,
 * 자손(파생) 나중</b> 순서를 지킨다. 반대 순서로 잠그는 경로가 하나라도 생기면 같은 (부모, 파생) 쌍에
 * 대해 순환 대기(교착, CWE-833)가 성립한다.
 * <ul>
 *   <li>{@code ResolutionPersistService.persist} — parent → newRaw (해당 클래스에 명시된 관례)</li>
 *   <li>{@code ResolutionSnapshotService} · {@code ResolutionReservationPersister} ·
 *       {@code AugmentResultService} · {@code DeidentReportService} — 부모(또는 자기) 단일 행만 잠금</li>
 *   <li>{@link #isUnderDeidentReportLocked} — 체인을 <b>무잠금으로 먼저 조회</b>한 뒤 조상부터 잠금</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeidentReportGate {

    /** {@code LS_DATA_RAW.DE_IDNTF_YN} — 비식별 실패/누락 신고 상태(재비식별 대기). */
    public static final String DEIDENT_FAILED = "F";

    /**
     * 조상 체인 순회 상한 — 현 데이터 모델의 파생 깊이는 1단계(파생의 파생 없음)라 넉넉한 값이다.
     * 이 깊이를 넘도록 조상이 이어지면 데이터 오염으로 보고 <b>fail-closed</b>(차단)한다.
     */
    public static final int MAX_ANCESTOR_DEPTH = 8;

    private final VideoRepository videoRepository;

    /** 체인 순회 1노드 — 신고 여부 판정값과 다음(부모) 링크. */
    private record ChainNode(String deIdntfYn, Long orgnlRawSn) {
    }

    /**
     * 이 영상(또는 <b>그 조상 원본</b>)이 <b>비식별 누락 신고 구간</b>(재비식별 대기)인가.
     *
     * <p>{@code 'Y'}(정상)·{@code 'N'}(미수행)·NULL·행 부재는 모두 {@code false}(통과) — 일반 영상 흐름에
     * 영향이 없다. {@code rawSn} 이 null 이면 판정 대상이 아니므로 {@code false}.
     *
     * <p>조회 자체가 실패(DB 오류)하면 예외를 그대로 전파한다. 호출부가 "판정 불가 = 진행 금지"
     * (fail-closed)로 다루게 하기 위해서다 — 여기서 삼켜 {@code false}(통과)로 만들지 않는다.
     */
    public boolean isUnderDeidentReport(Long rawSn) {
        return isChainUnderReport(rawSn, sn -> videoRepository.findDeidentChainNodeByRawSn(sn)
                .map(n -> new ChainNode(n.getDeIdntfYn(), n.getOrgnlRawSn()))
                .orElse(null));
    }

    /**
     * 체인 순회 본체 — 자기 자신부터 {@code ORGNL_RAW_SN} 조상까지 올라가며 {@code 'F'} 를 찾는다.
     *
     * <p>종료 조건: ①{@code 'F'} 발견(=차단) ②조상 없음/행 부재(=통과) ③이미 방문한 노드 재방문
     * (사이클 — 체인의 모든 노드를 이미 판정했으므로 통과) ④깊이 상한 초과(=데이터 오염, fail-closed 차단).
     *
     * @param loader 노드 로더 (무잠금 projection / 잠금 조회 두 구현을 공유하기 위한 주입점)
     */
    private boolean isChainUnderReport(Long rawSn, Function<Long, ChainNode> loader) {
        Long current = rawSn;
        Set<Long> visited = new HashSet<>();
        for (int depth = 0; depth <= MAX_ANCESTOR_DEPTH; depth++) {
            if (current == null || !visited.add(current)) {
                return false; // 조상 없음 / 사이클(모든 노드 판정 완료) → 신고 아님
            }
            ChainNode node = loader.apply(current);
            if (node == null) {
                return false; // 행 부재 — 존재하지 않는 영상은 신고 대상이 아니다(기존 규약)
            }
            if (DEIDENT_FAILED.equals(node.deIdntfYn())) {
                return true;
            }
            current = node.orgnlRawSn();
        }
        // 깊이 상한 초과 = 파생 깊이 규약 위반(오염 데이터). 판정 미완이므로 통과시키지 않는다.
        log.warn("[DeidentGate] ancestor chain exceeded max depth — blocking rawSn={} depth>{}",
                rawSn, MAX_ANCESTOR_DEPTH);
        return true;
    }

    /**
     * 같은 판정을 <b>RAW 행 잠금(SELECT … FOR UPDATE) 하에</b> 수행한다 — 판정 후 커밋까지 신고
     * ({@code DeidentReportService.report} 의 {@code findByRawSnForUpdate} + {@code markDeidentified("F")})
     * 와 <b>직렬화</b>되어야 하는 호출부용.
     *
     * <h3>왜 무잠금 판정으로 부족한가 (H1 · CWE-359/367)</h3>
     * export 는 프레임 N개 × 2벌 파일 복사라 수 분이 걸린다. 진입부 게이트가 {@code 'Y'} 를 읽고 통과한
     * 뒤 쓰기 도중에 신고가 커밋되면, <b>비식별 누락이 확인된 그 프레임</b>이 새 버전 폴더에 전량 기록되고
     * 성공 마감 → 통지까지 나간다. 그래서 <b>성공/부분 마감 직전</b>에 이 잠금 판정으로 재확인한다.
     * 이는 {@code ResolutionSnapshotService.snapshot} · {@code ResolutionReservationPersister} ·
     * {@code AugmentResultService.createAugmentedVideo} 가 이미 쓰는 확립된 프로토콜과 동일하다.
     *
     * <p><b>호출 규약</b>: 반드시 쓰기 트랜잭션 안에서 호출한다(잠금은 그 트랜잭션 커밋까지 유지된다).
     * 판정 결과에 따른 상태 변경도 <b>같은 트랜잭션</b>에서 끝내야 창이 닫힌다.
     *
     * <p>행이 없으면 {@code false}(통과) — 무잠금 판정과 동일 규약. 조회 실패는 전파(fail-closed).
     *
     * <p><b>조상 체인도 동일하게 잠근다</b>: 파생영상 export 는 부모의 비식별 프레임 사본을 산출하므로,
     * 부모가 신고되면 마감을 막아야 한다. 자기 행만 잠그면 신고가 부모 행에서 일어나 직렬화 자체가
     * 성립하지 않는다.
     *
     * <h3>잠금 순서 = 조상 → 자손 (교착 방지, CWE-833)</h3>
     * 체인을 <b>먼저 무잠금으로 조회</b>해 대상 rawSn 목록을 만든 뒤, <b>조상부터</b> 잠근다. 순회하며
     * 곧바로 잠그면(파생 → 조상) {@code ResolutionPersistService.persist}(parent → newRaw)와 반대 순서가
     * 되어 같은 (부모, 파생) 쌍에서 순환 대기가 성립한다(클래스 javadoc 의 전역 불변식 참조).
     *
     * <p>무잠금 선조회와 실제 잠금 사이에 <b>체인 모양이 바뀌지 않는다</b>: {@code ORGNL_RAW_SN} 은
     * 파생 생성 팩토리({@code LsDataRaw.createFromAugment} / {@code createFromResolution})에서만 대입되고
     * 이후 setter·벌크 UPDATE 어디에서도 변경되지 않는(생성 후 불변) 컬럼이다. 따라서 선조회로 확정한
     * 목록이 잠금 시점에 다른 조상을 갖게 되는 일이 없다. 판정값({@code DE_IDNTF_YN})만 바뀔 수 있으므로
     * <b>잠근 뒤 다시 읽어</b> 판정한다.
     *
     * <p><b>잠금 범위 최소화</b>: 선조회 단계에서 이미 {@code 'F'} 가 관측되면 차단이 확정이므로 어떤 행도
     * 잠그지 않고 즉시 {@code true} 를 반환한다(차단은 상태를 바꾸지 않아 직렬화가 필요 없다).
     * 잠금 중 조상 행이 사라진 경우는 그 노드만 건너뛴다 — 없는 행은 신고 상태일 수 없다.
     */
    public boolean isUnderDeidentReportLocked(Long rawSn) {
        List<Long> ancestorFirst = new ArrayList<>();
        if (planChainLocking(rawSn, ancestorFirst)) {
            return true; // 선조회에서 차단 확정('F' 관측 또는 깊이 상한) — 잠금 불필요
        }
        for (Long sn : ancestorFirst) { // 조상 → 자손 (전역 잠금 순서 불변식)
            String deIdntfYn = videoRepository.findByRawSnForUpdate(sn)
                    .map(LsDataRaw::getDeIdntfYn)
                    .orElse(null);
            if (DEIDENT_FAILED.equals(deIdntfYn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 잠금 계획 수립 — 무잠금 projection 으로 {@code rawSn} 의 체인을 훑어 잠글 대상을
     * <b>조상 → 자손</b> 순서로 {@code out} 에 채운다.
     *
     * @return 잠금 없이 차단이 확정되면 {@code true}({@code out} 은 무의미). {@code false} 면
     *         {@code out} 이 잠금 순서대로 채워진 상태다.
     */
    private boolean planChainLocking(Long rawSn, List<Long> out) {
        Long current = rawSn;
        Set<Long> visited = new HashSet<>();
        for (int depth = 0; depth <= MAX_ANCESTOR_DEPTH; depth++) {
            if (current == null || !visited.add(current)) {
                Collections.reverse(out); // 수집은 자손 → 조상, 잠금은 그 역순
                return false;
            }
            ChainNode node = videoRepository.findDeidentChainNodeByRawSn(current)
                    .map(n -> new ChainNode(n.getDeIdntfYn(), n.getOrgnlRawSn()))
                    .orElse(null);
            if (node == null) {
                Collections.reverse(out); // 행 부재 — 기존 규약대로 그 위는 보지 않는다
                return false;
            }
            if (DEIDENT_FAILED.equals(node.deIdntfYn())) {
                return true; // 이미 차단 확정 — 잠그지 않는다
            }
            out.add(current);
            current = node.orgnlRawSn();
        }
        log.warn("[DeidentGate] ancestor chain exceeded max depth — blocking rawSn={} depth>{}",
                rawSn, MAX_ANCESTOR_DEPTH);
        return true; // 깊이 상한 초과 = 오염 데이터 → fail-closed
    }
}
