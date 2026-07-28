package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.DeidentChainProjection;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S7 (CWE-833 교착 방지) — {@link DeidentReportGate#isUnderDeidentReportLocked} 의 <b>잠금 순서</b> 검증.
 *
 * <h3>닫는 결함</h3>
 * 구 구현은 체인을 순회하며 <b>파생 → 조상</b> 순으로 {@code SELECT … FOR UPDATE} 를 걸었다. 그런데
 * {@code ResolutionPersistService.persist} 는 "잠금 순서는 항상 parent → newRaw 로 고정한다(교착 방지)"를
 * 명시적 관례로 두고 <b>조상 → 파생</b> 순으로 잠근다. 두 트랜잭션이 같은 (부모, 파생) 쌍을 반대 순서로
 * 잠그면 순환 대기가 성립한다(현재 라이프사이클에서 재현되지 않는 것은 호출 그래프의 우연일 뿐 보장이 아니다).
 *
 * <p>따라서 게이트는 체인을 <b>무잠금으로 먼저 조회</b>해 목록을 만든 뒤 <b>조상부터</b> 잠근다.
 */
class DeidentReportGateLockOrderTest {

    private VideoRepository videoRepository;
    private DeidentReportGate gate;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        gate = new DeidentReportGate(videoRepository);
    }

    /** 무잠금 체인 1노드 스텁 — {@code (DE_IDNTF_YN, ORGNL_RAW_SN)}. */
    private void stubChainNode(Long rawSn, String deIdntfYn, Long orgnlRawSn) {
        when(videoRepository.findDeidentChainNodeByRawSn(rawSn)).thenReturn(Optional.of(
                new DeidentChainProjection() {
                    @Override
                    public String getDeIdntfYn() {
                        return deIdntfYn;
                    }

                    @Override
                    public Long getOrgnlRawSn() {
                        return orgnlRawSn;
                    }
                }));
    }

    /** 잠금 조회 스텁 — 실제 RAW 행(잠금 시점 값). */
    private void stubLockedRow(Long rawSn, String deIdntfYn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "LOCK-" + rawSn, "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(raw, "rawSn", rawSn);
        raw.markDeidentified(deIdntfYn);
        when(videoRepository.findByRawSnForUpdate(rawSn)).thenReturn(Optional.of(raw));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("잠금판정은_조상부터_잠근다 — parent→newRaw 관례와 동일 방향(교착 방지)")
    void locksAncestorBeforeDescendant() {
        // given — 파생(920) → 부모(910) 체인. 둘 다 정상('Y')이라 전 노드를 잠그고 재판정한다.
        Long parentSn = 910L;
        Long derivativeSn = 920L;
        stubChainNode(derivativeSn, "Y", parentSn);
        stubChainNode(parentSn, "Y", null);
        stubLockedRow(parentSn, "Y");
        stubLockedRow(derivativeSn, "Y");

        // when
        boolean blocked = gate.isUnderDeidentReportLocked(derivativeSn);

        // then — 통과하되, 잠금은 반드시 조상(부모) 먼저 → 자손(파생) 순서다.
        assertThat(blocked).isFalse();
        InOrder order = inOrder(videoRepository);
        order.verify(videoRepository).findByRawSnForUpdate(parentSn);
        order.verify(videoRepository).findByRawSnForUpdate(derivativeSn);
    }

    @Test
    @DisplayName("잠금후_재판정에서_조상이_F면_차단된다 — 선조회_이후_커밋된_신고를_놓치지_않는다")
    void blocksWhenAncestorBecomesReportedUnderLock() {
        // given — 무잠금 선조회 시점에는 둘 다 'Y' 였으나, 잠금 시점에 부모가 'F' 로 커밋된 상태.
        Long parentSn = 911L;
        Long derivativeSn = 921L;
        stubChainNode(derivativeSn, "Y", parentSn);
        stubChainNode(parentSn, "Y", null);
        stubLockedRow(parentSn, "F");

        // when / then — 잠근 뒤 다시 읽어 판정하므로 차단된다(자손 잠금은 도달하지 않아도 무방).
        assertThat(gate.isUnderDeidentReportLocked(derivativeSn)).isTrue();
        verify(videoRepository).findByRawSnForUpdate(parentSn);
    }

    @Test
    @DisplayName("선조회에서_이미_F면_어떤_행도_잠그지_않는다 — 잠금 범위 최소화")
    void doesNotLockWhenAlreadyBlockedByUnlockedRead() {
        // given — 부모가 이미 신고('F') 상태. 차단은 상태를 바꾸지 않으므로 직렬화가 필요 없다.
        Long parentSn = 912L;
        Long derivativeSn = 922L;
        stubChainNode(derivativeSn, "Y", parentSn);
        stubChainNode(parentSn, "F", null);

        // when / then
        assertThat(gate.isUnderDeidentReportLocked(derivativeSn)).isTrue();
        verify(videoRepository, never()).findByRawSnForUpdate(parentSn);
        verify(videoRepository, never()).findByRawSnForUpdate(derivativeSn);
    }

    @Test
    @DisplayName("원본영상은_자기행만_잠근다 — 조상 없는 체인")
    void locksSelfOnlyForOriginVideo() {
        // given — ORGNL_RAW_SN 이 없는 원본 영상.
        Long rawSn = 913L;
        stubChainNode(rawSn, "Y", null);
        stubLockedRow(rawSn, "Y");

        // when / then
        assertThat(gate.isUnderDeidentReportLocked(rawSn)).isFalse();
        verify(videoRepository).findByRawSnForUpdate(rawSn);
    }
}
