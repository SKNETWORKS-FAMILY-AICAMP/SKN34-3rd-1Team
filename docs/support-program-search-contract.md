# 지원사업 검색·추천 HTTP 계약

GovBiz Web은 공공데이터포털 키나 OpenAI 키를 보유하지 않습니다. 브라우저는 Core API만 호출하고,
Core가 기업마당 공고를 검증한 뒤 AI Service에 제한된 후보를 점수화하도록 요청합니다.

```text
Browser
  → GET /api/v1/support-programs/search
      → Core API
          → 기업마당 공고 조회·검증·접수 상태 필터
          → 최신 후보 최대 20개 선택
          → POST /internal/v1/support-program-rankings/rank
              → LLM이 버전된 평가 기준으로 모든 후보 점수화
          → Core가 ID·점수·순서를 검증하고 상위 5개 반환
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
후보에 없는 자격·금액·상태를 만들어서는 안 됩니다. AI Service는 점수순으로 정렬한 상위 결과만
Core에 반환합니다.

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
- 결과가 총점 내림차순이며 최대 5개
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
원본에 없는 지원금액은 생성하지 않으며 `sourceUrl`로 공식 원문을 확인할 수 있습니다.

## 현재 후보 선택 한계

아직 DB 전문검색·벡터 검색이 없기 때문에 Core는 접수 상태를 적용한 뒤 갱신시각 기준 최신 20개를
LLM 후보로 보냅니다. 이 20개 안의 의미 순위는 LLM이 결정하지만, 오래된 관련 공고가 후보에서 빠질
수 있습니다. 실제 DB 수집 단계에서는 SQL/벡터 검색으로 의미 후보를 먼저 찾고 같은 LLM 점수화를
재사용합니다. Kotlin 단어 사전이나 고정 점수표를 다시 추가하지 않습니다.

## 비밀정보와 오류 처리

`DATA_GO_KR_SERVICE_KEY`는 Core에, `OPENAI_API_KEY`는 AI Service에만 주입합니다. 외부 오류 본문,
사용자 질의나 인증키는 공개 오류에 포함하지 않습니다.

| 상황 | HTTP | `code` |
|---|---:|---|
| `query`가 500자를 초과함 | 400 | `REQUEST_VALIDATION_FAILED` |
| 기업마당 인증키 미설정 | 503 | `SUPPORT_PROGRAM_SOURCE_NOT_CONFIGURED` |
| 외부 API 실패·잘못된 응답 | 502 | `SUPPORT_PROGRAM_SOURCE_ERROR` / `SUPPORT_PROGRAM_INVALID_RESPONSE` |
| 외부 API 연결 불가·시간 초과 | 503 / 504 | `SUPPORT_PROGRAM_SOURCE_UNAVAILABLE` / `SUPPORT_PROGRAM_SOURCE_TIMEOUT` |
| AI Service 실패·잘못된 응답 | 502 | `AI_SERVICE_UPSTREAM_ERROR` / `AI_SERVICE_INVALID_RESPONSE` |
| AI Service 연결 불가·시간 초과 | 503 / 504 | `AI_SERVICE_UNAVAILABLE` / `AI_SERVICE_TIMEOUT` |

테스트는 가짜 Agent와 HTTP mock을 사용하며 실제 OpenAI 호출을 수행하지 않습니다.
