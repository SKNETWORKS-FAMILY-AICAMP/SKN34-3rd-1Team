# GovBiz 서비스 호출·데이터 흐름

[문서 목록](README.md) · [아키텍처 README](architecture/README.md)

현재 production 코드의 서비스 경계와 실행 흐름을 설명합니다. 계층·DI·디자인 패턴은
[아키텍처 README](architecture/README.md), 기술·버전은 [기술 구성](technology.md),
완료 기능과 남은 제약은 [구현 현황](implementation-status.md), 환경 설정은
[인프라 README](../infrastructure/README.md)를 참고하세요.

## 서비스 경계

```text
브라우저 → React Web → Core API
                       ├→ MySQL: 현재 공개 공고 카탈로그·공고별 공식 원문
                       ├→ 공공데이터포털: 기업마당 전체 공고 수집
                       ├→ 기업마당 공식 HTTPS 상세 페이지: 명시적 원문 질문 시 HTML 수집
                       └→ AI Service
                           ├→ OpenAI: 문서·질의 임베딩, 후보 점수화·근거 답변
                           └→ Qdrant: 공고 검색·원문 근거 청크의 분리된 벡터 컬렉션
```

Core API는 공개 HTTP 계약, 기업마당 수집, MySQL 접근과 접수 상태 계산을 소유합니다. AI Service는
Core가 전달한 공고 문서·원문 청크의 색인·검색·점수화·근거 답변을 담당하며 MySQL에 직접 접근하지 않습니다.

브라우저는 Core API의 `/api`만 호출합니다. Compose에서 Vite는 `/api`를 `core-api:8080`으로 프록시하며,
AI Service는 호스트에 포트를 게시하지 않습니다. MySQL·Qdrant·Core API·Web의 개발용 포트는
`127.0.0.1`에 바인딩합니다. 기업마당 키는 Core API에, OpenAI 키는 AI Service에만 주입합니다.
이는 개발 환경의 서비스 배치이며 운영 인증·접근 제어가 구현됐다는 의미는 아닙니다.

## 검색·상세 조회·원문 근거 질문

공개 검색·근거 질문은 입력 검증 뒤 Controller에서 `SupportProgramRequestAdmissionService`를 거쳐
기존 업무 Service를 실행합니다. 하나의 Bean이 접속 주소별/전체 최근 60초 및 동시 작업 한도를 공유하며,
거절 시 하위 Service를 호출하지 않고 429 또는 503을 반환합니다. 잠금은 입장 판단·카운터 갱신에만 사용하고
외부 호출 중에는 유지하지 않습니다. `finally`로 정상·예외 종료 모두 동시 슬롯을 반환합니다.
준비 상태·상세 GET·Health·백그라운드 동기화·비웹 평가는 이 공개 제한과 분리합니다.
전달 헤더를 기본 신뢰하지 않으며 Compose 프록시/NAT 뒤에서는 주소별 한도를 공유할 수 있습니다.
단일 프로세스 보호이며 분산 한도·전역 비용 상한은 아닙니다. [설정·경계·검증](support-program-request-limits.md)을 참고하세요.

```text
GET /api/v1/support-programs/search
  → SupportProgramController
    → SupportProgramSearchService
      → SupportProgramRepository → MyBatis Mapper → Mapper XML → MySQL
        → 빈 검색: findPublishedPresent로 공개 세대·지문이 있는 DB 공고 선택
        → 자연어 검색: findSearchablePresent로 위 조건에 index_ready=true 추가
      → 접수 상태 계산·필터
      ├→ 빈 검색어: 최신순 최대 5개 반환
      └→ 검색어 있음:
          AiSupportProgramRetrievalFacade → AiSupportProgramIndexClient
            → AI Service → 질의 임베딩 → Qdrant 후보 최대 20개
            → Core: 전체 적격 공고의 키워드 상위 20개와 RRF 결합 → 후보 최대 20개
          AiSupportProgramRankingFacade → HttpAiSupportProgramRankingClient
            → AI Service → 단일 Agent → OpenAI 점수화
          Core의 응답 검증 → 최종 추천 0~5개
```

1. Repository는 `is_source_present = TRUE`이고 제공처의 공개 세대·지문이 있는 공고를 읽습니다. 자연어 검색은
   `index_ready = TRUE`도 요구하며, 빈 검색은 공개 이후 색인 장애와 무관하게 기존 DB 목록을 유지합니다.
   저장된 신청 기간과 서울 날짜로 접수 상태를 다시 계산하고 `acceptingOnly=true`이면 `OPEN`만 남깁니다.
