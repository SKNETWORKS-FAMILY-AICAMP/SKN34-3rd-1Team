# GovBiz AI Service

FastAPI, OpenAI 임베딩, Qdrant로 전체 공고에서 관련 후보를 찾고 OpenAI Agents SDK로 후보를
점수화하는 내부 서비스입니다. 브라우저에 직접 공개하지 않고 Spring Core API만 호출합니다.

프로젝트 전체 기술 구성은 [기술 문서](../../docs/technology.md), 기능별 완료 범위와 남은 작업은
[구현 현황](../../docs/implementation-status.md)을 참고하세요. 이 문서는 AI Service 실행·설정·내부 처리 규칙을 다룹니다.

## 책임

AI Service가 하는 일:

- 사용자의 자연어 질문과 Core가 검증한 공고 후보를 함께 읽음
- Core가 보낸 공고 검색 문서를 OpenAI로 임베딩하고 Qdrant에 색인
- 현재 MySQL 공고 ID·내용 해시 목록 안에서 의미가 가까운 후보를 최대 20개 검색
- 버전된 100점 평가 기준으로 모든 후보를 점수화
- 지원대상·지역의 명백한 자격 불일치를 제외하고 의미 관련성·총점 최소 기준을 통과한 공고만 0~5개로 반환
- 반환 공고의 자격 판정·세부 점수·총점·추천 이유를 strict structured output으로 제공

AI Service가 하지 않는 일:

- 기업마당 API 호출 또는 MySQL 원본 공고 저장
- 접수 상태 계산과 공식 URL 검증
- 존재하지 않는 공고 추가
- 최종 HTTP 공개 DTO 조립
- 점수 결과 영속화

## 내부 API

```http
GET /internal/v1/health
POST /internal/v1/support-program-rankings/rank
PUT /internal/v1/support-program-index/batch
POST /internal/v1/support-program-index/prune
POST /internal/v1/support-program-index/search
```

점수화 요청은 최대 20개 후보와 상위 결과 개수 1~5개를 받습니다. 응답 `rankings`는 적격 공고가
없을 수 있으므로 0개부터 `resultLimit`개까지입니다. 계약 예시는
[지원사업 검색·추천 HTTP 계약](../../docs/support-program-search-contract.md)에 있습니다.

Health 응답은 프로세스의 HTTP 응답 여부만 확인합니다. OpenAI 모델 호출 성공이나 Qdrant 연결·색인
완료 여부를 검사하는 readiness 검사는 아닙니다. `/internal` 경로 자체에 인증 기능은 없으며,
기본 Compose에서는 AI Service 포트를 호스트에 공개하지 않습니다.

## 전체 공고 의미 검색

공고의 제목·요약·지원대상 등을 포함한 검색 문서는 Core가 구성합니다. `id`는
`BIZINFO:PBLN_123`처럼 제공처를 포함하고, `contentHash`는 전달된 `text`의 UTF-8 SHA-256입니다.
Qdrant point ID는 이 두 값에서 결정되므로 같은 문서를 반복 처리해도 중복되지 않습니다.

| 요청 | 입력 | 응답 |
|---|---|---|
| `PUT .../batch` | `documents: [{id, contentHash, text}]`, 1~50개, text 최대 12,000자 | `{indexedCount}`: 기존 색인 포함 요청 건수 |
| `POST .../prune` | `sourceCode`, `documents: [{id, contentHash}]`, 최대 20,000개 | `{retainedCount}` |
| `POST .../search` | `query`: 앞뒤 공백 제거 후 1~500자, `eligibleDocuments: [{id, contentHash}]` 최대 20,000개, `limit`: 1~20 | `{query, matches: [{id, contentHash, score}]}` |

```text
Core의 기업마당 동기화: 시작 세대 발급 → 전체 수집·검증
→ HTTP batch API → SupportProgramIndexService
→ Qdrant에서 동일 ID·해시 존재 여부 확인
→ 누락·변경 문서만 OpenAI Embeddings API 호출
→ 차원·유한 숫자·응답 인덱스·건수 검증 → Qdrant UPSERT → Response
→ 모든 batch 성공 후 Core가 최신 시작 세대인지 확인 → 해당 세대만 MySQL에 공개

Core의 별도 색인 스케줄러 (기본 PT1M)
→ 이미 공개된 MySQL 공고 조회 → 같은 batch API로 누락 벡터 복구 (prune 호출 없음)

사용자 검색 → Core가 현재 검색 가능한 MySQL 공고 ID·해시 전달
→ HTTP search API → SupportProgramIndexService
→ 요청한 모든 현재 버전이 색인됐는지 정확한 count 검증
→ OpenAI로 검색어 임베딩
→ Qdrant HasId 필터로 해당 ID·해시만 검색 → 관련 후보 Response
→ 기존 HTTP ranking API → Service → Agent → OpenAI → Response
```

