# 지원사업 검색·상세 HTTP 계약

GovBiz Web은 공공데이터포털 키나 OpenAI 키를 보유하지 않습니다. 브라우저는 Core API만 호출하고,
Core는 정기 동기화된 MySQL 공고 카탈로그에서 후보를 읽어 AI Service에 점수화를 요청합니다.
전체 구성은 [기술 문서](technology.md), 구현 범위와 후속 과제는 [구현 현황](implementation-status.md)을 참고하세요.

```text
Browser
  → GET /api/v1/support-programs/search
      → Core API
          → MySQL의 현재 노출 공고 조회·접수 상태 필터
          → 현재 공고 ID·내용 해시로 Qdrant 검색 범위 제한
          → 질의 임베딩에 가까운 후보 최대 20개 선택
          → POST /internal/v1/support-program-rankings/rank
              → LLM이 버전된 평가 기준으로 모든 후보 점수화
          → 명백한 지원대상·지역 불일치 제외 + 최소 추천 기준 적용
          → 0~5개를 Core가 검증해 반환
```

## 공개 요청

```http
GET /api/v1/support-programs/search?query=%EC%88%98%EC%B6%9C&acceptingOnly=true
Accept: application/json
```

| Query parameter | 필수 | 설명 |
|---|---|---|
| `query` | 예 | 사용자의 검색 문장. 요청값 최대 500자. 앞뒤 공백 제거 후 비어 있으면 임베딩·Qdrant·LLM을 호출하지 않고 최신 공고 최대 5개를 반환 |
| `acceptingOnly` | 아니요 | `true`이면 Core가 `OPEN` 공고만 AI 후보로 전달. 기본값 `true` |

## 공개 검색 준비 상태

채팅 화면은 검색 요청 전에 다음 endpoint로 현재 공개 스냅샷의 준비 상태를 조회합니다. 이 endpoint는
외부 API·AI Service·Qdrant를 호출하지 않고 MySQL에 기록된 마지막 전체 동기화·색인 준비 결과만 반환합니다.

```http
GET /api/v1/support-programs/readiness
Accept: application/json
```

```json
{
  "searchState": "SEARCHABLE_WITH_SYNC_FAILURE",
  "programCount": 128,
  "indexReady": true,
  "lastSuccessfulSyncAt": "2026-09-05T09:00:00+09:00",
  "lastFailedSyncAt": "2026-09-05T10:00:00+09:00"
}
```

| 필드 | 설명 |
|---|---|
| `searchState` | `PREPARING`, `SEARCHABLE`, `SEARCHABLE_WITH_SYNC_FAILURE`, `UNAVAILABLE` 중 하나 |
| `programCount` | 마지막으로 원자적으로 공개된 기업마당 스냅샷의 공고 수. 현재 제공처가 기업마당 하나이므로 현재 검색 카탈로그 수와 같다. |
| `indexReady` | 이 공개 스냅샷 전체의 마지막 색인 준비가 성공했는지. 실시간 Qdrant Health는 확인하지 않는다. |
| `lastSuccessfulSyncAt` | 마지막 전체 카탈로그 동기화가 공개까지 성공한 시각. 없으면 `null` |
| `lastFailedSyncAt` | 현재 세대의 수집·공개 전 필수 색인 실패를 기록한 마지막 시각. 없으면 `null` |

`SEARCHABLE`은 성공적으로 공개·색인된 스냅샷을 뜻하며 공고 수가 0인 경우도 포함합니다.
`SEARCHABLE_WITH_SYNC_FAILURE`은 이전 스냅샷은 계속 검색 가능하지만 더 최근 동기화가 실패한 경우입니다.
`UNAVAILABLE`은 상태 행은 있으나 해당 공개 스냅샷의 색인 준비가 확인되지 않은 경우입니다.
`PREPARING`은 신뢰할 수 있는 상태 행이 아직 없는 초기 상태입니다. V4 상태 테이블을 도입하기 전부터
존재하던 **비어 있지 않은** 기업마당 공고는 전체 복구 색인이 성공한 뒤에만, 그때 읽은 지문·공고 수를 sentinel
세대 `0`으로 조건부 채택해 `SEARCHABLE`이 될 수 있습니다. 빈 초기 DB·복구 전 legacy 공고는 이 상태를 유지하고,
실제 새 스냅샷의 지문은 bootstrap이 바꾸지 않습니다. 시각은 `Asia/Seoul` 오프셋을 포함한 ISO-8601 문자열입니다.