2. 검색어는 앞뒤 공백을 제거합니다. 검색어가 비어 있으면 `source_sort_timestamp` 내림차순·제공처 코드·원본 ID
   오름차순의 최대 5개를 AI 없이 반환합니다. 자연어 검색의 빈 후보가 전체 색인 장애 때문이면 503으로 알리고,
   최초 빈 DB나 준비된 제공처의 정상 0건이면 빈 목록을 반환합니다. 미공개 제공처 데이터는 노출하지 않습니다.
3. 비어 있지 않은 질의는 대상 공고 전체의 정확한 ID·내용 해시를 AI Service에 전달합니다.
   최신 공고 20개를 먼저 자르지 않습니다. Qdrant가 반환해야 할 개수는 `min(대상 공고 수, 20)`입니다.
4. `AiSupportProgramRetrievalFacade`는 의미 검색 응답의 질의·ID·해시·중복·유한 점수·내림차순·개수를
   검증한 뒤 전체 적격 공고의 동일 색인 본문에서 키워드 상위 20개를 구합니다. 질의와 본문을 NFC로
   정규화하고 `Locale.ROOT` 소문자 변환 후 `[a-z0-9가-힣]+` 토큰 집합의 교집합 수를 내림차순으로
   정렬합니다. 일치 토큰이 없는 공고는 제외하고, 동점은 정렬 시각 내림차순·제공처 포함 ID 오름차순입니다.
   의미 검색과 키워드의 1부터 시작하는 순위를 동일 가중치 RRF `1 / (60 + 순위)`로 합산하고,
   동점은 의미 검색 순위·제공처 포함 ID 오름차순으로 정렬해 중복 없는 최대 20개를 점수화에 전달합니다.
   의미 검색 응답이 실패하거나 잘못됐으면 오류를 반환합니다. 키워드 일치가 없으면 의미 검색 순서를 유지합니다.
5. AI는 모든 후보의 의미·자격·세부 점수를 `SupportProgramAssessment`로 판단하고 총점은 출력하지 않습니다.
   요청별 strict schema의 `rankings`는 후보 ID 자체를 필수 키로 선언한 객체이며 다른 키는 금지합니다.
   Agent는 검증된 키를 ID로 붙여 기존 내부 `AssessedSupportProgram` 목록으로 변환합니다.
   AI Service가 다섯 점수를 합산해 기존 HTTP 항목 `ScoredSupportProgram`으로 변환·검증한 후
   총점순으로 정렬하고 추천 기준을 적용합니다.
   Core도 최종 응답의 후보 ID·질의·계약 버전·점수·순서·추천 이유를 재검증해 공개 응답으로 변환합니다.

```text
GET /api/v1/support-programs/readiness
  → SupportProgramController → SupportProgramSearchReadinessService
  → SupportProgramRepository → MyBatis Mapper → Mapper XML → MySQL
  → 제공처별 공고 수·색인 준비·최근 동기화 시각과 전체 검색 범위를 반환
```

`support_program_sync_status`는 제공처별 공개 스냅샷의 세대·검색 문서 지문·공고 수와 색인 준비 상태를
최근 카탈로그 동기화 결과와 분리해 보관합니다. 제공처별 `indexReady=true`이면 공고 수가 0이어도 검색 가능합니다.
색인이 준비된 이전 스냅샷을 유지한 채 새 수집·사전 색인이 실패하면 `SEARCHABLE_WITH_SYNC_FAILURE`이며,
색인 준비가 확인되지 않은 공개/실패 상태는 `UNAVAILABLE`입니다.
공고 없는 초기 준비는 `PREPARING`, 상태 행 없이 공고가 남아 있는 복구 전 legacy는 `UNAVAILABLE`입니다.
시각은 서울 시계를 사용해 저장하고 API에서는 `+09:00` 오프셋이 포함된 ISO-8601 문자열로 반환합니다.

