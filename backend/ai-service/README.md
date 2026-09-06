# GovBiz AI Service

FastAPI, OpenAI 임베딩, Qdrant로 전체 공고에서 관련 후보를 찾고 OpenAI Agents SDK로 후보를
점수화하는 내부 서비스입니다. 브라우저에 직접 공개하지 않고 Spring Core API만 호출합니다.

프로젝트 전체 기술 구성은 [기술 문서](../../docs/technology.md), 기능별 완료 범위와 남은 작업은
[구현 현황](../../docs/implementation-status.md)을 참고하세요. 이 문서는 AI Service 실행·설정·내부 처리 규칙을 다룹니다.
모듈의 책임과 객체 조립은 [아키텍처 README](../../docs/architecture/README.md#ai-service-기능별-모듈과-객체-조립)에 정리했습니다.

## 책임

AI Service가 하는 일:

- 사용자의 자연어 질문과 Core가 검증한 공고 후보를 함께 읽음
- Core가 보낸 공고 검색 문서를 OpenAI로 임베딩하고 Qdrant에 색인
- 현재 MySQL 공고 ID·내용 해시 목록 안에서 의미가 가까운 후보를 최대 20개 검색
- 버전된 100점 평가 기준으로 모든 후보를 점수화
- 지원대상·지역의 명백한 자격 불일치를 제외하고 의미 관련성·총점 최소 기준을 통과한 공고만 0~5개로 반환
- AI가 자격 판정·세부 점수·추천 이유를 strict structured output으로 생성하고 Service가 총점을 합산해 반환
- Core가 준비한 공고 상세 원문 청크를 별도 Qdrant collection에 색인하고, 지정된 현재 청크 안에서 근거를 최대 5개 검색
- 검색된 공고 상세 근거만 사용해 한국어 답변과 인용 청크 ID를 strict structured output으로 반환

AI Service가 하지 않는 일:

- 기업마당 API 호출 또는 MySQL 원본 공고 저장
- 접수 상태 계산과 공식 URL 검증
- 존재하지 않는 공고 추가
- 최종 HTTP 공개 DTO 조립
- 점수 결과 영속화
- 공고 상세 페이지 수집, HTML 정제, 청크 분할 또는 상세 원문 영속화

## 내부 API

```http
GET /internal/v1/health
POST /internal/v1/support-program-rankings/rank
PUT /internal/v1/support-program-index/batch
POST /internal/v1/support-program-index/prune
POST /internal/v1/support-program-index/search
PUT /internal/v1/support-program-evidence/chunks
POST /internal/v1/support-program-evidence/search
POST /internal/v1/support-program-evidence/answers
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

공고 단위 후보 검색의 벡터 유사도는 신청 자격 충족률이나 선정 확률이 아닙니다.

공고와 상세 근거의 임베딩 입력은 [공유 전처리](app/support_program_embedding.py)에서 토큰 상한을 맞춥니다.
토크나이저 준비뿐 아니라 인코딩·잘라내기·재검사 전체를 작업 스레드에서 수행하므로, 큰 색인 배치의 CPU 작업이
HTTP 이벤트 루프를 막지 않습니다. 기존 입력 순서·8,191 토큰 상한·32개 API 배치·내용 해시 규칙은 유지합니다.

## 공고 상세 근거 RAG

상세 화면의 질문 답변은 Core가 공식 공고 상세 원문을 정제·분할한 청크만 사용합니다. 이 기능은 현재
Core가 제공하는 기업마당 상세 공고를 대상으로 하지만, AI Service 내부 식별자는
`sourceCode:sourceProgramId` 형식이므로 다른 제공처에도 같은 계약을 사용할 수 있습니다.

| 요청 | 입력 | 응답 |
|---|---|---|
| `PUT .../chunks` | `chunks: [{id, contentHash, documentId, order, text}]`, 1~50개. `id`·`contentHash`는 소문자 SHA-256, text는 UTF-8 SHA-256과 일치하며 최대 12,000자 | `{indexedCount}` |
| `POST .../search` | `question`: 앞뒤 공백 제거 후 1~500자, `eligibleChunks: [{id, contentHash, documentId, order}]` 1~50개, `limit`: 1~5 | `{question, matches: [{id, contentHash, documentId, order, score}]}` |
| `POST .../answers` | `question`, `chunks: [{id, documentId, order, text}]` 1~5개 | `{answer, answerStatus, citationChunkIds}` |

`documentId`는 최대 320자의 정규 `sourceCode:sourceProgramId`입니다. 첫 번째 콜론만 제공처 코드와 원본
공고 ID를 나누므로 원본 ID에 추가 콜론이 있어도 됩니다. `order`는 0 이상의 정수이고, 같은 요청 안의
청크 ID는 중복될 수 없습니다.

LLM이 긴 해시를 잘못 복사하는 오류를 막기 위해, Agent는 이번 요청 배열의 짧은 `index`만 선택하게 합니다.
모델 전용 결과는 `SupportProgramEvidenceAnswerSelection`의 `citationChunkIndexes`이며, 범위·중복·상태를
검증한 뒤 요청의 원래 64자리 ID로 복원합니다. 원문 `order`와 요청 배열 `index`는 다릅니다.
공개/내부 HTTP 응답은 기존 `citationChunkIds`를 유지하고, 잘못된 선택을 자동 보정하지 않습니다.

```text
Core의 상세 공고 준비
→ 공식 상세 원문 정제·고정 크기 청크화 → 각 청크 ID·text SHA-256 검증
→ PUT /support-program-evidence/chunks
→ SupportProgramEvidenceService
→ 별도 Qdrant collection에서 현재 ID·해시·documentId·order 검증 후 OpenAI Embeddings API 호출·UPSERT

사용자 상세 질문
→ Core가 해당 공고의 현재 eligibleChunks 전달
→ POST /support-program-evidence/search
→ 모든 청크가 현재 collection에 존재하는지 확인 → HasId filter로 지정 청크만 유사도 검색
→ 최대 5개의 match 반환
→ Core가 match의 공식 text만 포함해 POST /support-program-evidence/answers 호출
→ SupportProgramEvidenceAnswerAgent (max_turns=1)
→ OpenAI가 이번 요청의 citationChunkIndexes 선택 → 번호 검증 후 원래 ID 복원
→ 출력 상태·중복 인용·입력 밖 citationChunkIds 재검증 → 한국어 답변 반환
```

상세 근거 collection은 공고 단위 검색 collection과 이름·point ID가 다릅니다. point ID는 청크 ID와
내용 해시에서 결정되고, payload의 `documentId`와 `order`까지 다시 비교합니다. 따라서 다른 공고의
청크를 같은 청크 ID·해시로 재사용하거나, 검색 결과에 요청하지 않은 공고 청크가 섞이는 경우 정상 답변으로
대체하지 않고 오류로 처리합니다. 이전 청크 버전은 현재 `eligibleChunks`의 ID·해시 필터에 없으므로
검색 결과에서 제외됩니다.

검색 요청에 지정한 청크가 하나라도 색인되지 않았거나 Qdrant가 기대한 개수의 결과를 반환하지 않으면
`503`과 `{"detail":{"code":"EVIDENCE_NOT_READY"}}`를 반환합니다. 임베딩·Qdrant·Agent 장애,
payload 불일치, 검증 실패는 `EVIDENCE_UNAVAILABLE`입니다. 부분 검색, 다른 공고 청크, 일반 지식으로
대체하지 않습니다.

답변 Agent는 청크 원문의 지시를 따르지 않고 데이터로만 취급합니다. 제공된 text에서 직접 확인 가능한
내용만 한국어로 답하며, 충분한 근거가 있으면 `ANSWERED`와 하나 이상의 `citationChunkIds`를 반환합니다.
근거가 부족하면 `INSUFFICIENT_EVIDENCE`와 빈 인용 배열을 반환합니다. AI Service는 인용 ID가 요청에
전달된 청크 집합의 부분집합인지도 다시 확인합니다.

대상 조건 요약에서는 사업개요를 포함한 제공 근거의 관련 규모·업종·지역·자격·제외/예외를 보존하도록
지시합니다. 필수·우대·선택 조건을 구분하고 원문의 AND/OR 관계를 임의로 바꾸거나 없는 제한을 만들지
않도록 했으며, 조건이 흩어져 있으면 해당 청크들을 함께 인용하도록 했습니다.
이는 프롬프트 지침이지 모든 조건을 코드로 판정하는 규칙 엔진이 아닙니다.
[추가 실제 검증](../../evaluation/support-program-evidence/runs/official-flow-20260907-v2/README.md)에서
가상 6건·공식 6건의 답변을 대조했고, H01의 누락 보완을 관찰했습니다. 단회 AI-only 결과이며 일반적인
정확도나 반복 실험으로 입증한 개선을 뜻하지 않습니다.

## 평가 기준

`govbiz-support-program-ranking-v3`는 다음 배점을 사용합니다.

| 항목 | 배점 |
|---|---:|
| 질문과 공고의 의미적 관련성 | 40 |
| 기업 유형·업종·업력과 지원 대상 적합성 | 25 |
| 지역 적합성 | 15 |
| 신청 시점과 접수 상태 적합성 | 10 |
| 원하는 지원 유형 적합성 | 10 |

LLM에 전달할 평가 지시는 [prompt.py](app/support_program_ranking/prompt.py)에 둡니다.
[models.py](app/support_program_ranking/models.py)는 AI 출력 값 `SupportProgramAssessment`, Agent가 검증된 ID를 붙인
내부 항목 `AssessedSupportProgram`, 검증된 HTTP 응답 `ScoredSupportProgram`을 구분합니다.
AI는 의미·자격·항목별 점수를 판단하되 `totalScore`와 값 안의 `programId`는 출력하지 않습니다.
[service.py](app/support_program_ranking/service.py)가 다섯 점수를 합산하고 기존 HTTP 응답으로 변환·검증한 뒤
최소 추천 기준을 적용합니다. 지역·업종별 조건을 코드에 나열하거나 AI의 자격 판단을 바꾸는 방식이 아닙니다.
Core도 같은 HTTP 계약을 재검증합니다. HTTP 필드·배점·추천 임계치·`scoringVersion`은 기존 v3와 같습니다.

### 추천 반환 최소 기준

Agent는 후보를 빠짐없이 점수화하고 각 후보의 `targetAssessment`·`regionAssessment`에 `eligibility`와
`score`를 함께 반환합니다. 두 항목의 nested `anyOf` 스키마는 `MATCH`·`UNKNOWN`이면 각각 0~25점·0~15점,
`INCOMPATIBLE`이면 0점만 허용해 부적합 판정과 양수 점수의 모순을 차단합니다.
Service는 이를 기존 HTTP의 `targetEligibility`·`targetFit`, `regionEligibility`·`regionFit`으로 옮깁니다.
Agent에 전달하는 strict output schema의 `rankings`는 배열이 아닌 객체입니다. 요청 후보의 ID 20개가 있다면
그 ID 20개 자체를 모두 `required` 속성 키로 선언하고 `additionalProperties=false`로 다른 키를 금지합니다.
배열 길이만 맞추고 특정 공고를 중복 평가하는 실패를 막기 위한 구조이며, 적합하지 않은 후보도 평가한 뒤
Service에서 제외합니다. Agent가 검증된 키를 `programId`로 붙여 입력 후보 순서의 내부 목록으로 변환합니다.
요청별 Agent 복사본에만 이 스키마를 적용하므로 서로 다른 후보의 요청이 공통 설정을 바꾸지 않습니다.
내부 목록 중복 검증과 Service의 후보 ID 집합 검증도 유지합니다.
자격 값은 `MATCH`(제공된 정보와 일치), `INCOMPATIBLE`(명백한 조건 불일치), `UNKNOWN`(정보 부족) 중 하나입니다.
`UNKNOWN`은 자동 탈락이나 자격 충족 확정을 뜻하지 않습니다. Service는 아래 조건을 모두 충족한 공고만 추천으로
반환합니다.

후보의 `id`와 응답의 `programId`는 `sourceCode:sourceProgramId` 형태의 같은 정규 식별자입니다. 제공처가
다르면 원본 공고 ID가 같아도 서로 다른 후보로 취급하며, 키에서 내부 항목으로 옮길 때 입력값을 그대로 유지합니다.

- `targetEligibility`와 `regionEligibility` 어느 쪽도 `INCOMPATIBLE`이 아님
- `semanticRelevance >= 20`: 40점인 핵심 관련성 항목에서 절반 이상
- `totalScore >= 60`: 전체 100점 기준 60점 이상

자격 불일치는 높은 총점으로 상쇄할 수 없습니다. 지역·접수 상태만 맞는 공고가 추천되는 것을 막기 위해
의미 관련성 조건도 별도로 둡니다. 하나라도 충족하지 못하면 최종 결과에서 제외하며,
적격 공고가 없으면 빈 `rankings`를 정상 `200` 응답으로
반환합니다. 이 값은 실제 검색 평가 데이터가 쌓이면 조정할 초기 정책입니다. Core도 내부 HTTP 응답이 이
정책을 어기지 않았는지 다시 검증하지만, 키워드 사전이나 항목별 가중치를 Kotlin에 구현하지 않습니다.

4단계 2차에서는 `semanticRelevance`를 같은 분야의 키워드보다 **실제 요청한 서비스·비용·결과의 제공 여부**로
판단하도록 프롬프트를 보완했습니다. 행사에 딸린 부대 지원을 독립적인 지원으로 확대하지 않고,
현재 단계와 요청 활동을 구별합니다. 부분적으로 직접 제공하는 지원은 인정하며 모든 질문 단어 일치나
미확인 자격의 자동 탈락을 요구하지 않습니다. 지역·업종·공고 ID별 하드코딩은 추가하지 않았습니다.
공개 점수 계약 v3와 임계값은 유지하고 측정 파일의 프롬프트 SHA-256으로 전후 버전을 구별합니다.
실제 고정 후보 전후 32회 비교에서 dev의 알려진 무관 추천은 6→4건, 관련 추천은 15→16건이었으나
heldout 오추천은 줄지 않았고 평균 API 응답시간은 약 1.82초 늘었습니다.
[측정 조건·결과·재현 방법](../../evaluation/support-program-search/runs/support-program-catalog-20260906-v1/stage4-v2/README.md)에 한계를 함께 기록했습니다.

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
   └→ 요청별 필수 ID 키 rankings 객체의 SupportProgramAssessment 값으로 세부 점수·자격 판정 (총점 없음)
→ Agent가 검증된 ID 키를 붙여 SupportProgramRankingOutput의 AssessedSupportProgram 목록으로 변환
→ Service가 입력 후보 ID exact set을 재검증
→ Service가 다섯 점수 합산 → 기존 HTTP 항목 ScoredSupportProgram으로 변환·검증
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
├── support_program_evidence/        # 상세 원문 근거 검색·답변
│   ├── router.py                   # chunks/search/answers 내부 HTTP 경계
│   ├── models.py                   # 청크·근거 검색·답변 strict 계약
│   ├── service.py                  # 별도 Qdrant collection 색인·현재 청크 검색
│   ├── prompt.py                   # 근거 외 지식 금지 한국어 답변 지시
│   ├── agent.py                    # 단일 typed Agent Runner 실행
│   ├── answer_service.py           # 인용 청크 집합 재검증
│   └── errors.py                   # 안전한 기능 실패
└── support_program_ranking/         # 지원사업 점수화 수직 기능
    ├── router.py                   # 내부 HTTP 경계
    ├── models.py                   # 요청·출력·응답 Pydantic 계약
    ├── prompt.py                   # 버전된 100점 평가 기준
    ├── agent.py                    # Runner와 OpenAI 실행
    ├── service.py                  # 후보 ID 검증·총점 합산·HTTP 변환·정렬·최소 기준 필터
    └── errors.py                   # 안전한 기능 실패
```

의존성 방향은 `router → service → agent → Agents SDK`입니다. 근거 색인은 Agent 없이
`router → service → OpenAI Embeddings/Qdrant`로 처리하고, 답변만 단일 typed Agent를 사용합니다.
`bootstrap.py`만 구체 OpenAI client와 model을 생성하고, 요청마다 Agent를 새로 만들지 않습니다.

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

상세 근거 청크 누락·Qdrant/임베딩 오류·payload 불일치
→ SupportProgramEvidenceError(EVIDENCE_NOT_READY 또는 EVIDENCE_UNAVAILABLE)
→ 상세정보 없는 내부 HTTP 503

상세 답변의 입력 밖 인용 ID·중복 인용·상태와 인용 배열 불일치
→ SupportProgramEvidenceError(EVIDENCE_UNAVAILABLE)
→ 상세정보 없는 내부 HTTP 503
```

사용자 질문, 공고 원문, API key와 OpenAI 원문 오류를 실패 응답에 포함하지 않습니다. Core는 다시
내부 응답의 ID·점수 범위·점수 합계·순서를 검증합니다. 부적합·정보 부족 판정을 `MATCH`로 바꾸거나
유효하지 않은 AI 출력을 정상 결과로 보정하지 않습니다. 재시도·fallback은 추가하지 않습니다.

## 설정

```dotenv
OPENAI_API_KEY=필수
OPENAI_MODEL=gpt-5.6-luna
LLM_MODEL_TIMEOUT_SECONDS=25.0
LLM_RUN_TIMEOUT_SECONDS=30.0
QDRANT_URL=http://localhost:6333
QDRANT_API_KEY=
QDRANT_TIMEOUT_SECONDS=5
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=1536
EMBEDDING_TIMEOUT_SECONDS=15
```

기본 전체 Agent 제한 `30s`는 모델 호출 제한 `25s`보다 길고 Core의 기본 읽기 제한 `35s`보다 짧습니다.
환경변수를 변경할 때도 이 관계를 유지해야 합니다. 설정 코드가 세 값의 대소 관계를 자동 검증하지는 않습니다.
AI의 각 timeout 환경변수는 0초 초과·30초 이하만 허용하며 그 밖의 값은 해당 기본값을 사용합니다.
모델·Agent 제한은 추천 점수화와 원문 근거 답변에 공통 적용됩니다. 기본값은 저장된 실제 추천 호출의
10.874~19.195초 관측에 맞춰 조정했으며, 기본값 변경 자체가 부하·운영 안정성 검증을 뜻하지는 않습니다.
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
QDRANT_TEST_URL=http://localhost:6333 uv run --locked --extra dev python -m pytest tests/support_program_evidence
uv build
```

테스트는 `agents.testing.ScriptedModel`과 HTTP mock transport를 사용하므로 실제 OpenAI 네트워크를
호출하지 않습니다. 색인 테스트는 기본적으로 실제 Qdrant client의 로컬 메모리 모드를 사용합니다.
`QDRANT_TEST_URL`을 지정하면 같은 테스트를 실제 Qdrant 서버에서 실행하며, 테스트마다 독립적인
collection을 만들고 정리합니다. 현재 운영 collection은 테스트가 사용하지 않습니다.

최신 20개 밖의 관련 공고 조회, 재색인 중복 방지, 현재 해시와 검색 가능 목록 필터, 부분 실패 시
prune 차단, 다른 제공처 보존, 비정상 임베딩 거부를 검증합니다. 상세 근거 기능은 현재 청크 전체 색인,
공고 간 청크 ID 재사용 거부, 현재 내용 해시 필터, 입력 밖 Agent 인용 거부, 근거 부족 상태를 검증합니다.
테스트 임베딩은 HTTP mock으로 고정한 벡터이므로 실제 한국어 검색 정확도나 답변 품질을 측정한 결과로
해석하면 안 됩니다.

총점 합산·자격·필수 ID 키 출력 계약 수정 후 전체 테스트 185개가 통과했습니다. 실제 실패 산식의 합산,
부적합 양수 점수 거부, 20개 중 15개 ID만 고유했던 실패 패턴, 요청별 필수 ID 키·누락·추가 거부,
실제 SDK 요청의 strict schema, `UNKNOWN` 보존과
오류의 503 변환을 포함합니다. 이는 코드 회귀 검증이며 실제 검색 품질 평가 완료를 뜻하지 않습니다.

Agent 확장 원칙은 [AI Agent 모듈 구조](docs/agent-structure.md)를 참고하세요.