## 내부 LLM 점수화 요청

Core만 다음 FastAPI endpoint를 호출합니다.

```http
POST /internal/v1/support-program-rankings/rank
Content-Type: application/json

{
  "originalQuery": "서울 AI 창업기업이 받을 사업",
  "scoringVersion": "govbiz-support-program-ranking-v3",
  "resultLimit": 5,
  "candidates": [
    {
      "id": "BIZINFO:PBLN_001",
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

AI Service의 버전 `govbiz-support-program-ranking-v3`는 다음 100점 기준을 사용합니다.

| 평가 항목 | 배점 | 의미 |
|---|---:|---|
| `semanticRelevance` | 40 | 사용자 질문과 공고 목적·내용의 의미적 관련성 |
| `targetFit` | 25 | 기업 유형·업종·업력과 지원 대상의 적합성 |
| `regionFit` | 15 | 사용자 지역과 지원 지역의 적합성 |
| `applicationStatusFit` | 10 | 신청 시점 요구와 공고 접수 상태의 적합성 |
| `supportTypeFit` | 10 | 자금·기술·수출·교육 등 원하는 지원 유형의 적합성 |

LLM은 입력 후보를 정확히 한 번씩 모두 평가합니다. 후보 문장은 데이터일 뿐 지시가 아니며,
후보에 없는 자격·금액·상태를 만들어서는 안 됩니다. v3는 점수와 별도로 모든 후보의 `targetEligibility`와
`regionEligibility`를 필수로 반환합니다. `MATCH`는 제공된 정보와 일치, `INCOMPATIBLE`은 명백한 조건
불일치, `UNKNOWN`은 정보 부족입니다. 하나라도 `INCOMPATIBLE`이면 총점과 관계없이 추천에서 제외합니다.
`UNKNOWN`은 자동 제외하지 않지만 신청 자격 충족을 확정하는 값도 아닙니다. 여기에 `semanticRelevance`
20점 이상과 `totalScore` 60점 이상을 모두 통과한 공고만 점수순으로 Core에 반환합니다.
적격 공고가 없으면 `rankings`는 빈 배열입니다. 자격 판정은 내부 AI 응답의 필수 필드이며 공개 검색 DTO에
새 필드로 노출하지 않습니다.

```json
{
  "originalQuery": "서울 AI 창업기업이 받을 사업",
  "scoringVersion": "govbiz-support-program-ranking-v3",
  "rankings": [
    {
      "programId": "BIZINFO:PBLN_001",
      "semanticRelevance": 38,
      "targetFit": 24,
      "targetEligibility": "MATCH",
      "regionFit": 15,
      "regionEligibility": "MATCH",
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
- `programId`가 전달한 후보의 제공처 포함 식별자와 정확히 일치하고 중복되지 않음
- 세부 점수가 각 배점 범위 안에 있음
- `targetEligibility`·`regionEligibility`가 누락 없이 허용 값이며 어느 쪽도 `INCOMPATIBLE`이 아님
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
      "sourceCode": "BIZINFO",
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
      "sourceUrl": "https://www.bizinfo.go.kr/web/lay1/bbs/S1T122C128/AS/74/view.do?pblancId=PBLN_001",
      "matchedReasons": ["서울 소재 AI 창업기업의 사업화를 지원"],
      "recommendationScore": 95
    }
  ]
}
```

빈 검색어는 AI Service를 호출하지 않으므로 `matchedReasons`는 빈 배열이고 `recommendationScore`는
`null`입니다. 해석할 수 없는 시작·종료일은 각각 `null`입니다. 접수 상태는 파싱된 날짜와 서울 기준
오늘 날짜로 먼저 판단하고, 날짜만으로 판단할 수 없으면 접수 예정·종료·상시 접수 등의 문구를 사용합니다.
따라서 날짜가 `null`이어도 상태가 `OPEN`, `UPCOMING`, `CLOSED`일 수 있으며, 판단 근거가 없을 때 `UNKNOWN`입니다.
적격 공고가 없으면 `programs`는 빈 배열입니다. 원본에 없는 지원금액은 생성하지 않으며 `sourceUrl`로
공식 원문을 확인할 수 있습니다.

## 공개 상세 조회

검색 결과의 `id`는 제공처 안에서의 원본 공고 ID입니다. 제공처가 다르면 같은 `id`가 존재할 수 있으므로,
상세 조회는 검색 응답의 `sourceCode`와 `id`를 각각 전달합니다. 두 값을 `{sourceCode}:{sourceProgramId}`처럼 하나의
문자열로 합치지 않아 URL 인코딩·구분자 충돌 없이 MySQL의 복합 원본 식별자와 정확히 대응합니다.

```http
GET /api/v1/support-programs/detail?sourceCode=BIZINFO&sourceProgramId=PBLN_001
Accept: application/json
```

| Query parameter | 필수 | 설명 |
|---|---|---|
| `sourceCode` | 예 | 제공처 코드. `[A-Z][A-Z0-9_]{0,63}` 형식이며 최대 64자입니다. |
| `sourceProgramId` | 예 | 제공처가 부여한 원본 공고 ID. 공백일 수 없고 최대 255자입니다. 검색 응답의 `id`를 전달합니다. |

성공하면 검색 결과 한 건과 같은 `SupportProgramResponse` 객체를 반환합니다. 상세 조회에는 검색 질의가
없으므로 `matchedReasons`는 빈 배열, `recommendationScore`는 `null`입니다. `is_source_present = FALSE`인
과거 공고와 존재하지 않는 복합 식별자는 모두 다음의 안정적인 404 오류로 처리합니다.

```json
{
  "type": "urn:govbiz:problem:support-program-not-found",
  "title": "Support Program Not Found",
  "status": 404,
  "detail": "The requested support program does not exist or is no longer available.",
  "instance": "/api/v1/support-programs/detail",
  "code": "SUPPORT_PROGRAM_NOT_FOUND"
}
```

## 공개 공식 원문 근거 질문

목록 검색이나 상세 GET은 기업마당 상세 페이지를 수집하지 않습니다. 사용자가 특정 공고에 질문을 제출할 때만
다음 endpoint가 기업마당 공식 HTML 원문을 사용합니다. 현재 지원 제공처는 `BIZINFO`뿐이며, 현재 공개된
공고가 아닌 경우에는 원문 수집 전에 상세 조회와 같은 404를 반환합니다.

```http
POST /api/v1/support-programs/detail/answers
Content-Type: application/json
Accept: application/json

