# GovBiz AI Service

FastAPI와 OpenAI Agents SDK로 공식 지원사업 후보를 점수화하는 내부 서비스입니다. 브라우저에 직접
공개하지 않고 Spring Core API만 호출합니다.

## 책임

AI Service가 하는 일:

- 사용자의 자연어 질문과 Core가 검증한 공고 후보를 함께 읽음
- 버전된 100점 평가 기준으로 모든 후보를 점수화
- 공고별 세부 점수·총점·추천 이유를 strict structured output으로 반환

AI Service가 하지 않는 일:

- 기업마당 API 호출 또는 공고 저장
- 접수 상태 계산과 공식 URL 검증
- 존재하지 않는 공고 추가
- 최종 HTTP 공개 DTO 조립
- 점수 결과 영속화

## 내부 API

```http
GET /internal/v1/health
POST /internal/v1/support-program-rankings/rank
```

점수화 요청은 최대 20개 후보와 상위 결과 개수 1~5개를 받습니다. 계약 예시는
[지원사업 검색·추천 HTTP 계약](../../docs/support-program-search-contract.md)에 있습니다.

## 평가 기준

`govbiz-support-program-ranking-v1`은 다음 배점을 사용합니다.

| 항목 | 배점 |
|---|---:|
| 질문과 공고의 의미적 관련성 | 40 |
| 기업 유형·업종·업력과 지원 대상 적합성 | 25 |
| 지역 적합성 | 15 |
| 신청 시점과 접수 상태 적합성 | 10 |
| 원하는 지원 유형 적합성 | 10 |

평가 기준은 [prompt.py](app/support_program_ranking/prompt.py)에 한 번만 정의합니다. Kotlin에
지역·카테고리 단어 사전이나 `지역 +12` 같은 점수표를 복제하지 않습니다.

## 수직 호출 흐름

```text
Core API
→ POST /internal/v1/support-program-rankings/rank
→ support_program_ranking/router.py
   → SupportProgramRankingRequest로 요청 검증
→ SupportProgramRankingService.rank()
→ SupportProgramRecommendationAgent.rank()
→ OpenAI Agents SDK Runner.run(max_turns=1)
   ├→ prompt.py의 평가 기준 사용
   ├→ 후보 문장을 지시가 아닌 데이터로 취급
   └→ SupportProgramRankingOutput strict schema로 모든 후보 점수화
→ Service가 입력 후보 ID exact set을 재검증
→ 총점 내림차순 정렬 후 resultLimit만 선택
→ SupportProgramRankingResponse
→ Core API
```

예를 들어 Core가 두 공고를 보내고 `resultLimit=1`을 지정하면 Agent는 두 후보를 모두 점수화합니다.
Service는 누락·추가·중복 ID를 거부한 뒤 가장 높은 한 건만 Core에 반환합니다.

## 파일별 책임

```text
app/
├── main.py                         # FastAPI, router, lifespan
├── config.py                       # 모델과 timeout 환경설정
├── bootstrap.py                    # OpenAI client/model/agent/service 조립
├── health/                         # 공통 Health 수직 기능
│   ├── router.py                   # 내부 Health HTTP 경계
│   └── models.py                   # Health 응답 계약
└── support_program_ranking/        # 지원사업 점수화 수직 기능
    ├── router.py                   # 내부 HTTP 경계
    ├── models.py                   # 요청·출력·응답 Pydantic 계약
    ├── prompt.py                   # 버전된 100점 평가 기준
    ├── agent.py                    # Runner와 OpenAI 실행
    ├── service.py                  # 후보 ID 검증·정렬·상위 선택
    └── errors.py                   # 안전한 기능 실패
```

의존성 방향은 `router → service → agent → Agents SDK`입니다. `bootstrap.py`만 구체 OpenAI client와
model을 생성하고, 요청마다 Agent를 새로 만들지 않습니다.

## 실패 흐름

```text
요청 형식 오류
→ FastAPI/Pydantic 422

OpenAI timeout·거부·SDK 오류·structured output 오류
→ AgentExecutionError
→ 상세정보 없는 내부 HTTP 503

후보 ID 누락·추가·중복
→ AgentExecutionError
→ 상세정보 없는 내부 HTTP 503
```

사용자 질문, 공고 원문, API key와 OpenAI 원문 오류를 실패 응답에 포함하지 않습니다. Core는 다시
내부 응답의 ID·점수 범위·점수 합계·순서를 검증합니다.

## 설정

```dotenv
OPENAI_API_KEY=필수
OPENAI_MODEL=gpt-5.6-luna
LLM_MODEL_TIMEOUT_SECONDS=8.0
LLM_RUN_TIMEOUT_SECONDS=10.0
```

전체 Agent 제한은 모델 호출 제한보다 길고 Core의 기본 읽기 제한 `12s`보다 짧게 유지합니다.

## 설치와 실행

```bash
uv sync --locked --extra dev
OPENAI_API_KEY=발급받은_키 \
uv run --locked --extra dev python -m uvicorn app.main:create_app --factory --reload --port 8000
```

## 검증

```bash
uv lock --check
uv sync --locked --extra dev
uv pip check --python .venv/bin/python
uv run --locked --extra dev python -m pytest
uv build
```

테스트는 `agents.testing.ScriptedModel`과 HTTP mock transport를 사용하므로 실제 OpenAI 네트워크를
호출하지 않습니다.

Agent 확장 원칙은 [AI Agent 모듈 구조](docs/agent-structure.md)를 참고하세요.
