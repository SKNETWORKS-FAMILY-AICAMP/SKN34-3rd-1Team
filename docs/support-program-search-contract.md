# 지원사업 검색·추천 HTTP 계약

GovBiz Web은 공공데이터포털 키나 OpenAI 키를 보유하지 않습니다. 브라우저는 Core API만 호출하고,
Core는 정기 동기화된 기업마당 공고 MySQL 카탈로그에서 후보를 읽어 AI Service에 점수화를 요청합니다.

```text
Browser
  → GET /api/v1/support-programs/search
      → Core API
          → MySQL의 현재 노출 기업마당 공고 조회·접수 상태 필터
          → 현재 공고 ID·내용 해시로 Qdrant 검색 범위 제한
          → 질의 임베딩에 가까운 후보 최대 20개 선택
          → POST /internal/v1/support-program-rankings/rank
              → LLM이 버전된 평가 기준으로 모든 후보 점수화
          → 최소 추천 기준을 통과한 0~5개를 Core가 검증해 반환
```

## 공개 요청

```http
GET /api/v1/support-programs/search?query=%EC%88%98%EC%B6%9C&acceptingOnly=true
Accept: application/json
```

| Query parameter | 필수 | 설명 |
|---|---|---|
| `query` | 예 | 사용자의 검색 문장. 최대 500자. 공백이면 LLM을 호출하지 않고 최신 공고를 반환 |
| `acceptingOnly` | 아니요 | `true`이면 Core가 `OPEN` 공고만 AI 후보로 전달. 기본값 `true` |

## 내부 LLM 점수화 요청

Core만 다음 FastAPI endpoint를 호출합니다.

```http
POST /internal/v1/support-program-rankings/rank
Content-Type: application/json

{
  "originalQuery": "서울 AI 창업기업이 받을 사업",
  "scoringVersion": "govbiz-support-program-ranking-v1",
  "resultLimit": 5,
  "candidates": [
    {
      "id": "PBLN_001",
      "title": "서울 AI 창업기업 사업화 지원",
      "organization": "서울경제진흥원",
      "summary": "AI 창업기업의 사업화를 지원합니다.",
      "categories": ["AI", "창업"],
      "regions": ["서울"],
      "targetDescription": "서울 소재 창업기업",
      "applicationPeriod": "상시 접수",
      "status": "OPEN"
    }
  ]
}
```

AI Service의 버전 `govbiz-support-program-ranking-v1`은 다음 100점 기준을 사용합니다.

| 평가 항목 | 배점 | 의미 |
|---|---:|---|
| `semanticRelevance` | 40 | 사용자 질문과 공고 목적·내용의 의미적 관련성 |
| `targetFit` | 25 | 기업 유형·업종·업력과 지원 대상의 적합성 |
| `regionFit` | 15 | 사용자 지역과 지원 지역의 적합성 |
| `applicationStatusFit` | 10 | 신청 시점 요구와 공고 접수 상태의 적합성 |
| `supportTypeFit` | 10 | 자금·기술·수출·교육 등 원하는 지원 유형의 적합성 |

LLM은 입력 후보를 정확히 한 번씩 모두 평가합니다. 후보 문장은 데이터일 뿐 지시가 아니며,
후보에 없는 자격·금액·상태를 만들어서는 안 됩니다. AI Service는 모든 후보를 점수화한 뒤
`semanticRelevance` 20점 이상과 `totalScore` 60점 이상을 모두 통과한 공고만 점수순으로 Core에
반환합니다. 적격 공고가 없으면 `rankings`는 빈 배열입니다.

```json
{
  "originalQuery": "서울 AI 창업기업이 받을 사업",
  "scoringVersion": "govbiz-support-program-ranking-v1",
  "rankings": [
    {
      "programId": "PBLN_001",
      "semanticRelevance": 38,
      "targetFit": 24,
      "regionFit": 15,
      "applicationStatusFit": 10,
      "supportTypeFit": 8,
      "totalScore": 95,
      "recommendationReasons": ["서울 소재 AI 창업기업의 사업화를 지원"]
    }
  ]
}
```

Core는 다음 불변식을 다시 검사합니다.

- `originalQuery`와 `scoringVersion`이 요청과 정확히 일치
- `programId`가 전달한 후보에 존재하고 중복되지 않음
- 세부 점수가 각 배점 범위 안에 있음
- `totalScore`가 다섯 세부 점수의 합과 정확히 일치
- 결과가 총점 내림차순이며 0~5개
- 반환한 공고마다 `semanticRelevance >= 20`, `totalScore >= 60`을 충족
- 추천 이유가 1~3개이고 각 1~120자

하나라도 위반하면 성공 결과를 만들지 않고 `AI_SERVICE_INVALID_RESPONSE`로 거부합니다.

## 공개 성공 응답

```json
{
  "query": "서울 AI 창업기업이 받을 사업",
  "programs": [
    {
      "id": "PBLN_001",
      "title": "서울 AI 창업기업 사업화 지원",
      "organization": "서울경제진흥원",
      "summary": "AI 창업기업의 사업화를 지원합니다.",
      "categories": ["AI", "창업"],
      "regions": ["서울"],
      "targetDescription": "서울 소재 창업기업",
      "applicationPeriod": "상시 접수",
      "applicationStartDate": null,
      "applicationEndDate": null,
      "status": "OPEN",
      "sourceName": "기업마당",
      "sourceUrl": "https://www.bizinfo.go.kr/example",
      "matchedReasons": ["서울 소재 AI 창업기업의 사업화를 지원"],
      "recommendationScore": 95
    }
  ]
}
```

