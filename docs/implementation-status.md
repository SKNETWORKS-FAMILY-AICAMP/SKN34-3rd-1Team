# 구현 현황

[메인 README](../README.md) · [프로젝트 기술](technology.md) · [검색·상세 API 계약](support-program-search-contract.md)

기준일: 2026-09-05. 현재 저장소의 production 코드·설정·테스트에 있는 기능을 정리합니다.
아래의 **구현됨**은 코드와 검증 경로가 있다는 뜻이며, 운영 배포 완료나 실데이터 정확도 입증을 뜻하지 않습니다.

## 사용자 기능

| 기능 | 상태 | 현재 동작 |
|---|---|---|
| 자연어 검색 | 구현됨 | 현재 공개된 기업마당 공고에서 의미 검색 후 AI 점수화, 0~5개 반환 |
| 접수 중 공고 검색 | 구현됨 | 채팅 UI는 `acceptingOnly=true`로 요청해 `OPEN` 공고만 검색 |
| 최신 목록 조회 | API 구현됨 | `query=`로 요청하면 AI 없이 최신 공고 최대 5개 반환; 채팅 UI는 빈 입력을 전송하지 않음 |
| 추천 결과 카드 | 구현됨 | 제목·기관·신청 기간·출처·추천 이유·점수 표시 |
| 공고 상세 화면 | 구현됨 | URL의 제공처 코드·원본 ID로 MySQL에서 재조회, 404·오류 안내, 원문 링크 제공 |
| 상세 직접 접속·새로고침 | 구현됨 | 검색 결과 상태가 없어도 같은 식별자로 API 조회 |
| 검색 입력 검증 | 구현됨 | 공백 입력·중복 전송 방지, 500자 초과 시 입력 보존·안내, 한글 IME Enter 처리 |
| 검색 취소 | 구현됨 | 새 대화·화면 이탈 시 요청 취소, 요청 ID로 오래된 응답 무시 |
| 대화 화면 상태 | 메모리 보관 | 화면 이동 중 Redux 상태 유지, 브라우저 새로고침 시 초기화 |
| 대화 맥락 기반 검색 | 미구현 | 이전 메시지를 다음 검색 요청에 보내지 않음 |
| 회원·기업 프로필·북마크·알림 | 미구현 | 저장·인증 API와 해당 업무 흐름 없음 |

공고 상세는 동기화한 목록 데이터의 상세 표시입니다. 기업마당 상세 페이지나 첨부문서를 추가 수집해
보여 주는 기능은 아닙니다. 추천 이유·점수는 검색 문장에 종속되므로 상세 API에서는 빈 이유·null 점수로 반환합니다.

관련 코드: [화면 라우트](../frontend/src/App.tsx), [채팅 ViewModel](../frontend/src/presentation/features/chat/viewmodel/useSupportProgramChatViewModel.ts),
[공개 Controller](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/controller/SupportProgramController.kt).

## 공고 수집·저장·동기화

| 기능 | 상태 | 현재 동작 |
|---|---|---|
| 기업마당 연동 | 구현됨 | 공공데이터포털 API 전체 페이지·전체 건수·필수값 검증 |
| MySQL 영속성 | 구현됨 | MyBatis XML UPSERT·조회, JSON 분야·지역, 원문·nullable 신청 날짜 저장 |
| 스키마 관리 | 구현됨 | Flyway V1 공고 테이블, V2 동기화 세대 테이블 |
| 정기 수집 | 구현됨 | 앱 시작 시 초기 지연 `PT0S`로 실행, 이후 완료 시점부터 기본 6시간 간격 |
| 공개 전 색인 | 구현됨 | 전체 벡터 준비 성공 후에만 신규 MySQL 스냅샷 공개 |
| 동시 실행 결과 보호 | 구현됨 | 최신 시작 세대만 공개, 이전 실행의 늦은 덮어쓰기 방지 |
| 수집·색인 실패 처리 | 구현됨 | 기존 공개 카탈로그 유지, 다음 스케줄에서 재시도 |
| 누락 공고 비활성화 | 구현됨 | 완전한 목록을 공개할 때 해당 제공처의 누락 공고만 미노출 처리 |
| DB 반영 원자성 | 구현됨 | 비활성화·UPSERT를 하나의 트랜잭션으로 묶어 실패 시 롤백 |
| 원본 ID 대소문자 갱신 | 구현됨 | UPSERT에서 최신 ID 표기를 반영해 사전 색인한 ID와 일치 유지 |
| 누락 벡터 복구 | 구현됨 | 현재 MySQL 공고를 기본 1분 간격으로 확인하고 기존 벡터 재사용·누락 생성 |
| 오래된 벡터 자동 정리 | 미구현 | 내부 `prune` API는 있으나 자동 동기화는 호출하지 않음 |
| 두 번째 공고 제공처 | 미구현 | 식별자는 제공처를 포함하지만 실제 수집·의미 검색 경로는 `BIZINFO` 전용 |

