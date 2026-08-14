package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.util.KeypointPoint;
import kr.co.cudo.authoring.common.util.KeypointSerializer;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.label.entity.LsLabel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 라벨 조회/수정 결과.
 * items[].autoLblYn 가 'Y' 면 자동, 'N' 면 사용자 입력.
 *
 * <p>siblings 는 동일 영상(rawSn=videoId)에 속한 모든 프레임의 (srcSn, frameNo) 목록.
 * FRAME_NO 오름차순 정렬. FE 의 프레임 타임라인/이전·다음 이동을 위해 함께 반환된다.
 * 다른 프레임의 라벨은 포함하지 않으며, 필요 시 별도 호출한다.
 *
 * <p>lockSttsCd 는 영상(LS_DATA_RAW)의 잠금 상태 코드.
 * null/빈 문자열 = 잠금 없음, "LOCKED_FOR_REDEIDENT" = 비식별 재처리 중(라벨 저장 차단).
 * FE 는 이 값으로 라벨링 화면 진입 시점에 UI 비활성화를 결정한다 (다중 세션 일관성).
 *
 * <p>labelVersion 은 이 프레임의 <b>라벨셋 버전</b>(C-ISSUE-21). FE 는 이 값을 보관했다가 저장 요청
 * ({@code LabelBulkUpsertRequest.labelVersion})에 실어 보내면, 그사이 다른 세션이 라벨을 바꾼 경우
 * 409 로 거부되어 full-replace 로 인한 타인 라벨 유실을 막는다. 컨텍스트 없는 레거시 빌드 경로는 null.
 */