`SupportProgramSourceReadinessResult → SupportProgramSourceReadinessResponse` 변환으로 필수 `sources`와
전체 검색 범위를 제공합니다. 일부만 준비되면 `SEARCHABLE_WITH_PARTIAL_SOURCES`이며 외부 호출은 없습니다.
상태가 아직 없는 현재 공고의 제공처도 준비 미확인으로 표시하고 초기 빈 DB에서는 구성된 기업마당만 표시합니다.
세부 정책과 검증은 [다중 제공처 준비](support-program-multi-source-preparation.md)에 있습니다.

점수화 계약은 `govbiz-support-program-ranking-v3`입니다. 의미 관련성 20/40점 이상과 총점 60/100점 이상을
충족해야 하며, `targetEligibility` 또는 `regionEligibility`가 `INCOMPATIBLE`이면 추천에서 제외합니다.
LLM 출력은 `targetAssessment`·`regionAssessment` 안에 `eligibility`와 `score`를 함께 묶습니다.
nested `anyOf` 스키마가 `INCOMPATIBLE`의 점수를 0으로 제한하고, `MATCH`·`UNKNOWN`에는 기존 항목별
범위(대상 0~25점·지역 0~15점)를 적용합니다. Service는 판단을 바꾸지 않고 기존 HTTP 필드로 옮깁니다.
총점 합산은 지역·업종별 판단 규칙을 코드에 추가하는 것이 아니며 배점·추천 정책·HTTP 계약·버전은 유지합니다.
`UNKNOWN`은 정보 부족을 뜻해 자동 제외하지 않지만 신청 자격을 확인했다는 의미도 아닙니다.
AI Service가 부적격 항목을 최종 응답에 넣으면 Core는 이를 응답 계약 위반으로 거부합니다.
결과가 0개인 것은 정상일 수 있으며 관련 없는 공고로 5개를 채우지 않습니다.

검색 카드의 상세 링크는 `/support-programs/detail?sourceCode={sourceCode}&sourceProgramId={id}`로
이동합니다. 상세 화면은 해당 URL의 식별자로 공개 상세 API를 다시 호출하므로 직접 진입·새로고침이 가능합니다.

```text
GET /api/v1/support-programs/detail
  → SupportProgramController → SupportProgramDetailService
  → SupportProgramRepository → MyBatis Mapper → Mapper XML → MySQL
```

상세 GET은 외부 API·AI를 호출하지 않습니다. 현재 노출된 복합 식별자 행만 반환하며 없는·미노출 행은
`SUPPORT_PROGRAM_NOT_FOUND`(404)입니다. 검색 문맥이 없으므로 추천 이유는 빈 배열, 점수는 `null`입니다.
공개 입력 제한과 JSON·오류 코드의 전체 계약은 [지원사업 API 계약](support-program-search-contract.md)에 있습니다.

### 공고별 공식 원문 근거 질문

```text
POST /api/v1/support-programs/detail/answers
  → SupportProgramController → SupportProgramEvidenceService
  → SupportProgramDetailService → 현재 공개 공고 확인
  → SupportProgramRepository → MySQL의 공고별 원문 캐시 조회
  → 캐시가 없거나 URL이 바뀌었거나 6시간이 지남:
      BizInfoSupportProgramSourceDocumentFacade → BizInfoSourceDocumentClient
        → 기업마당 공식 HTTPS 상세 페이지의 HTML만 수집·읽기 가능한 텍스트로 정규화
      → SupportProgramRepository → MySQL 원문 UPSERT
  → SupportProgramEvidenceChunker → 결정적 청크 최대 50개
  → AiSupportProgramEvidenceFacade → AI Service
      → 별도 Qdrant evidence 컬렉션에 청크 색인
      → 질문과 가까운 청크 최대 5개 검색
      → 단일 typed Agent → OpenAI 근거 답변·짧은 인용 번호 선택
      → Agent가 검증한 번호를 요청의 원래 청크 ID로 복원
  → Core가 청크·인용을 검증 → 답변과 원문 발췌·URL 반환
```

