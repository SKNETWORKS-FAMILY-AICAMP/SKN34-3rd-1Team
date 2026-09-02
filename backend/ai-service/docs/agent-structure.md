# AI Agent 모듈 구조

## 현재 구조

GovBiz AI Service에는 현재 실제 업무 Agent가 하나 있습니다.

```text
support_program_ranking/
├── router.py   # 내부 HTTP와 안전한 오류 변환
├── models.py   # 후보·점수 structured schema
├── prompt.py   # 평가 기준과 안전 지시
├── agent.py    # OpenAI Agents SDK Runner 실행
├── service.py  # 후보 집합 검증과 상위 결과 선택
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
→ 점수순 SupportProgramRankingResponse
```

## 계층 규칙

- `router`는 HTTP와 안전한 오류 변환만 담당합니다.
- `service`는 Agent를 주입받고 후보 집합·정렬 규칙을 검증합니다.
- `agent`는 SDK 실행·timeout·모델 오류 변환을 담당합니다.
- `models`는 요청과 structured output 불변식을 담당합니다.
- `prompt`는 LLM 평가 기준의 단일 원본입니다.
- `bootstrap`만 OpenAI client, model, Agent와 Service를 조립합니다.

후보 원문은 신뢰할 수 없는 데이터입니다. 프롬프트는 후보 안의 명령을 따르지 않도록 명시하고,
Agent는 tool이나 handoff 없이 한 turn만 실행합니다. Core와 AI Service는 모두 존재하지 않는 공고 ID와
잘못된 점수 합계를 거부합니다.

## 새 Agent를 추가하는 기준

파일을 나누기 위해 Agent를 추가하지 않습니다. 다음처럼 독립된 목표·입력·도구·평가 기준이 생길 때
새 수직 슬라이스를 만듭니다.

- 실제 공고 상세를 읽고 근거 있는 답변을 만드는 안내 Agent
- 여러 공고를 비교해 차이를 설명하는 비교 Agent
- GovClause의 승인 조건과 사람 검토를 다루는 별도 Agent

지역 Agent, 카테고리 Agent, API 출처별 Agent처럼 단순 함수나 adapter를 억지로 Agent로 만들지
않습니다. 대화 상태, 분기·반복, 중단·재개가 실제로 필요해질 때 LangGraph 같은 상태ful orchestration을
다시 평가합니다.

## 테스트 배치

```text
tests/
├── support_program_ranking/
│   ├── test_agent.py
│   └── test_router.py
├── test_bootstrap.py
└── test_health.py
```

- Agent 테스트: 실제 Runner + ScriptedModel, strict OpenAI wire 계약
- API 테스트: 요청 검증, 정렬, 후보 ID 위조·누락 거부, 안전한 503
- bootstrap 테스트: 단일 client/model/Agent/Service 객체 그래프와 종료 시 client close