Qdrant는 ID·해시·제공처와 벡터만 보관합니다. 공식 공고 내용과 접수 상태의 기준은 MySQL이며,
닫힌 공고나 미노출 공고를 제외할 책임은 Core에 있습니다. AI Service는 Core가 지정한 현재
ID·해시 목록만 검색하여 이전 버전이 추천 후보로 섞이지 않게 합니다.

색인이 없거나 현재 공고 중 하나라도 아직 색인되지 않았다면 `503`과
`{"detail":{"code":"INDEX_NOT_READY"}}`를 반환합니다. 부분 색인이나 최신 20개 조회로 대체하지
않습니다. Qdrant·OpenAI 장애, 임베딩 검증 실패는 `INDEX_UNAVAILABLE`로 구분합니다. 빈 검색
가능 목록은 외부 호출 없이 빈 `matches`를 반환합니다.

모델·차원·문서 처리 버전은 collection 이름에 반영합니다. 모델 또는 차원을 바꾸면 새 collection에
전체 재색인이 필요하며, 기존 collection은 자동 삭제하지 않습니다. Core는 MySQL의 시작 세대로
카탈로그 공개 순서를 확인하고, 전체 벡터가 준비된 최신 시작 세대만 공개합니다. 이미 공개된 공고의
누락 벡터를 복구하는 별도 스케줄러도 벡터를 삭제하지 않습니다.

내부 `prune` API는 남아 있지만 현재 Core 동기화·복구 경로에서는 호출하지 않습니다. 정확한 현재
ID·해시 필터가 오래된 벡터를 검색에서 제외하므로 새 스냅샷 준비 중인 벡터를 삭제할 필요가 없습니다.
이전 버전·미공개 세대·이전 모델 collection의 저장 공간 정리는 후속 과제입니다. 진행 중인 작업과
검색을 보호하는 보존·삭제 수명주기가 필요하며, 현재 `prune` API 자체가 다중 인스턴스나 동시 실행에
안전한 것은 아닙니다.

이번 단계는 공고 단위 후보 검색입니다. 첨부문서의 문단 검색이나 원문 인용 답변을 제공하는
RAG는 아직 구현하지 않았습니다. 벡터 유사도는 신청 자격 충족률이나 선정 확률이 아닙니다.

## 평가 기준

`govbiz-support-program-ranking-v2`는 다음 배점을 사용합니다.

| 항목 | 배점 |
|---|---:|
| 질문과 공고의 의미적 관련성 | 40 |
| 기업 유형·업종·업력과 지원 대상 적합성 | 25 |
| 지역 적합성 | 15 |
| 신청 시점과 접수 상태 적합성 | 10 |
| 원하는 지원 유형 적합성 | 10 |

LLM에 전달할 평가 지시는 [prompt.py](app/support_program_ranking/prompt.py)에 둡니다.
[models.py](app/support_program_ranking/models.py)는 점수 범위·합계·자격 판정을 검증하고,
[service.py](app/support_program_ranking/service.py)는 최소 추천 기준을 적용합니다. Core도 같은
HTTP 계약을 검증하지만, 지역·카테고리 단어 사전으로 별도 추천 점수를 계산하지 않습니다.

### 추천 반환 최소 기준

Agent는 후보를 빠짐없이 점수화하고 각 후보의 `targetEligibility`·`regionEligibility`를 반드시 반환합니다.
두 값은 `MATCH`(제공된 정보와 일치), `INCOMPATIBLE`(명백한 조건 불일치), `UNKNOWN`(정보 부족) 중 하나입니다.
`UNKNOWN`은 자동 탈락이나 자격 충족 확정을 뜻하지 않습니다. Service는 아래 조건을 모두 충족한 공고만 추천으로
반환합니다.

- `targetEligibility`와 `regionEligibility` 어느 쪽도 `INCOMPATIBLE`이 아님
- `semanticRelevance >= 20`: 40점인 핵심 관련성 항목에서 절반 이상
- `totalScore >= 60`: 전체 100점 기준 60점 이상

자격 불일치는 높은 총점으로 상쇄할 수 없습니다. 지역·접수 상태만 맞는 공고가 추천되는 것을 막기 위해
의미 관련성 조건도 별도로 둡니다. 하나라도 충족하지 못하면 최종 결과에서 제외하며,
적격 공고가 없으면 빈 `rankings`를 정상 `200` 응답으로
반환합니다. 이 값은 실제 검색 평가 데이터가 쌓이면 조정할 초기 정책입니다. Core도 내부 HTTP 응답이 이
정책을 어기지 않았는지 다시 검증하지만, 키워드 사전이나 항목별 가중치를 Kotlin에 구현하지 않습니다.

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
   └→ SupportProgramRankingOutput strict schema로 모든 후보 점수화·지원대상·지역 자격 판정