이 경로는 `BIZINFO` 현재 공고에만 제공됩니다. 기업마당 공식 `https://bizinfo.go.kr` 및 그 하위 도메인의
상세 HTML만 허용하며, URL에는 요청한 원본 공고 ID와 같은 `pblancId`가 정확히 하나 있어야 합니다.
자동 리디렉션은 끄고 각 이동 URL을 같은 조건으로 검증해 최대 3회 따릅니다. 따라서 기존 상세 URL에서
`/sii/siia/selectSIIA200Detail.do?pblancId=...`로 이동할 수 있으며, 외부 호스트·비 HTTPS·다른 공고 ID·순환 이동은
거부합니다. 원문 HTML은 최대 500KB로 읽고 jsoup `1.23.2`로 파싱합니다. `.support_project_detail` 안의
`.title_area .title`이 요청 공고 제목과 일치해야 하며, `.view_cont` 본문만 추출해 메뉴·다른 공고·푸터를
제외합니다. 정규화 본문은 최대 30,000자로 제한합니다. 공식 원문을 성공적으로 읽고 검증한 뒤에만
짧은 DB transaction으로 저장하므로 원문 수집·AI 오류가 공고 동기화·목록 검색·상세 GET을 바꾸지 않습니다.
현재 공고의 제공처가 `BIZINFO`가 아니면 422 `SUPPORT_PROGRAM_EVIDENCE_NOT_SUPPORTED`, 공식 원문 수집·검증에
실패하면 503 `SUPPORT_PROGRAM_EVIDENCE_UNAVAILABLE`을 반환합니다. AI 근거 색인·검색·답변의 연결·시간 초과·계약
오류는 일반 AI 경계와 같은 502/503/504 분류를 사용합니다.

Frontend는 `KSTARTUP`을 포함한 비 `BIZINFO` 상세에서 질문 입력을 숨기고 미지원 안내와 원문 링크를
표시하며, ViewModel에서도 질문 전송을 차단합니다. K-Startup 공식 URL 표시 허용은 원문 수집·RAG 지원과 별개입니다.

원문은 제목·공식 URL을 포함한 텍스트로 저장하며, 같은 원문은 요청마다 다시 수집하지 않고 최대 6시간
재사용합니다. 청크는 내용·원문 해시·순서에서 결정적으로 만들며 각 청크는 최대 1,500 UTF-16 코드 단위입니다. AI Service는
일반 공고 검색 컬렉션과 다른 Qdrant 컬렉션만 사용하고, 요청 공고의 청크 집합으로 검색 범위를 제한합니다.
답변이 충분한 근거를 찾지 못하면 `INSUFFICIENT_EVIDENCE`와 인용 없는 안내를 반환합니다. `ANSWERED`에는
검색된 청크의 인용이 하나 이상 있어야 하며 Core는 인용이 전달한 청크 밖을 가리키면 응답을 거부합니다.
인용 발췌문은 선택한 청크 전체를 반환해 청크 뒤쪽의 답변 근거도 화면에서 확인할 수 있습니다.

모델에는 64자리 해시를 복사시키지 않습니다. Agent가 이번 요청 배열에 `index`(0~4)를 붙여 전달하고
`SupportProgramEvidenceAnswerSelection.citationChunkIndexes`를 검증한 뒤 원래 `citationChunkIds`로 변환합니다.
`index`는 원문의 `order`와 다르며 요청마다 새로 부여합니다. 범위 초과·중복·상태 모순을 보정하거나 무시하지
않고 기존 오류로 반환합니다. Core와 공개 HTTP의 인용 계약은 변경하지 않습니다.

첨부파일·PDF·OCR·다른 제공처 원문 수집은 이 흐름에 포함하지 않습니다. 공고 목록 검색의 의미·키워드 후보 선정·AI
점수화와도 별도 사용 사례이므로, 원문 질문을 하지 않으면 기업마당 상세 HTML을 수집하거나 evidence 컬렉션을
사용하지 않습니다.

## 검색 품질 평가 fixture 내보내기와 캡처

```text
evaluation-fixture-export profile (비웹 실행)
  → findSearchablePresent로 준비된 제공처의 공개 공고 조회 → 지정한 referenceDate 기준 OPEN 공고만 선정
  → SupportProgramIndexDocumentMapper와 같은 ID·내용 해시·검색 문서 생성
  → 기준 날짜·전체 적격 카탈로그와 cases: []인 미라벨 fixture 초안을 원자적으로 JSON 기록
  → 질문을 고정하고 선택한 AI-only·혼합·사람 검토 방식으로 참조 라벨 확정

evaluation-capture profile (비웹 실행)
  → 질문 묶음 JSON 검증
  → 같은 referenceDate로 SupportProgramSearchService.searchWithTrace
      → 준비된 제공처의 현재 공고 → 기준 날짜의 적격 공고 → 의미·키워드 RRF 후보 최대 20개 → AI 최종 추천 최대 5개
  → 기준 날짜·질문별 후보 ID·최종 ID·카탈로그 지문을 원자적으로 JSON 기록
  → 별도 Python 평가 도구가 선택한 판정 출처의 fixture와 대조
```