세대 관리는 결과 공개 순서를 보호합니다. 여러 서버가 같은 데이터를 동시에 수집·임베딩하는 작업 자체를
막지는 않습니다. 수집·색인에 실패하면 새 데이터 공개가 지연되며, 첫 동기화가 완료되기 전에는 저장된
공고가 없어 검색 결과가 비어 있을 수 있습니다. Frontend 원문 URL 검증도 현재 기업마당 도메인만
허용하므로 두 번째 제공처는 설정만 추가해서 사용할 수 없습니다.

관련 코드: [동기화 Service](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/service/sync/BizInfoSupportProgramCatalogSyncService.kt),
[Repository](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/repository/SupportProgramRepository.kt),
[색인·복구 Service](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/service/sync/SupportProgramIndexSyncService.kt).

## 검색·AI 구현 범위

| 기능 | 상태 | 현재 동작 |
|---|---|---|
| 전체 현재 공고 의미 검색 | 구현됨 | 최신 20개로 먼저 자르지 않고 검색 가능 공고 전체를 대상으로 후보 최대 20개 선정 |
| 공고 버전 일치 확인 | 구현됨 | 현재 DB의 공고 ID·내용 해시로 검색 허용 목록 구성 |
| 후보 점수화 | 구현됨 | 단일 OpenAI Agent가 전달된 모든 후보를 점수화 |
| 추천 최소 기준 | 구현됨 | 의미 관련성 ≥20/40, 총점 ≥60/100, 최종 최대 5개 |
| 대상·지역 불일치 제외 | 구현됨 | AI가 `INCOMPATIBLE`로 판정한 공고 제외; `UNKNOWN`은 자동 제외하지 않음 |
| AI 응답 검증 | 구현됨 | 후보 ID·중복·점수 범위·합계·정렬·자격 필드·추천 이유 검증 |
| 상세 원문·첨부 수집, PDF/OCR | 미구현 | 공고 목록에서 받은 정규화 필드를 사용 |
| 문서 분할·근거 인용 RAG 답변 | 미구현 | 검색·추천 이유 외에 원문 구간을 인용하는 질의응답 없음 |
| 하이브리드 검색·검색어 캐시 | 미구현 | 운영 검색은 벡터 후보 검색 후 AI 점수화 경로 |

점수와 `MATCH` 판정은 모델의 판단이며 실제 신청 가능 여부나 선정 확률을 보장하지 않습니다.
코드는 응답 형식·숫자·제외 규칙을 검증하지만, 추천 이유가 원문에 의해 모두 뒷받침되는지까지
독립적으로 검증하지는 않습니다. 접수 상태 또한 파싱된 날짜와 알려진 문구 규칙을 따르며 신청 기간 밖의
모든 예외 조건을 반영하지 않습니다.

관련 코드: [검색 Service](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/service/search/SupportProgramSearchService.kt),
[AI 점수화 Service](../backend/ai-service/app/support_program_ranking/service.py),
[벡터 검색 Service](../backend/ai-service/app/support_program_index/service.py).

