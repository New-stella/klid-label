package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.version.dto.SnapshotVersionRef;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 산출 회차 스냅샷 <b>읽기</b>의 단일 진실원 — 회차↔스냅샷 해석 + 본문 파싱.
 *
 * <h3>왜 별도 컴포넌트인가</h3>
 * 두 경로가 <b>같은 규칙</b>으로 같은 스냅샷을 읽어야 한다:
 * <ul>
 *   <li>불러오기(API-195, {@code StartVersionService}) — 화면에 올릴 내용</li>
 *   <li>확정 저장(API-196, {@code label.service.VideoLabelSaveTxService}) — 실제로 확정할 내용</li>
 * </ul>
 * 두 곳이 각자 해석하면 <b>화면에 보인 것과 저장된 것이 갈린다</b>(그 순간 사용자는 자기가 확인하지
 * 않은 내용을 확정하게 된다). 그래서 「회차 ≤ N 중 최대」 규칙과 payload 파싱을 여기 한 곳에 둔다.
 *
 * <h3>★읽기 전용 — 버전 축을 건드리지 않는다 (사용자 확정 원칙, 구속)</h3>
 * 이 컴포넌트는 <b>조회만</b> 한다. 회차 기록·활성 표식({@code ACTVTN_YN})·회차↔스냅샷 매핑을 일절
 * 변경하지 않는다. 각 회차는 서로 간섭하지 않아야 하며(검수 완료 시점마다 만들어진 데이터마트가
 * 그대로 유지돼야 한다), 확정 저장은 <b>덮어쓰기가 아니라 새로 저장</b>이다. 편해 보여도 롤백
 * 시맨틱({@code VersionService.activateRollbackTarget}·{@code deactivateOthers}·
 * {@code LsOutputVerSnpshRepository.recordActiveSnapshots})을 재사용하면 그 순간 이 원칙이 깨진다.
 *
 * <h3>조회 규칙 — 「회차 ≤ N 중 최대」, 판정 원천은 매핑이다</h3>
 * 내용이 바뀌지 않은 프레임은 {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE 때문에 그 회차에 스냅샷이
 * 생기지 않으므로(멱등 skip — 정상) "≤ N 중 최대"로 고르되, <b>기준은 번호가 아니라
 * {@code LS_OUTPUT_VER_SNPSH}(V183)에 기록된 회차</b>다. {@code LS_LABEL_VERSION.VER_NO} 는 값이 하나라
 * <b>한 스냅샷이 여러 회차의 내용</b>(1:N)임을 담지 못해, 롤백 후 재산출한 회차를 고르면 <b>그 회차에
 * 존재한 적 없는 비활성 스냅샷</b>으로 되돌리는 조용한 오복원이 났다.
 *
 * @design API-195
 * @design API-196
 * @design D5
 * @req R6
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VersionSnapshotReader {

    private final LsLabelVersionRepository labelVersionRepository;
    /** 회차↔스냅샷 매핑(V183) — 판정의 <b>단일 원천</b>. */
    private final LsOutputVerSnpshRepository outputVerSnpshRepository;
    private final ObjectMapper objectMapper;

    /**
     * 그 회차 시점 프레임 1건의 스냅샷 내용.
     *
     * @param dscdYn 그 회차를 확정할 때의 폐기 여부({@code Y}/{@code N} — 옛 형식은 {@code N})
     * @param items  그 회차의 라벨 목록. <b>{@code labelId}·{@code trackId}·자동라벨 여부·신뢰도·출처가
     *               모두 실려 있다</b> — 확정 저장이 그것을 복원의 원천으로 쓴다
     */
    public record FrameSnapshot(String dscdYn, List<LabelResponse.Item> items) {
    }

    /** 그 영상에 그 회차의 스냅샷이 실재하는지 — 요청 회차 대조(CWE-639). */
    public boolean versionExists(Long rawSn, Integer versionNo) {
        return labelVersionRepository.existsByDataRawSnAndVersionNo(rawSn, versionNo);
    }

    /**
     * 프레임마다 <b>회차 ≤ N 중 가장 큰 회차</b>의 스냅샷 행 PK 를 고른다.
     *
     * <p>결측 필드가 있는 참조는 걸러낸다 — 조회 경로가 하나 더 생겨도 규칙이 조용히 무너지지 않게
     * 하기 위한 이중 방어다. 같은 회차가 둘이면(UK 상 불가능) 행 PK 가 큰 쪽을 택해 결과를 결정적으로
     * 만든다.
     *
     * @return {@code srcSn → LBL_VERSION_SN} (그 회차 이하 매핑이 없는 프레임은 키가 없다)
     */
    public Map<Long, Long> resolveTargets(Long rawSn, Integer versionNo) {
        Map<Long, SnapshotVersionRef> best = new HashMap<>();
        for (SnapshotVersionRef ref : outputVerSnpshRepository.findRefsUpTo(rawSn, versionNo)) {
            if (ref.versionNo() == null || ref.dataSrcSn() == null || ref.labelVersionSn() == null) {
                continue;
            }
            SnapshotVersionRef current = best.get(ref.dataSrcSn());
            if (current == null || isLater(ref, current)) {
                best.put(ref.dataSrcSn(), ref);
            }
        }
        Map<Long, Long> resolved = new HashMap<>(best.size());
        best.forEach((srcSn, ref) -> resolved.put(srcSn, ref.labelVersionSn()));
        return resolved;
    }

    private static boolean isLater(SnapshotVersionRef candidate, SnapshotVersionRef current) {
        int byVersion = candidate.versionNo().compareTo(current.versionNo());
        return byVersion > 0
                || (byVersion == 0 && candidate.labelVersionSn() > current.labelVersionSn());
    }

    /**
     * 해석된 스냅샷 1건을 읽는다. 매핑이 없으면 {@link Optional#empty()}(그 회차를 알 수 없는 프레임).
     *
     * <p><b>본문만 스칼라로 읽는다</b>(F-03) — 프레임마다 엔티티를 올리면 payload(프레임당 최대 10MB)가
     * 영속성 컨텍스트에 누적되어 트랜잭션 내내 힙에 고정된다. 파싱 결과도 호출부가 프레임 처리 후
     * 참조를 놓으면 그대로 회수된다.
     */
    public Optional<FrameSnapshot> readFrame(Map<Long, Long> targets, Long srcSn) {
        Long labelVersionSn = targets.get(srcSn);
        if (labelVersionSn == null) {
            return Optional.empty();
        }
        return labelVersionRepository.findPayloadById(labelVersionSn)
                .map(payload -> new FrameSnapshot(
                        SnapshotDiscardPolicy.resolve(payload, objectMapper, srcSn),
                        parseItems(payload, srcSn)));
    }

    /**
     * 스냅샷 payload 의 {@code items} 를 라벨 항목으로 읽는다.
     *
     * <h3>fail-closed — 손상은 빈 결과가 아니라 400 (OWASP A10:2025)</h3>
     * {@code items} 가 <b>명시적 배열일 때만</b> 통과시킨다(allowlist). 파싱 실패는 물론 키 누락
     * ({@code {}} · 스칼라 root) · {@code {"items":null}} · 배열이 아닌 값도 전부 손상이다.
     * {@code blank}/{@code null} 은 <b>손상이 아니라 라벨 0건</b>이다(정상적으로 라벨이 없던 프레임) —
     * {@code VersionService.requireParsableSnapshot} 과 같은 판정 규칙이다.
     *
     * <p>읽지 못한 것을 라벨 0건으로 돌려주면 화면이 빈 상태를 그리고, 그 위에서 확정하면 남아 있던
     * 라벨이 통째로 지워진다.
     *
     * <p><b>{@code labelId} 를 잃지 않는 것이 이 파싱의 핵심이다</b>: 라벨 표시 색상·라벨명·속성 정의의
     * 단일 진실원이 라벨 마스터이고 그 연결 실체가 {@code labelId} 다. 항목을 그대로
     * {@code LabelResponse.Item} 으로 역직렬화해 필드를 통째로 보존한다(골라 옮기면 하나 빠뜨리는 순간
     * 저장 후 마스터 조인이 끊긴다 — 실사고 이력).
     */
    private List<LabelResponse.Item> parseItems(String payload, Long srcSn) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        JsonNode items;
        try {
            items = objectMapper.readTree(payload).path("items");
        } catch (Exception e) {
            // 내부 상태(경로/스키마/본문)를 노출하지 않는다 — 예외 종류만 로깅(CWE-209/359).
            log.warn("[Version] snapshot parse failed srcSn={} cause={}",
                    srcSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT, "버전 스냅샷을 읽을 수 없습니다.");
        }
        if (!items.isArray()) {
            log.warn("[Version] snapshot has no label array srcSn={}", srcSn);
            throw new CustomException(ErrorCode.INVALID_INPUT, "버전 스냅샷을 읽을 수 없습니다.");
        }
        try {
            return objectMapper.readerForListOf(LabelResponse.Item.class).readValue(items);
        } catch (Exception e) {
            log.warn("[Version] snapshot item read failed srcSn={} cause={}",
                    srcSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT, "버전 스냅샷을 읽을 수 없습니다.");
        }
    }
}