두 경로 모두 공개 Controller나 디버그 HTTP endpoint가 아닙니다. `evaluation-fixture-export`는 자신의 웹 서버와
두 동기화 스케줄러를 끄고 공고 데이터는 MySQL에서만 조회합니다. 따라서 Qdrant·AI Service·OpenAI를 호출하지
않으며, 전체 카탈로그 검증이 끝난 뒤에만 출력 파일을 원자적으로 교체합니다. `referenceDate`는 실행 시각의
오늘이 아니라 신청 시작·종료일로 접수 상태를 다시 계산하는 평가 기준이며 생성 fixture에 함께 기록됩니다.
생성된 `cases: []`에는 고정 질문의 `id`·`query`·`split`과 선택한 판정 방식의 `relevantIds`를 채웁니다.
현재 공유 실행은 AI-only이며 사람 검토 정답으로 표시하지 않습니다. 질문 묶음의 `name`과 각
`id`·`query`·`split`은 fixture의 `cases`와 같은 순서·내용으로 맞춥니다.

`evaluation-capture` profile도 자신의 웹 서버와 두 동기화 스케줄러를 끄며, 모든 질문이 성공하고 캡처 중
카탈로그 지문이 같을 때만 출력 파일을 교체합니다. capture는 fixture와 같은 `referenceDate`를 명시해 같은
접수 상태 집합을 검색하며, Python 평가기는 두 날짜가 다르면 평가를 거부합니다. 별도 Core API 인스턴스가 카탈로그를 갱신한 경우에는 지문
변화로 결과 파일 기록을 거부합니다. 후보 ID는 `sourceCode:sourceProgramId` 형태이며, 첫 번째 `:` 앞의
`[A-Z][A-Z0-9_]{0,63}` 제공처 코드와 뒤의 원본 ID를 함께 사용합니다. 같은 Search Service가 만든
후보·최종 결과를 기록하므로 평가 코드가 운영 검색 흐름을 별도로 재현하지 않습니다. 실제 AI Service를
호출할 수 있으므로 기본 실행·CI에는 포함하지 않습니다. fixture 내보내기·라벨·캡처·평가 실행 규칙은
[검색 평가 자료](../evaluation/support-program-search/README.md)를 따릅니다.
새 fixture/capture는 같은 준비된 제공처 범위를 사용합니다. 기존 고정 평가 스냅샷·원표·캡처는 당시의
입력과 실행 결과로 보존하며, 이번 상태 필터를 소급 적용하거나 과거 지표를 다시 해석하지 않습니다.

## 기업마당 동기화와 공개 순서

```text
BizInfoSupportProgramCatalogSyncScheduler (기본: 최초 PT0S, 완료 후 PT6H)
  → Repository: 수집 시작 세대 발급 [짧은 DB transaction]
  → BizInfoSupportProgramCatalogFacade → BizInfoClient: 전체 페이지 수집·검증
  → BizInfoProgramMapper: 필수 필드 검증·공고 정규화
  → SupportProgramIndexSyncService.indexSnapshot: 모든 공고의 벡터 준비
      → AiSupportProgramIndexClient → AI Service → OpenAI 임베딩 → Qdrant
  → Repository: 최신 시작 세대일 때만 MySQL에 공개 [짧은 DB transaction]
      → BIZINFO 기존 행 미노출 처리 + 수집 목록 UPSERT
      → 공개 세대·카탈로그 지문·공고 수·indexReady·성공 시각 기록
```

공공데이터포털의 전체 건수, 페이지 번호·크기, 페이지별 항목 수와 실제 수집 수가 일치해야 합니다.
기업마당 수집·응답 검증·필수 필드 정규화 중 하나라도 실패하면 카탈로그를 바꾸지 않습니다.
현재 Client는 페이지당 1,000건을 요청하며 최대 20페이지·20,000건으로 제한합니다.