## 검증과 평가

| 검증 | 저장소에 있는 내용 | 해석 범위 |
|---|---|---|
| Frontend | ViewModel·HTTP 계약·라우팅·상세·IME·입력 검증 테스트, lint·build | 화면과 요청 처리의 회귀 확인 |
| Core API | Controller·외부 경계·검색·동기화 테스트, MySQL 8.4 Testcontainers | API 계약·SQL·롤백·동기화 동작 확인 |
| AI Service | Agent 출력·점수·자격 필터·벡터 API 테스트, CI의 Qdrant 연동 검증 | 내부 계약과 검색 색인 동작 확인 |
| Compose smoke | 실제 MySQL·Qdrant와 로컬 기업마당·OpenAI 스텁 | 전체 연결, 오래된 관련 공고 경로, 장애 격리와 재시작 복구 |
| 가상 공고 평가 | 공고 40개·질문 30개, 최신순·키워드 비교, 외부 의미 검색 결과 파일 입력 | 후보 검색 회귀 평가 도구; 실제 추천 정확도 증거 아님 |
| 실데이터 검색 품질 | 고정 공고·사람이 검토한 정답·실제 모델 비교 보고서 없음 | 후속 검증 필요 |

평가 도구는 이미 있습니다. 현재 계산하는 지표는 `macroRecallAtK`와 무결과 질문의
`noMatchFalsePositiveRate`이며 기본 K는 20입니다. MRR이나 AI 재정렬 전후의 자동 비교는 구현하지 않았습니다.
의미 검색 결과 파일을 제공하지 않으면 출력의 `semantic`은 `null`입니다.
[평가 자료와 실행법](../evaluation/support-program-search/README.md)을 참고하세요.

현재 Health API는 서비스 응답과 Core→AI 연결을 확인합니다. 공고 최신성, MySQL·Qdrant·OpenAI 전체 상태를
한 번에 확인하는 준비 상태 점검은 아닙니다. CI 정의가 있다는 사실만으로 특정 원격 실행의 성공을 뜻하지는 않습니다.
실행 명령은 [서비스별 안내](../README.md#상세-문서)와 [CI 정의](../.github/workflows/ci.yml)에 있습니다.

## 현재 제약과 다음 작업

| 우선순위 | 작업 | 완료를 판단할 기준 |
|---|---|---|
| 1 | 실데이터 검색 품질 평가 | 같은 시점의 실제 공고·대표 질문·사람이 검토한 정답을 고정하고 후보 검색과 최종 Top-5를 별도로 비교 |
| 2 | 관측 정보와 성능 기준 확보 | 최근 동기화 성공 시각·실패, 색인 준비 상태, 검색 지연·모델 사용량을 확인할 수 있는 기록과 측정 |
| 3 | 원문 근거 기반 RAG | 필요한 공고 원문·첨부 수집, 문서 분할·색인, 답변 근거 인용을 하나의 사용 사례로 구현 |
| 4 | 데이터·사용자 기능 확장 | 두 번째 제공처나 기업 프로필·북마크를 선택하고 해당 API·저장·화면까지 연결 |

검색은 현재 기업마당 공고를 MySQL에서 모두 읽습니다. 비어 있지 않은 검색어를 처리할 때는 접수 상태
필터를 통과한 공고의 문서 해시도 계산해 내부 API에 보냅니다.
검색·색인 경계의 공고 수 제한은 20,000개이며, 그 이상의 카탈로그나 동시 사용자 부하는 검증된 범위가 아닙니다.
오래된 벡터의 저장 공간 정리도 아직 자동화하지 않았습니다. 규모 확장 전에 조회 비용과 색인 수명주기를
측정·개선해야 합니다.

현재 Compose는 개발 환경이며 회원 인증, 운영 접근 제어, 배포 자동화, 백업·복구 절차는 별도 구현·검증이
필요합니다. UI가 채팅 형태라는 이유만으로 문서 기반 RAG나 대화 이력 저장이 구현된 것으로 설명하지 않습니다.
