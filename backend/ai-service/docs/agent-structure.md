# AI Agent 모듈 구조

## 현재 구조

GovBiz AI Service에는 현재 실제 업무 Agent가 하나 있습니다.
공고 임베딩·Qdrant 색인·후보 검색은 `support_program_index`의 Service가 직접 처리하며 별도 Agent가 아닙니다.

```text
support_program_ranking/
├── router.py   # 내부 HTTP와 안전한 오류 변환
├── models.py   # 후보·점수 structured schema
├── prompt.py   # 평가 기준과 안전 지시
├── agent.py    # OpenAI Agents SDK Runner 실행
├── service.py  # 후보 집합 검증과 최소 기준 필터
└── errors.py   # 기능 실행 실패 경계
```

이 Agent는 사용자 질문을 키워드 배열로 바꾸는 것이 아니라, Core가 전달한 공식 후보 전체를 버전된
규칙으로 점수화합니다.

```text
HTTP router
→ SupportProgramRankingService
→ SupportProgramRecommendationAgent
→ Runner.run(max_turns=1)
→ SupportProgramRankingOutput
→ exact candidate ID 검증
→ 지원대상·지역 INCOMPATIBLE 제외
→ 의미 관련성 20점·총점 60점 기준 필터 후 점수순 SupportProgramRankingResponse (0~5개)
```

현재 계약 버전은 `govbiz-support-program-ranking-v3`입니다. 후보 `id`와 응답 `programId`는
`sourceCode:sourceProgramId` 형태의 정규 식별자이며, 서로 다른 제공처가 같은 원본 ID를 사용해도
별개 후보로 검증합니다. `targetEligibility`와 `regionEligibility`는
`MATCH`, `INCOMPATIBLE`, `UNKNOWN` 중 하나이며, 정보 부족을 뜻하는 `UNKNOWN`은 자동 제외하지 않습니다.
이 Agent는 공고 단위 점수와 추천 이유를 반환합니다. 첨부문서 검색이나 근거 문단을 인용하는 답변 생성은 구현하지 않았습니다.

## 계층 규칙

- `router`는 HTTP와 안전한 오류 변환만 담당합니다.
- `service`는 Agent를 주입받고 후보 집합·정렬 규칙을 검증한 뒤 최소 기준을 통과한 결과만 반환합니다.
- `agent`는 SDK 실행·timeout·모델 오류 변환을 담당합니다.
- `models`는 요청과 structured output 불변식을 담당합니다.
- `prompt`는 LLM에 전달할 평가 지시를 담습니다. 점수 범위·합계는 `models`, 반환 최소 기준은 `service`에서도 검증합니다.
- `bootstrap`만 OpenAI client, model, Agent와 Service를 조립합니다.

후보 원문은 신뢰할 수 없는 데이터입니다. 프롬프트는 후보 안의 명령을 따르지 않도록 명시하고,
Agent는 tool이나 handoff 없이 한 turn만 실행합니다. Core와 AI Service는 모두 존재하지 않는 공고 ID와
잘못된 점수 합계를 거부합니다.

## 새 Agent를 추가하는 기준

파일을 나누기 위해 Agent를 추가하지 않습니다. 다음처럼 독립된 목표·입력·도구·평가 기준이 생길 때
새 수직 슬라이스를 만듭니다.

- 실제 공고 상세를 읽고 근거 있는 답변을 만드는 안내 Agent
- 여러 공고를 비교해 차이를 설명하는 비교 Agent

지역 Agent, 카테고리 Agent, API 출처별 Agent처럼 단순 함수나 adapter를 억지로 Agent로 만들지
않습니다. 위 예시는 향후 분리 여부를 판단할 기준이며 현재 구현 기능이 아닙니다. 대화 상태, 분기·반복,
중단·재개가 실제로 필요해질 때 상태 관리와 실행 조율 방식을 검토합니다. 현재는 tool·handoff·graph를 사용하지 않습니다.

## 테스트 배치

```text
tests/
├── support_program_index/
│   ├── conftest.py
│   ├── test_router.py
│   └── test_service.py
├── support_program_ranking/
│   ├── test_agent.py
│   └── test_router.py
├── test_bootstrap.py
├── test_config.py
└── test_health.py
```

- Agent 테스트: 실제 Runner + ScriptedModel, strict OpenAI wire 계약
- 순위화 API 테스트: 요청 검증, 정렬, 후보 ID 위조·누락 거부, 자격 불일치 제외, 안전한 503
- 색인 테스트: 고정 임베딩 HTTP 응답과 Qdrant로 색인·현재 해시 필터·누락 및 장애 처리 검증
- bootstrap 테스트: 단일 client/model/Agent/Service 객체 그래프와 종료 시 client close

테스트의 고정 모델 응답과 임베딩 벡터는 동작·계약 검증용입니다. 실제 한국어 질문의 검색 정확도나
모델의 자격 판단 정확도를 측정한 결과는 아닙니다.