색인은 전체 공고를 16개씩 나누어 요청합니다. AI Service는 동일 ID·해시의 벡터가 이미 있으면 재사용하고
없는 버전만 생성합니다. 모든 배치의 성공과 처리 건수를 확인한 후에만 DB 공개를 시도합니다.
색인 도중 실패하면 현재 세대일 때만 실패 시각을 기록하고 기존 공개 카탈로그·그 스냅샷의 색인 준비 상태를
유지합니다. 이미 준비된 벡터는 재시도 시 재사용할 수 있습니다. 더 최신 세대가 시작되면 이전 세대의
성공·실패 기록 모두 무시합니다.

MySQL의 `support_program_sync_generation`은 제공처별 최신 **시작** 세대를 관리합니다. 이전 실행이 늦게
끝나도 더 최근에 시작된 작업이 있으면 공개를 건너뜁니다. 후발 작업이 실패하더라도 이전 세대가 다시
공개 권한을 얻지는 않으며 마지막으로 공개된 카탈로그를 다음 성공까지 유지합니다.

시작 세대 발급과 공개는 각각 행 잠금을 사용하는 짧은 DB transaction입니다. 공개 transaction 안의
미노출 처리·UPSERT·상태 행 성공 기록 중 하나라도 실패하면 전체를 rollback합니다. 외부 HTTP 수집·색인은 DB
transaction 밖에서 실행하며, 수집 실패를 이유로 기존 행을 삭제하거나 다른 제공처 데이터를 변경하지 않습니다.
동기화 Service가 수집·사전 색인·공개 과정의 RuntimeException을 한 번 기록한 뒤 Scheduler가 다음 주기에 계속 실행합니다.

## 벡터 정합성과 복구

`SupportProgramIndexDocumentMapper`가 제목·기관·지원 대상·분야·지역·신청 기간 원문·요약으로 검색 문서를
구성합니다. 제어·형식 문자는 개행·탭을 제외하고 정리하며 Unicode 코드 포인트 기준 최대 12,000자로
제한합니다. UTF-8 문서의 SHA-256이 내용 해시이고, 내부 문서 ID는
`{sourceCode}:{sourceProgramId}`입니다.

AI Service는 문서 ID·내용 해시에서 Qdrant point ID를 결정하며, 임베딩 모델·차원·색인 규격에 따라
컬렉션을 분리합니다. 현재 DB의 정확한 문서 버전에 해당하는 point ID만 검색하도록 필터링하므로
이전 버전·미노출 공고·아직 공개하지 않은 세대의 벡터는 결과에 섞이지 않습니다.

```text
SupportProgramIndexSyncScheduler (기본: 최초 PT0S, 완료 후 PT1M)
  → SupportProgramIndexSyncService.repair
  → Repository: 현재 MySQL 공개 공고 목록·제공처별 상태 조회
  → 제공처별로 AI Service: 해당 버전의 누락 벡터 생성·저장
  → 해당 제공처 상태의 공개 세대·지문·공고 수가 읽은 스냅샷과 같을 때만 indexReady 갱신
  → 한 제공처 실패 이후에도 다른 제공처를 처리하고, 완료 후 실패를 오류로 전달
```

복구는 제공처 수집과 별도 단일 스레드에서 실행합니다. `SUPPORT_PROGRAM_INDEX_ENABLED=false`는
이 복구 작업만 끄며, 새 카탈로그 공개 전의 필수 색인은 끄지 않습니다.
공고가 0개인 공개 스냅샷도 제공처 상태를 기준으로 처리하고, legacy 채택도 제공처별로 수행합니다.

복구 색인이 실패하면 자신이 읽은 스냅샷과 상태 행이 여전히 같을 때만 `indexReady=false`로 바꾸며,
최근 카탈로그 동기화 성공·실패 기록은 바꾸지 않습니다. 복구가 늦게 끝난 동안 새 스냅샷이 공개되면 조건부
UPDATE가 0행이 되어 새 스냅샷 상태를 건드리지 않습니다. 이 상태는 마지막 전체 색인 준비 결과이며 실시간
Qdrant Health를 뜻하지는 않습니다.

공개 준비와 복구는 모두 `prune`을 호출하지 않습니다. 이전 스냅샷 기준의 삭제가 공개 준비 중인 새 벡터를
지우는 상황을 피하기 위해 현재 자동 삭제를 연결하지 않았습니다. 내부 `prune` API는 존재하지만,
안전한 보존·삭제 수명주기와 전체 다중 인스턴스 운영 검증은 후속 과제입니다.