{
  "sourceCode": "BIZINFO",
  "sourceProgramId": "PBLN_001",
  "question": "신청 대상은 누구인가요?"
}
```

| Body field | 필수 | 설명 |
|---|---|---|
| `sourceCode` | 예 | 상세 조회와 같은 제공처 코드. 현재 공식 원문 질문은 `BIZINFO`만 지원 |
| `sourceProgramId` | 예 | 상세 조회와 같은 최대 255자 원본 공고 ID |
| `question` | 예 | 앞뒤 공백을 제거한 질문. 비어 있을 수 없고 최대 500자 |

성공 응답은 다음 형태입니다. 예시의 문장은 형식 설명용이며, 실제 `answer`는 해당 요청에서 검색된 공식 원문
청크에만 근거합니다.

```json
{
  "answer": "공식 원문에 적힌 신청 대상을 안내합니다.",
  "answerStatus": "ANSWERED",
  "citations": [
    {
      "excerpt": "공고 원문에서 답변 근거가 된 발췌문",
      "sourceUrl": "https://www.bizinfo.go.kr/web/lay1/bbs/S1T122C128/AS/74/view.do?pblancId=PBLN_001",
      "chunkOrder": 0
    }
  ]
}
```

| 응답 필드 | 설명 |
|---|---|
| `answer` | 한국어 근거 답변 또는 근거 부족 안내. 최대 1,200자 |
| `answerStatus` | `ANSWERED` 또는 `INSUFFICIENT_EVIDENCE` |
| `citations` | 최대 5개. `excerpt`는 선택한 원문 청크 전체(최대 1,500 UTF-16 코드 단위), `sourceUrl`은 기업마당 공식 원문 URL, `chunkOrder`는 0부터 시작하는 원문 청크 순서 |

`ANSWERED`에는 하나 이상의 `citations`가 반드시 있고, `INSUFFICIENT_EVIDENCE`에는 인용이 없습니다.
Core는 인용 청크가 이 질문에서 실제로 검색된 최대 5개 청크 중 하나인지 확인합니다. 따라서 답변은
전달되지 않은 원문 구간이나 다른 공고의 원문을 인용할 수 없습니다.

원문 캐시가 없거나 같은 URL의 수집 시각이 6시간보다 오래된 경우에만 Core가 공식 HTTPS 기업마당 상세 HTML을
다시 읽습니다. 리디렉션은 각 이동 URL의 공식 HTTPS 호스트와 동일한 `pblancId`를 검증해 최대 3회 따르며,
순환 이동·HTML 이외 응답·공식 호스트가 아닌 URL·비 HTTPS URL·크기 제한 초과 원문은 거부합니다.
HTML은 최대 500KB로 읽고 jsoup `1.23.2`로 파싱합니다. `.support_project_detail`의 `.title_area .title`이
요청 공고 제목과 일치할 때 `.view_cont` 본문만 추출하며, 정규화 본문은 최대 30,000자입니다.
성공적으로 정규화한 텍스트만 MySQL에 저장하고, 최대 50개 결정적 청크를 일반 공고 검색과 분리된 Qdrant evidence
컬렉션에 색인합니다. 이 작업은 공고 동기화와 목록 검색에 포함되지 않습니다. 첨부파일·PDF·OCR·다른 제공처
원문은 현재 지원하지 않습니다.

### 내부 원문 근거 API

Core만 아래 AI Service endpoint를 호출합니다. 브라우저에 공개하지 않으며, 일반 공고 검색의
`/internal/v1/support-program-index/*` 컬렉션·계약과 분리됩니다.

| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `PUT /internal/v1/support-program-evidence/chunks` | `chunks`: `{id, contentHash, documentId, order, text}` 1~50개 | `indexedCount` |
| `POST /internal/v1/support-program-evidence/search` | `question`, `eligibleChunks`: `{id, contentHash, documentId, order}` 1~50개, `limit` 1~5 | `question`, `matches`: 청크 ID·해시·문서 ID·순서·유사도 |
| `POST /internal/v1/support-program-evidence/answers` | `question`, 검색 완료 청크 `{id, documentId, order, text}` 1~5개 | `answer`, `answerStatus`, `citationChunkIds` |

청크 `id`와 `contentHash`는 각각 소문자 SHA-256 64자리이고 `documentId`는
`{sourceCode}:{sourceProgramId}`입니다. AI Service와 Core는 같은 청크 ID·해시·문서 ID·순서가 유지되는지,
검색 결과 수·점수 순서·인용 범위를 다시 검증합니다. 답변 Agent는 전달받은 청크의 텍스트 밖 정보를 사용하지
않도록 지시되며, Core도 `ANSWERED`의 인용 누락과 `INSUFFICIENT_EVIDENCE`의 인용 포함을 계약 위반으로 거부합니다.

## 전체 카탈로그 후보 검색

검색어가 있으면 Core는 MySQL에서 현재 노출된 전체 제공처 공고를 읽어 접수 상태를 적용합니다.
그 전체 허용 목록의 ID·검색 텍스트 해시를 AI Service에 보내고, Qdrant의 의미 검색으로 최대 20개를
선택합니다. 최신순 21번째 이후의 공고도 후보가 될 수 있습니다. 빈 검색어만 최신순 최대 5개를 반환합니다.

내부 색인 API는 다음 세 가지입니다. 공개 브라우저 API가 아니며 FastAPI 내부 포트에서만 제공합니다.

| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `PUT /internal/v1/support-program-index/batch` | `documents`: `{id, contentHash, text}` 최대 50개 | `indexedCount`: 이미 존재하는 버전 포함 확인된 개수 |
| `POST /internal/v1/support-program-index/prune` | `sourceCode`, 현재 `documents`: `{id, contentHash}` | `retainedCount` |
| `POST /internal/v1/support-program-index/search` | `query`, `eligibleDocuments`: `{id, contentHash}`, `limit`(1~20) | `query`, `matches`: `{id, contentHash, score}` |

색인·점수화 경계의 `id`와 `programId`는 `{sourceCode}:{sourceProgramId}`로 구성합니다. 첫 번째 `:` 앞의
`sourceCode`는 `[A-Z][A-Z0-9_]{0,63}` 형태의 안정적인 제공처 코드이고, 뒤의 원본 ID는 전체 문자열로 유지합니다.
원본 ID는 비어 있거나 앞뒤 공백이 있을 수 없고, 최대 255개 Unicode code point이며 Unicode `C` 범주 문자를 포함할 수
없습니다. 첫 번째 구분자 뒤의 추가 `:`는 원본 ID의 일부로 유지합니다.
`contentHash`는 전달한 검색 텍스트의 UTF-8 SHA-256 소문자 64자리이며 공개 응답 ID나 DB `content_hash`와는
별개의 색인 계약입니다. 검색 텍스트는 최대
12,000자로 제한하고 임베딩 입력은 모델 토큰 제한 내에서 잘라 사용합니다. 검색·정리 허용 목록은 최대 20,000개입니다.
오늘 날짜에 따라 달라지는 상태는 텍스트에 고정하지 않고 Core가 조회 시 계산합니다.

기업마당 동기화는 수집 전에 MySQL에서 시작 세대를 발급받고, 전체 수집·검증 및 모든 색인 배치를
성공한 뒤 최신 시작 세대일 때만 새 MySQL 카탈로그를 공개합니다. 공개 transaction은 공고 교체와 함께
공개 세대·카탈로그 지문·공고 수·`indexReady=true`·성공 시각도 기록합니다. 더 최근에 시작한 작업이 있으면
이전 작업의 공개·실패 기록을 모두 건너뜁니다. 현재 세대의 수집 또는 사전 색인 실패는 이전 공개 스냅샷을
바꾸지 않고 실패 시각만 기록합니다. 별도 기본 `PT1M` 스케줄러는 이미 공개된 공고의 누락 벡터만 복구합니다.
복구의 성공·실패는 자신이 읽은 세대·지문·공고 수가 상태 행과 아직 같을 때만 `indexReady`를 바꾸므로,
늦게 끝난 복구가 새 스냅샷 준비 상태를 덮지 못합니다.
두 경로 모두 `prune`을 호출하지 않으며, 정확한 현재 ID·해시 필터가 오래된 벡터를 검색에서 제외합니다.
`prune` API는 남아 있지만 다중 인스턴스·동시 실행의 안전한 정리를 보장하지 않습니다. 이전 버전·미공개
세대의 벡터 정리는 진행 중인 작업과 검색을 보호하는 보존·삭제 수명주기를 마련한 뒤 구현할 후속 과제입니다.

Core는 반환된 ID가 허용 목록에 있고 내용 해시가 일치하는지, 중복·비정상 점수·질의 echo
불일치가 없는지 검증합니다. 현재는 관련성 탈락 기준이 없으므로 결과 개수도 `min(20, 허용 공고 수)`와
정확히 같아야 합니다. Qdrant 유사도 점수는 내부 후보 선정에만 사용하며, 공개 `recommendationScore`는
기존 Agent 평가 점수입니다. 둘 다 선정 확률이 아닙니다.

색인 누락·임베딩·Qdrant 장애는 정상 빈 결과나 최신 목록으로 대체하지 않고 오류로 반환합니다. AI 점수화는
지원대상·지역의 명백한 불일치를 제외하고 의미 관련성 20점·총점 60점 최소 기준을 통과한 공고를
최대 5개 반환하며, 적격 공고가 없을 때의 빈 목록은
정상 성공 응답입니다. 원문 근거 질문은 위의 별도 endpoint이며 이 목록 검색 경로의 동작을 바꾸지 않습니다.
후보·최종 추천 비교를 위한 [검색 평가 자료와 실행 도구](../evaluation/support-program-search/README.md)를
추가했습니다. `evaluation-fixture-export`는 공개 API가 아닌 비웹 실행 프로필이며, 현재 MySQL의 `OPEN`
모든 제공처의 현재 `OPEN` 공고를 운영 색인과 같은 ID·내용 해시·검색 문서로 미라벨 fixture 초안에 기록합니다.
`evaluation-capture`는 사람이 fixture에 질문·정답을 라벨링한 뒤 fixture와 같은 `referenceDate`에서 현재 Search
Service가 만든 후보 최대 20개와 최종 추천 최대 5개의 ID를 기록합니다. 기준 날짜는 실행 시점의 오늘이 아니라
신청 시작·종료일로 접수 상태를 다시 계산하는 날짜이며, fixture와 capture가 다르면 평가기가 거부합니다.
실제 공고·임베딩 모델을 사용한 추천 품질의 **측정 결과**는 사람이
검토한 정답 라벨을 아직 만들지 않았으므로 아직 없습니다.

## 비밀정보와 오류 처리

`DATA_GO_KR_SERVICE_KEY`는 기업마당 동기화를 위해 Core에, `OPENAI_API_KEY`는 AI Service에만
주입합니다. 기업마당 동기화 실패는 사용자 검색 요청의 오류가 아니라 백그라운드 동기화 실패로
처리하며, 이전 MySQL 카탈로그가 있으면 계속 검색합니다. 첫 동기화 전이거나 카탈로그가 비어 있으면
검색은 HTTP 200과 빈 `programs` 배열을 반환합니다. 외부 오류 본문, 사용자 질의나 인증키는 공개 오류에
포함하지 않습니다.

| 상황 | HTTP | `code` |
|---|---:|---|
| `query`가 500자를 초과함 | 400 | `REQUEST_VALIDATION_FAILED` |
| 상세 조회의 `sourceCode`·`sourceProgramId`가 누락·형식·공백·길이 제한을 위반함 | 400 | `REQUEST_VALIDATION_FAILED` |
| 원문 근거 질문의 `sourceCode`·`sourceProgramId`·`question`이 누락·형식·공백·길이 제한을 위반함 | 400 | `REQUEST_VALIDATION_FAILED` |
| 상세 조회 대상이 없거나 현재 제공처 목록에서 사라짐 | 404 | `SUPPORT_PROGRAM_NOT_FOUND` |
| 현재 공고의 제공처가 원문 근거 질문을 지원하지 않음 | 422 | `SUPPORT_PROGRAM_EVIDENCE_NOT_SUPPORTED` |
| 기업마당 공식 원문 수집·검증 실패 | 503 | `SUPPORT_PROGRAM_EVIDENCE_UNAVAILABLE` |
| AI Service의 예상하지 못한 HTTP 응답·응답 계약 위반 | 502 | `AI_SERVICE_UPSTREAM_ERROR` / `AI_SERVICE_INVALID_RESPONSE` |
| AI Service 연결 불가·내부 503 응답 | 503 | `AI_SERVICE_UNAVAILABLE` |
| AI Service 호출 시간 초과·내부 408/504 응답 | 504 | `AI_SERVICE_TIMEOUT` |
| 현재 허용 공고의 색인 미완료 또는 Qdrant·임베딩 실패 | 503(내부 시간 초과 분류에 따라 504) | `AI_SERVICE_UNAVAILABLE` / `AI_SERVICE_TIMEOUT` |

테스트는 가짜 Agent·임베딩과 HTTP mock을 사용하며 실제 OpenAI 호출을 수행하지 않습니다. CI에서는
실제 Qdrant 서버와 MySQL로 저장·검색·미노출·복구 경로를 검증합니다. 이는 실제 임베딩 모델의 검색 품질 측정과 다릅니다.
