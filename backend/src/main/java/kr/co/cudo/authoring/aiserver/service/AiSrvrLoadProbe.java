package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;

/**
 * 노드 하나의 <b>용도별 부하</b>를 물어 온다. [@design ADR-057]
 *
 * <p>인터페이스로 가른 이유는 두 가지다 — (1)부하를 알려주는 경로는 추론 서버가 실행을 용도별로
 * 나누는 변경과 <b>함께</b> 생기므로 그 전에는 존재하지 않고, (2)시험에서 지연·없는 경로·깨진
 * 본문을 실제 서버 없이 재현해야 한다.
 *
 * <p><b>구현은 절대 예외를 던지지 않는다.</b> 한 노드의 조회 실패가 순회를 끊으면 뒤 노드가 통째로
 * 관측에서 빠진다. 실패는 {@link AiSrvrLoadReport.Outcome} 으로 <b>분류해서</b> 돌려준다.
 */
public interface AiSrvrLoadProbe {

    AiSrvrLoadReport probe(LsAiSrvr server);
}