검색 대상의 벡터가 하나라도 없거나 Qdrant·AI Service가 실패하면 일부 후보만으로 성공하지 않고 오류를
반환합니다. 별도 복구 작업의 성공 후 다시 검색할 수 있습니다. 빈 검색어 목록과 상세는 AI에 의존하지 않습니다.
조회 때 변하는 접수 상태는 벡터에 고정하지 않으며, DB의 `content_hash` 컬럼도 영속 색인 완료 기록으로
사용하지 않습니다. 현재 검색은 매번 전체 대상 공고와 ID·해시 목록을 읽고 전송하므로 대규모 데이터 성능은
별도 개선이 필요합니다.

## 데이터와 접수 상태

MySQL의 `support_program`은 `(source_code, source_program_id)` 고유키로 원본 공고를 식별합니다.
`categories`·`regions`는 JSON, 신청 기간은 원문과 nullable 날짜로 저장합니다. 원본에서 사라진 공고는
삭제하지 않고 `is_source_present=false`로 바꿉니다. UPSERT는 대소문자만 바뀐 원본 ID도 최신 표기로
갱신하여 MySQL과 벡터 식별자를 맞춥니다. 고유키 비교는 MySQL의 `utf8mb4_0900_ai_ci` collation을 따릅니다.

`support_program_source_document`는 원문 근거 답변에만 쓰는 공고별 공식 HTML 정규화 텍스트·원문 URL·해시·수집
시각을 같은 복합 식별자로 저장하고 공고를 FK로 참조합니다. 조회 시 공고의 공개 상태를 확인하므로 미노출 공고에는
원문 질문을 제공하지 않습니다. 이 테이블은 정기 목록 동기화에서 채우지 않고 명시적 원문 질문의 수집·검증이
성공했을 때 UPSERT합니다.

`support_program_sync_status`는 제공처별 스냅샷의 공개 세대·지문·공고 수를 기록합니다.
V4 적용 전부터 있던 공고는 과거 공개 세대를 복원하지 않습니다. 대신 해당 제공처의 현재 공개 공고가 1건 이상인 경우에
한해, 전체 복구 색인이 성공한 뒤 그때 읽은 지문·공고 수를 sentinel 세대 `0`으로 조건부 채택할 수 있습니다.
빈 초기 DB는 `PREPARING`, 복구 전 legacy 공고는 `UNAVAILABLE`이며, 실제 지문이 있는 새 스냅샷은 bootstrap이 덮어쓰지 않습니다.

접수 상태는 `SupportProgramStatusResolver`가 읽을 때 계산합니다. 파싱된 시작일 전은 `UPCOMING`,
종료일 이후는 `CLOSED`, 시작일·종료일 범위 안은 `OPEN`입니다. 날짜 경계는 포함합니다.
날짜만으로 결정되지 않은 경우 예정 표현, 남아 있는 종료일, 명시적 종료 표현, 상시 접수 표현 등의
규칙을 순서대로 적용하고 판단 근거가 없으면 `UNKNOWN`을 유지합니다. 따라서 `접수 종료` 표현이
상시 접수보다 우선하더라도 파싱된 날짜를 무조건 덮어쓰지는 않습니다.

현재 수집 Client·동기화는 `BIZINFO` 한 제공처만 구현되어 있습니다. 반면 production 검색·색인·AI 점수화는
`sourceCode:sourceProgramId`를 내부 식별자로 사용하고 검색은 준비된 제공처 범위에서 실행합니다.
K-Startup 공식 URL·표시 이름·미지원 질문 안내는 준비했으나 실제 Client·정규화·동기화는 추가하지 않았습니다.
이번 준비에는 새 스키마·의존성·제공처 Registry가 없습니다.

## Frontend와 내부 계약

Frontend의 업무 호출 흐름은 `View → ViewModel Hook → UseCase → Repository → data/api → Core API`입니다.
Awilix의 `app/di`에서 Repository·UseCase·외부 함수를 구성하고 `appContainer`가 앱 단위 인스턴스를
제공합니다. ViewModel은 UseCase·외부 함수 토큰을 조회하며 Repository를 직접 생성하지 않습니다.
`data/api`의 함수가 요청 URL·Fetch·Zod 응답 검증을 담당합니다.