public record LabelResponse(
        Long srcSn,
        Integer frameNo,
        Long videoId,
        String frameImageType,
        String lockSttsCd,
        Long labelVersion,
        /**
         * R4·R5 — 이 프레임의 폐기여부({@code Y}/{@code N}). 화면이 폐기 배지·복원 버튼을 그리는 근거다.
         *
         * <p><b>{@code null} 이면 "이 응답은 폐기 축을 싣지 않는다"</b>는 뜻이며 "폐기 아님"이 아니다.
         * 라벨 조회·저장 응답에서만 채워진다 — 아래 {@code of(...)} 오버로드 주석의 <b>버전 스냅샷 불변</b>
         * 항목 참조.
         */
        @com.fasterxml.jackson.annotation.JsonInclude(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String dscdYn,
        List<SiblingFrame> siblings,
        List<Item> items
) {

    /**
     * 영상 잠금 응답 코드 — FE 판정 상수({@code frontend/src/features/label/types.ts} 의
     * {@code LockSttsCd.LOCKED_FOR_REDEIDENT})와 동일 문자열이어야 한다(H-ISSUE-41).
     *
     * <p>내부 저장 모델인 락 행 상태({@code LsAuthWorkLock.STATUS_LOCKED='LOCKED'})와는 <b>다른 축</b>이다 —
     * 그 값을 응답에 재사용하면 화면이 잠금을 인지하지 못한다.
     */
    public static final String LOCK_STTS_LOCKED_FOR_REDEIDENT = "LOCKED_FOR_REDEIDENT";

    /**
     * 동일 영상 내 형제 프레임 식별자.
     *
     * <p>hasLabel: 해당 프레임(srcSn)에 저장된 라벨(LS_DATA_LBL)이 1건 이상 존재하는지 여부.
     * FE 프레임 strip 이 SAVED(연두) 상태를 표시하는 데 사용한다(신규 DB 컬럼 없음 — 라벨 존재 여부 파생).
     * 라벨 존재 정보를 주입하지 않는 레거시 경로는 {@code from(src)} 로 false 로 둔다(하위호환).
     */
    public record SiblingFrame(
            Long srcSn, Integer frameNo, boolean hasLabel,
            /**
             * R4·R5 — 그 형제 프레임의 폐기여부({@code Y}/{@code N}). 프레임 strip 이 폐기 프레임을
             * 흐리게 표시하는 근거다. {@code null} 이면 이 응답이 폐기 축을 싣지 않는다는 뜻이다
             * (버전 스냅샷 경로 — 아래 {@code of(...)} 주석 참조).
             */
            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String dscdYn) {
        /** 라벨 존재 여부 미지정 — hasLabel=false (컨텍스트 없는 레거시 경로 호환). */
        public static SiblingFrame from(LsDataSrc src) {
            return from(src, false);
        }

        /** 폐기 축 미포함 빌드 — 버전 스냅샷 등 폐기 여부를 담지 않는 경로. */
        public static SiblingFrame from(LsDataSrc src, boolean hasLabel) {
            return from(src, hasLabel, false);
        }

        public static SiblingFrame from(LsDataSrc src, boolean hasLabel, boolean includeDiscard) {
            return new SiblingFrame(src.getSrcSn(), Math.toIntExact(src.getFrameNo()), hasLabel,
                    includeDiscard ? src.getDscdYn() : null);
        }
    }

    public record Item(
            Long id,
            String lblTypeCd,
            String label,
            Long labelId,
            String labelName,
            String color,
            List<List<Double>> points,
            String autoLblYn,
            BigDecimal confScore,
            String trackId,
            String lblSrcCd
    ) {
        /**
         * LS_DATA_LBL + LS_LABEL 결합 응답.
         *
         * <h3>V6 — {@code autoLblYn} 의 {@code null}→{@code 'N'} 치환은 <b>여기 한 곳</b>이다 (Critical)</h3>
         * 흡수 전에는 "AI 정보 행이 없으면 수동 라벨"이라 {@code aiInfo == null} 분기가 {@code 'N'} 을
         * 냈다. 흡수 후 그 부재는 <b>컬럼 {@code null}</b> 이고, 응답 계약은 <b>그대로 {@code 'N'}</b> 이다
         * (외부 FE 팀이 쓰는 계약이라 값 시맨틱을 바꾸지 않는다).
         *
         * <p><b>DB 에 {@code 'N'} 을 적어 이 치환을 없애지 말 것</b> — 그러면 "사람이 그린 라벨"과
         * "AI 가 만들었는데 자동 플래그가 N"(버전 롤백 복원)이 같은 값이 되어 영구히 구분되지 않는다.
         * DB 축(null 유지)과 응답 축('N' 유지)을 서로 다른 층에서 각각 보존하는 것이 의도다.
         *
         * <p>{@code confScore}/{@code lblSrcCd} 는 치환하지 않는다 — 흡수 전에도 그 둘은 {@code null}
         * 이 그대로 나갔다.
         *
         * <p>{@code lsLabel == null} 이면 (V32 마이그 매칭 실패 등) {@code labelId/labelName/color} 모두 null.
         * <p>{@code label} 필드(LS_DATA_LBL.LABEL 텍스트)는 호환 위해 그대로 노출 — FE 는 labelName/color 우선 사용.
         */
        public static Item from(LsDataLbl entity, LsLabel lsLabel, ObjectMapper objectMapper) {
            List<List<Double>> nested = parsePoints(entity.getLblTypeCd(), entity.getPointCn(), objectMapper);
            return new Item(
                    entity.getLblSn(),
                    entity.getLblTypeCd(),
                    entity.getLabelNm(),
                    entity.getLabelId(),
                    lsLabel != null ? lsLabel.getLabelNm() : null,
                    lsLabel != null ? lsLabel.getColrVl() : null,
                    nested,
                    entity.getAutoLblYn() != null ? entity.getAutoLblYn() : LsDataLbl.AUTO_NO,
                    entity.getConfScore(),
                    entity.getTrackId(),
                    entity.getLblSrcCd()
            );
        }

        /**
         * 좌표 파싱 — {@code LBL_TYPE_CD} 기반 type-route.
         * <ul>
         *   <li>SKELETON: {@link KeypointSerializer#fromJson} 삼중값 [[x,y,v], x17] (v 보존 — 응답/스냅샷 무손실)</li>
         *   <li>그 외(BBOX/POLYGON/SEGMENT/TRACK): 기존 2-튜플 {@link LabelPointSerializer#fromJson} (불변)</li>
         * </ul>
         */
        private static List<List<Double>> parsePoints(String lblTypeCd, String pointCn, ObjectMapper objectMapper) {
            if (LsDataLbl.TYPE_SKELETON.equals(lblTypeCd)) {
                List<KeypointPoint> kps = KeypointSerializer.fromJson(pointCn, objectMapper);
                List<List<Double>> nested = new ArrayList<>(kps.size());
                for (KeypointPoint kp : kps) {
                    nested.add(List.of(kp.x(), kp.y(), (double) kp.v()));
                }
                return nested;
            }
            List<Point> parsed = LabelPointSerializer.fromJson(pointCn, objectMapper);
            List<List<Double>> nested = new ArrayList<>(parsed.size());
            for (Point p : parsed) {
                nested.add(List.of(p.x(), p.y()));
            }
            return nested;
        }
    }

    /**
     * 컨텍스트(현재 프레임 + 영상 + 형제 프레임) 포함 응답 빌드.
     *
     * @param current    현재 프레임 (srcSn, frameNo, rawSn 추출)
     * @param siblings   동일 영상의 모든 프레임 (FRAME_NO ASC 정렬 권장)
     * @param entities   현재 프레임의 라벨 엔티티
     */
    /** 기본 frameImageType('DEID'), lockSttsCd=null 빌드 — 기존 호출자 호환. */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, "DEID", null, objectMapper);
    }

    /** Phase 3 — frameImageType 명시 빌드 (lockSttsCd=null). 기존 호출자 호환. */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, frameImageType, null, objectMapper);
    }

    /**
     * Phase 3 보강 — frameImageType + 영상 잠금 상태 코드 명시 빌드.
     * lockSttsCd 는 LS_DATA_RAW.LOCK_STTS_CD 그대로 전달 (null/"LOCKED_FOR_REDEIDENT").
     *
     * <p>V6 — 구 시그니처에는 {@code Map<Long, LsDataLblAiInfo> aiInfoMap} 인자가 있었다(라벨 PK →
     * AI 정보 행). 생산이력이 라벨 행의 컬럼이 되어 그 맵을 만들 이유가 사라졌고, 맵을 넘기던 오버로드와
     * 넘기지 않던 오버로드가 <b>같은 시그니처로 합쳐졌다</b>. 응답 스키마는 불변이다 — 조달처만 바뀐다.
     * <p>LS_LABEL 마스터 맵 없이 호출되는 경로 (labelId/labelName/color 모두 null).
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, frameImageType, lockSttsCd, Map.of(), objectMapper);
    }

    /**
     * Phase 2 — LS_DATA_LBL + LS_LABEL 결합 응답.
     * <p>{@code lsLabelMap} 키: {@code LS_LABEL.labelId}. 라벨 마스터 매칭 안 되는 엔티티는 labelName/color=null.
     * <p>LabelService 가 한 번의 일괄 lookup({@code findAllById})으로 맵을 구성하여 전달 — N+1 회피.
     * <p>hasLabel 정보 없이 호출되는 경로 — 모든 형제 프레임 hasLabel=false (빈 집합 위임).
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   Map<Long, LsLabel> lsLabelMap,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, frameImageType, lockSttsCd, lsLabelMap,
                Set.of(), objectMapper);
    }

    /**
     * R5 — 형제 프레임별 라벨 존재 플래그(hasLabel) 포함 결합 응답.
     * <p>{@code labeledSrcSns}: 라벨이 1건 이상 존재하는 프레임 srcSn 집합. LabelService 가 단일
     * IN 쿼리({@code findDistinctSrcSnsWithLabelIn})로 조회하여 전달 — 프레임 수와 무관한 1쿼리(N+1 금지).
     * 각 형제 프레임의 hasLabel 은 이 집합의 포함 여부로 결정된다.
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   Map<Long, LsLabel> lsLabelMap,
                                   Set<Long> labeledSrcSns,
                                   ObjectMapper objectMapper) {
        // DEV_FIX(H12) — 여기서 엔티티의 lblVer 를 읽지 않는다. LBL_VER 는 insertable/updatable=false 라
        //   원자 UPDATE 이후 영속 컨텍스트 값이 stale 이며, 그 값을 조용히 내려보내면 클라이언트가 낡은
        //   버전을 왕복시켜 무한 409 에 빠진다. 또한 이 오버로드는 <b>버전 스냅샷 직렬화</b> 경로가 쓰는데,
        //   가변 카운터가 페이로드에 섞이면 라벨이 동일해도 VERSION_HASH 가 달라져 멱등 판정이 깨진다.
        //   버전이 필요한 경로(라벨 조회·저장 응답)는 아래 <b>명시 오버로드</b>로 최신 값을 직접 전달한다.
        return of(current, siblings, entities, frameImageType, lockSttsCd, lsLabelMap,
                labeledSrcSns, null, objectMapper);
    }

    /**
     * C-ISSUE-21 — 라벨셋 버전을 <b>명시</b>해 빌드한다(라벨 조회·저장 응답 전용).
     *
     * <p>저장 경로는 라벨셋 버전을 원자 UPDATE({@code bumpLabelVersionIn})로 올리므로 영속성 컨텍스트의
     * {@code LsDataSrc} 인스턴스는 stale 이다. 따라서 호출부가 "잠금 하에 읽은 값 + 1" 로 계산한 최신 값을
     * 직접 전달한다. 조회 경로는 같은 트랜잭션에서 방금 읽은 엔티티 값을 명시 전달한다.
     *
     * @param labelVersion 이 프레임의 최신 라벨셋 버전. {@code null} 이면 응답에 버전을 싣지 않는다
     *                     (버전 개념이 없는 스냅샷/레거시 빌드 경로).
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   Map<Long, LsLabel> lsLabelMap,
                                   Set<Long> labeledSrcSns,
                                   Long labelVersion,
                                   ObjectMapper objectMapper) {
        return of(current, siblings, entities, frameImageType, lockSttsCd, lsLabelMap,
                labeledSrcSns, labelVersion, objectMapper, false);
    }

    /**
     * R4·R5 — 프레임 폐기여부({@code dscdYn}) 포함 여부를 <b>명시</b>해 빌드한다.
     *
     * <h3>왜 기본값이 "포함 안 함"인가 — 버전 스냅샷 불변 (Critical)</h3>
     * 이 레코드는 응답 DTO이면서 동시에 <b>검수 승인 버전 스냅샷</b>({@code LS_LABEL_VERSION.LABEL_PAYLOAD})
     * 의 직렬화 형태이기도 하다. 스냅샷은 그 JSON 을 SHA-256 해시해 {@code VERSION_HASH} 로 삼으므로,
     * 필드를 하나 늘리면 <b>라벨이 전혀 같아도 해시가 달라져</b> 기존 스냅샷과의 멱등 대조가 어긋난다.
     * 그래서 폐기 축은 <b>라벨 조회·저장 응답에서만</b> 싣고, 스냅샷 직렬화 경로는 종전 형태를 그대로 쓴다
     * ({@code @JsonInclude(NON_NULL)} 로 필드 자체가 나타나지 않는다).
     *
     * <p>버전 스냅샷에 폐기 상태를 담는 것(D5)은 <b>별도 단계</b>의 일이며, 그때는 해시 변화가
     * 의도된 동작이다. 여기서 곁다리로 바꾸지 않는다.
     *
     * @param includeDiscard {@code true} 면 현재 프레임과 형제 프레임의 폐기여부를 응답에 싣는다.
     */
    public static LabelResponse of(LsDataSrc current,
                                   List<LsDataSrc> siblings,
                                   List<LsDataLbl> entities,
                                   String frameImageType,
                                   String lockSttsCd,
                                   Map<Long, LsLabel> lsLabelMap,
                                   Set<Long> labeledSrcSns,
                                   Long labelVersion,
                                   ObjectMapper objectMapper,
                                   boolean includeDiscard) {
        Set<Long> safeLabeled = labeledSrcSns == null ? Set.of() : labeledSrcSns;
        List<SiblingFrame> siblingDtos = new ArrayList<>(siblings.size());
        for (LsDataSrc s : siblings) {
            siblingDtos.add(SiblingFrame.from(s, safeLabeled.contains(s.getSrcSn()), includeDiscard));
        }
        Map<Long, LsLabel> safeLabelMap = lsLabelMap == null ? Map.of() : lsLabelMap;
        List<Item> items = entities.stream()
                .map(e -> Item.from(
                        e,
                        e.getLabelId() != null ? safeLabelMap.get(e.getLabelId()) : null,
                        objectMapper))
                .toList();
        return new LabelResponse(
                current.getSrcSn(),
                Math.toIntExact(current.getFrameNo()),
                current.getRawSn(),
                frameImageType,
                lockSttsCd,
                labelVersion,
                includeDiscard ? current.getDscdYn() : null,
                siblingDtos,
                items
        );
    }

    /**
     * D5 — <b>검수 승인 버전 스냅샷</b> 직렬화 전용 빌드. 폐기여부를 <b>자기 프레임만</b> 싣는다.
     *
     * <h3>왜 형제 프레임의 폐기여부는 싣지 않는가 (Critical)</h3>
     * 이 payload 의 SHA-256 이 {@code VERSION_HASH} 이고 그 해시가 프레임 단위 멱등 판정 축이다.
     * 형제 폐기여부까지 실으면 <b>프레임 하나를 폐기하는 순간 같은 영상 모든 프레임의 해시가 흔들려</b>
     * 라벨이 하나도 바뀌지 않은 프레임까지 새 스냅샷이 적층된다. 폐기는 프레임 축이고 스냅샷은 프레임
     * 단위 행이므로 <b>그 프레임의 값만</b> 담는 것이 정확하다.
     *
     * <p>이 전환으로 스냅샷 해시가 달라지는 것은 <b>의도된 동작</b>이다(D5). 이미 저장된 승인 스냅샷은
     * 그대로이므로 한동안 옛 형식(키 부재)과 공존하며, 읽는 쪽은 키 부재를 "폐기 아님"으로 해석한다
     * ({@code SnapshotDiscardPolicy} 단일 판정기).
     *
     * @design D5
     * @req R6
     */
    public static LabelResponse ofSnapshot(LsDataSrc current,
                                           List<LsDataSrc> siblings,
                                           List<LsDataLbl> entities,
                                           String frameImageType,
                                           String lockSttsCd,
                                           ObjectMapper objectMapper) {
        LabelResponse base = of(current, siblings, entities, frameImageType, lockSttsCd,
                Map.of(), Set.of(), null, objectMapper, false);
        return new LabelResponse(base.srcSn(), base.frameNo(), base.videoId(),
                base.frameImageType(), base.lockSttsCd(), base.labelVersion(),
                current.getDscdYn() == null ? LsDataSrc.DSCD_NO : current.getDscdYn(),
                base.siblings(), base.items());
    }

    /**
     * 라벨 엔티티만으로 빌드 (컨텍스트 없음 — 기존 호출자 호환).
     * 검수 조회 등 다른 프레임/영상 컨텍스트가 필요 없는 경로에서 사용.
     */
    public static LabelResponse of(List<LsDataLbl> entities, ObjectMapper objectMapper) {
        return new LabelResponse(
                null,
                null,
                null,
                null,
                null,
                null,
                // 프레임 컨텍스트가 없는 경로라 폐기여부를 알 수 없다 — 값을 지어내지 않고 비운다.
                null,
                Collections.emptyList(),
                entities.stream().map(e -> Item.from(e, null, objectMapper)).toList()
        );
    }
}