빈 검색어는 LLM을 호출하지 않으므로 `matchedReasons`는 빈 배열이고 `recommendationScore`는
`null`입니다. 날짜를 확실히 해석할 수 없으면 시작·종료일은 `null`, 상태는 `UNKNOWN`으로 유지합니다.
적격 공고가 없으면 `programs`는 빈 배열입니다. 원본에 없는 지원금액은 생성하지 않으며 `sourceUrl`로
공식 원문을 확인할 수 있습니다.

## 전체 카탈로그 후보 검색

검색어가 있으면 Core는 MySQL에서 현재 노출된 전체 기업마당 공고를 읽어 접수 상태를 적용합니다.
그 전체 허용 목록의 ID·검색 텍스트 해시를 AI Service에 보내고, Qdrant의 의미 검색으로 최대 20개를
선택합니다. 최신순 21번째 이후의 공고도 후보가 될 수 있습니다. 빈 검색어만 최신순 최대 5개를 반환합니다.

내부 색인 API는 다음 세 가지입니다. 공개 브라우저 API가 아니며 FastAPI 내부 포트에서만 제공합니다.

| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `PUT /internal/v1/support-program-index/batch` | `documents`: `{id, contentHash, text}` 최대 50개 | `indexedCount`: 이미 존재하는 버전 포함 확인된 개수 |
| `POST /internal/v1/support-program-index/prune` | `sourceCode`, 현재 `documents`: `{id, contentHash}` | `retainedCount` |
| `POST /internal/v1/support-program-index/search` | `query`, `eligibleDocuments`: `{id, contentHash}`, `limit`(1~20) | `query`, `matches`: `{id, contentHash, score}` |

`id`는 내부에서 `BIZINFO:원본ID`로 구성합니다. `contentHash`는 전달한 검색 텍스트의 UTF-8 SHA-256
소문자 64자리이며 공개 응답 ID나 DB `content_hash`와는 별개의 색인 계약입니다. 검색 텍스트는 최대
12,000자로 제한하고 임베딩 입력은 모델 토큰 제한 내에서 잘라 사용합니다. 검색·정리 허용 목록은 최대 20,000개입니다.
오늘 날짜에 따라 달라지는 상태는 텍스트에 고정하지 않고 Core가 조회 시 계산합니다.

Core는 반환된 ID가 허용 목록에 있고 내용 해시가 일치하는지, 중복·비정상 점수·질의 echo
불일치가 없는지 검증합니다. 현재는 관련성 탈락 기준이 없으므로 결과 개수도 `min(20, 허용 공고 수)`와
정확히 같아야 합니다. Qdrant 유사도 점수는 내부 후보 선정에만 사용하며, 공개 `recommendationScore`는
기존 Agent 평가 점수입니다. 둘 다 선정 확률이 아닙니다.

색인 누락·임베딩·Qdrant 장애는 정상 빈 결과나 최신 목록으로 대체하지 않고 오류로 반환합니다. AI 점수화는
의미 관련성 20점·총점 60점 최소 기준을 통과한 공고를 최대 5개 반환하며, 적격 공고가 없을 때의 빈 목록은
정상 성공 응답입니다. 상세 문서 RAG는 이번 범위에 포함하지 않습니다.
후보 검색 비교를 위한 [가상 평가 자료와 실행 도구](../evaluation/support-program-search/README.md)를 추가했습니다.
실제 공고·임베딩 모델을 사용한 추천 품질 측정은 아직 수행하지 않았습니다.

## 비밀정보와 오류 처리

`DATA_GO_KR_SERVICE_KEY`는 기업마당 동기화를 위해 Core에, `OPENAI_API_KEY`는 AI Service에만
주입합니다. 기업마당 동기화 실패는 사용자 검색 요청의 오류가 아니라 백그라운드 동기화 실패로
처리하며, 이전 MySQL 카탈로그가 있으면 계속 검색합니다. 첫 동기화 전이거나 카탈로그가 비어 있으면
검색은 HTTP 200과 빈 `programs` 배열을 반환합니다. 외부 오류 본문, 사용자 질의나 인증키는 공개 오류에
포함하지 않습니다.

| 상황 | HTTP | `code` |
|---|---:|---|
| `query`가 500자를 초과함 | 400 | `REQUEST_VALIDATION_FAILED` |
| AI Service 실패·잘못된 응답 | 502 | `AI_SERVICE_UPSTREAM_ERROR` / `AI_SERVICE_INVALID_RESPONSE` |
| AI Service 연결 불가·시간 초과 | 503 / 504 | `AI_SERVICE_UNAVAILABLE` / `AI_SERVICE_TIMEOUT` |
| 현재 허용 공고의 색인 미완료 또는 Qdrant·임베딩 실패 | 503(내부 시간 초과 분류에 따라 504) | `AI_SERVICE_UNAVAILABLE` / `AI_SERVICE_TIMEOUT` |

테스트는 가짜 Agent·임베딩과 HTTP mock을 사용하며 실제 OpenAI 호출을 수행하지 않습니다. CI에서는
실제 Qdrant 서버와 MySQL로 저장·검색·미노출·복구 경로를 검증합니다. 이는 실제 임베딩 모델의 검색 품질 측정과 다릅니다.