채팅 메시지·검색 조건은 Redux Toolkit으로 관리하고 검색 요청 흐름은 ViewModel의 thunk에 둡니다.
화면 전용 DOM 참조·입력 조합 상태 등은 로컬 hook으로 관리합니다. React Router는 검색 화면,
지원사업 상세와 두 SampleItem 예제 화면을 연결합니다. SampleItem은 업무 기능이 아니라 같은 UseCase의
Hook 상태와 Redux 상태 차이를 비교하는 예제입니다.

Core의 공개 계약은 기능별 `controller/dto`, 외부 계약은 시스템별 `client/dto`, 검증된 실행 결과는
`service/dto`, 업무 모델은 `domain`에 둡니다. 관계형 DB 접근은 `Repository → Mapper → XML`이며
`DbRow`를 Repository 밖으로 노출하지 않습니다. 같은 필드가 있어도 외부 입력과 공개 응답을 하나의
타입으로 합치지 않습니다. 상세 배치 규칙은 [Core API README](../backend/core-api/README.md)에 있습니다.

AI Service는 점수화와 원문 근거 답변에서 각각 `HTTP API → Service → Agent → OpenAI → Response` 흐름으로
실행합니다. `bootstrap.py`가 클라이언트와 서비스 수명주기를 구성하고, 두 typed Agent를 각각
`max_turns=1`로 실행합니다. 현재 tool·handoff·multi-agent orchestration은 없습니다. 일반 공고 색인·검색은
`support_program_index`, 원문 청크 색인·검색은 `support_program_evidence`가 OpenAI 임베딩과 분리된 Qdrant
컬렉션을 직접 사용합니다.

두 색인 Service가 실제로 공유하는 입력 토큰 상한 처리는 `support_program_embedding.py`의 함수 하나로
유지합니다. 토크나이저 준비·인코딩·잘라내기를 작업 스레드에서 실행해 HTTP 이벤트 루프를 막지 않으며,
OpenAI 호출·응답 검증·오류 처리는 각 Service에 남겨 둡니다.

추천 점수화와 근거 답변의 기본 제한시간은 모델 `25s` → Agent 실행 `30s` → Core 읽기 `35s`입니다.
AI Health도 Core의 같은 읽기 설정을 사용합니다. 공고 의미 검색 전체는 AI에서 `25s`, Core 읽기는
`30s`이며, 검색 화면은 의미 검색과 점수화의 순차 호출을 고려해 `70s` 후 요청을 취소합니다.

## 오류 경계

AI Service의 LLM 실행 실패·색인 미준비·Qdrant 실패는 내부 503으로 반환되고 Core는 공개
`503 AI_SERVICE_UNAVAILABLE`로 변환합니다. Core가 관측한 연결·읽기 timeout 및 점수화·색인 API의
내부 408·504는 504, 예상하지 않은 HTTP 상태나 잘못된 응답 계약은 502로 분류합니다.
공개 응답은 `application/problem+json`이며 내부 URL·원본 라이브러리 예외를 노출하지 않습니다.

Core의 Health는 프로세스 상태, AI Health는 AI Service의 정해진 Health 응답을 확인하는 기능입니다.
이들이 성공했다고 MySQL·Qdrant·OpenAI를 포함한 실제 검색 전체가 준비됐음을 보장하지 않습니다.
전체 연결 동작은 [Compose 검증 절차](../infrastructure/README.md)로 확인합니다.

현재 제품은 공고 요약의 의미·키워드 결합 검색·구조화된 추천과, 기업마당 공식 HTML 한 종류의 공고별 근거 답변을 제공합니다.
실제 검색 후보·최종 추천의 캡처, AI-only 참조 판정과 변경 전후 보고서는
[공유 평가 자료](../evaluation/support-program-search/runs/support-program-catalog-20260906-v1/README.md)에 있습니다.
평가 가능한 질문은 6개, 그중 양성 질문은 2개뿐이며 독립적인 사람 검토 품질 증거는 아닙니다.
근거 답변은 [5단계 후속 검증](../evaluation/support-program-evidence/runs/official-flow-20260907-v1/README.md)에서
공식 HTML 2건·질문 6개의 실제 MySQL·Qdrant·모델 연결과 인용을 확인했습니다. 고정 HTML을 재생한
소규모 AI-only 검토이며 공고당 청크 1개여서 일반적인 검색 품질 근거는 아닙니다.
PDF·첨부·다른 제공처 확장은 후속 범위입니다.