→ Service가 입력 후보 ID exact set을 재검증
→ 총점 내림차순 정렬
→ 자격 INCOMPATIBLE 제외 + semanticRelevance 20점·totalScore 60점 기준 필터
→ 적격 공고를 resultLimit까지 선택(0개 가능)
→ SupportProgramRankingResponse
→ Core API
```

예를 들어 Core가 두 공고를 보내고 `resultLimit=1`을 지정하면 Agent는 두 후보를 모두 점수화합니다.
Service는 누락·추가·중복 ID를 거부한 뒤 최소 기준을 통과한 공고 중 가장 높은 한 건만 Core에 반환합니다.
두 공고가 모두 기준을 통과하지 못하면 빈 목록을 반환합니다.

## 파일별 책임

```text
app/
├── main.py                         # FastAPI, router, lifespan
├── config.py                       # 모델과 timeout 환경설정
├── bootstrap.py                    # OpenAI client/model/agent/service 조립
├── health/                         # 공통 Health 수직 기능
│   ├── router.py                   # 내부 Health HTTP 경계
│   └── models.py                   # Health 응답 계약
├── support_program_index/          # 공고 임베딩·Qdrant 후보 검색
│   ├── router.py                   # batch/prune/search 내부 HTTP 경계
│   ├── models.py                   # ID·해시·본문·검색 계약 검증
│   └── service.py                  # OpenAI 임베딩·Qdrant 색인과 검색
└── support_program_ranking/         # 지원사업 점수화 수직 기능
    ├── router.py                   # 내부 HTTP 경계
    ├── models.py                   # 요청·출력·응답 Pydantic 계약
    ├── prompt.py                   # 버전된 100점 평가 기준
    ├── agent.py                    # Runner와 OpenAI 실행
    ├── service.py                  # 후보 ID 검증·정렬·최소 기준 필터
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
QDRANT_URL=http://localhost:6333
QDRANT_API_KEY=
QDRANT_TIMEOUT_SECONDS=5
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=1536
EMBEDDING_TIMEOUT_SECONDS=15
```

기본 전체 Agent 제한 `10s`는 모델 호출 제한 `8s`보다 길고 Core의 기본 읽기 제한 `12s`보다 짧습니다.
환경변수를 변경할 때도 이 관계를 유지해야 합니다. 설정 코드가 세 값의 대소 관계를 자동 검증하지는 않습니다.
색인·의미 검색은 별도 Core 읽기 제한을 사용합니다. AI batch/search 전체 제한은 `25s`, prune은
`15s`이고 Core 색인·검색 읽기 제한 기본값은 `30s`입니다. 이 구현은 임베딩을 재시도 없이 호출하며
한 문서 최대 8,191 tokens, 임베딩 API 요청당 최대 32개로 나눕니다. 긴 문서의 뒷부분은 이 단계의 후보
검색에서 제외될 수 있습니다. 토큰 계산은 두 지원 모델 공통 `cl100k_base`를 사용합니다.
Docker 이미지는 빌드 시 토크나이저 파일을 받아 런타임에 별도 다운로드가 필요하지 않습니다.

`OPENAI_EMBEDDING_MODEL`은 `text-embedding-3-small`과 `text-embedding-3-large`를 지원합니다.
차원은 small 최대 1,536, large 최대 3,072로 검증합니다. 기존 순위화 OpenAI client를 공유하며
애플리케이션 종료 시 OpenAI와 Qdrant client를 모두 닫습니다.

## 설치와 실행

Python 지원 범위는 `>=3.11,<3.15`이며 Docker와 CI는 3.11을 사용합니다. 아래 명령은
`backend/ai-service`에서 실행합니다. 색인·의미 검색에는 `QDRANT_URL`에 Qdrant가 실행 중이어야 합니다.

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
QDRANT_TEST_URL=http://localhost:6333 uv run --locked --extra dev python -m pytest tests/support_program_index
uv build
```

테스트는 `agents.testing.ScriptedModel`과 HTTP mock transport를 사용하므로 실제 OpenAI 네트워크를
호출하지 않습니다. 색인 테스트는 기본적으로 실제 Qdrant client의 로컬 메모리 모드를 사용합니다.
`QDRANT_TEST_URL`을 지정하면 같은 테스트를 실제 Qdrant 서버에서 실행하며, 테스트마다 독립적인
collection을 만들고 정리합니다. 현재 운영 collection은 테스트가 사용하지 않습니다.

최신 20개 밖의 관련 공고 조회, 재색인 중복 방지, 현재 해시와 검색 가능 목록 필터, 부분 실패 시
prune 차단, 다른 제공처 보존, 비정상 임베딩 거부를 검증합니다. 테스트 임베딩은 HTTP mock으로
고정한 벡터이므로 실제 한국어 검색 정확도 향상을 측정한 결과로 해석하면 안 됩니다.

Agent 확장 원칙은 [AI Agent 모듈 구조](docs/agent-structure.md)를 참고하세요.
