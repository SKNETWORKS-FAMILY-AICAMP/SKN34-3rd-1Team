# 구현 현황

[메인 README](../README.md) · [문서 목록](README.md) · [아키텍처 README](architecture/README.md)

기준일: 2026-09-07. 현재 저장소의 production 코드·설정·테스트에 있는 기능을 정리합니다.
아래의 **구현됨**은 코드와 검증 경로가 있다는 뜻이며, 운영 배포 완료나 실데이터 정확도 입증을 뜻하지 않습니다.

현재 **3단계는 제한적인 AI-only 기준선 측정 완료**, **4단계는 합의한 개선·전후 비교·요청 제한·통합 검증 완료**입니다.
[4단계 최종 검증 기록](support-program-request-limits.md#4단계-최종-통합-검증-2026-09-06)을 참고하세요.
**5단계는 기업마당 HTML 기반 최소 구현·검증 범위 완료**입니다. 추가 E12에서 64자리 인용 ID 복사 오류를
확인해 짧은 번호 선택·ID 복원 방식으로 수정했습니다. 수정 후 가상 4건과 공식 공고 2건의 질문 6개를 검증했고,
첫 공식 실행은 기대 상태 6건 일치·AI 의미 검토 5건 일치/1건 부분 일치였습니다. 당시 H01의 요약 누락과
이전 E01의 확정 불가한 실패 원인을 보존합니다. 전체 품질 합격·첨부/PDF 지원·운영 준비 완료는 아닙니다.
[5단계 검증 보고서](../evaluation/support-program-evidence/runs/official-flow-20260907-v1/README.md)와
[실행법](../evaluation/support-program-evidence/README.md)을 참고하세요.

5단계 후속 보완으로 H01의 대상 조건 요약 누락을 줄이기 위한 프롬프트 지침과 가상 질문 6개를 추가했습니다.
관련 범위·예외와 필수/우대/선택 관계를 보존하도록 했고, 공고별 하드코딩이나 검색 구조 변경은 없습니다.
**추가 20회로 가상 6건·공식 6건의 실측도 완료했습니다.** AI 의미 검토에서 모두 일치했고 공식 H01의
누락 보완과 기존 사례의 비회귀를 관찰했습니다. 단회 소규모 결과로 일반 정확도·통계적 개선을 주장하지
않습니다. [최신 검증 보고서](../evaluation/support-program-evidence/runs/official-flow-20260907-v2/README.md)에 전후 조건·사용량·한계를 보존했습니다.

## 사용자 기능

| 기능 | 상태 | 현재 동작 |
|---|---|---|
| 자연어 검색 | 구현됨 | 현재 공개된 MySQL 공고 카탈로그에서 의미·키워드 결합 검색 후 AI 점수화, 0~5개 반환 |
| 검색 준비 상태 확인 | 구현됨 | 공개 스냅샷 공고 수·색인 준비·마지막 동기화 성공/실패를 `PREPARING`·`SEARCHABLE`·`SEARCHABLE_WITH_SYNC_FAILURE`·`UNAVAILABLE`으로 구분 |
| 접수 중 공고 검색 | 구현됨 | 채팅 UI는 `acceptingOnly=true`로 요청해 `OPEN` 공고만 검색 |
| 최신 목록 조회 | API 구현됨 | `query=`로 요청하면 AI 없이 최신 공고 최대 5개 반환; 채팅 UI는 빈 입력을 전송하지 않음 |
| 추천 결과 카드 | 구현됨 | 제목·기관·신청 기간·출처·추천 이유·점수 표시 |
| 공고 상세 화면 | 구현됨 | URL의 제공처 코드·원본 ID로 MySQL에서 재조회, 404·오류 안내, 원문 링크 제공 |
| 상세 직접 접속·새로고침 | 구현됨 | 검색 결과 상태가 없어도 같은 식별자로 API 조회 |
| 상세 원문 질문 화면 | 구현됨 | 사용자가 질문 버튼을 누를 때만 원문 질문 API를 호출하고 답변·인용·근거 부족/지원 불가/일시 오류를 안내 |
| 공고별 공식 원문 질문 API | 구현됨 | 기업마당 현재 공고에 한해 명시적 질문 시 답변·원문 인용·근거 부족 상태를 반환 |
| 검색 입력 검증 | 구현됨 | 공백 입력·중복 전송 방지, 500자 초과 시 입력 보존·안내, 한글 IME Enter 처리 |
| 검색 취소 | 구현됨 | 새 대화·화면 이탈 시 요청 취소, 요청 ID로 오래된 응답 무시 |
| 요청량·동시 실행 제한 | 구현됨 | 검색·원문 질문이 주소별 최근 60초 6건·Core 프로세스 전체 60건·동시 4건을 공유; 429/503 안내와 입력·수동 재시도 유지 |
| 대화 화면 상태 | 메모리 보관 | 화면 이동 중 Redux 상태 유지, 브라우저 새로고침 시 초기화 |
| 대화 맥락 기반 검색 | 미구현 | 이전 메시지를 다음 검색 요청에 보내지 않음 |
| 회원·기업 프로필·북마크·알림 | 미구현 | 저장·인증 API와 해당 업무 흐름 없음 |

`GET /detail`은 동기화한 목록 데이터만 표시하고 외부 호출을 하지 않습니다. 반면
`POST /detail/answers`는 사용자가 질문한 기업마당 현재 공고에 한해 공식 상세 HTML을 추가 수집해 근거 답변을
만듭니다. 첨부문서는 수집하지 않습니다. 추천 이유·점수는 검색 문장에 종속되므로 상세 GET API에서는 빈 이유·null 점수로 반환합니다.

관련 코드: [화면 라우트](../frontend/src/App.tsx), [채팅 ViewModel](../frontend/src/presentation/features/chat/viewmodel/useSupportProgramChatViewModel.ts),
[공개 Controller](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/controller/SupportProgramController.kt).

## 공고 수집·저장·동기화

| 기능 | 상태 | 현재 동작 |
|---|---|---|
| 기업마당 연동 | 구현됨 | 공공데이터포털 API 전체 페이지·전체 건수·필수값 검증 |
| MySQL 영속성 | 구현됨 | MyBatis XML UPSERT·조회, JSON 분야·지역, 원문·nullable 신청 날짜 저장 |
| 스키마 관리 | 구현됨 | Flyway V1 공고, V2 동기화 세대, V3 공고별 공식 원문, V4 공개 스냅샷 준비 상태 테이블 |
| 정기 수집 | 구현됨 | 앱 시작 시 초기 지연 `PT0S`로 실행, 이후 완료 시점부터 기본 6시간 간격 |
| 공개 전 색인 | 구현됨 | 전체 벡터 준비 성공 후에만 신규 MySQL 스냅샷 공개 |
| 동시 실행 결과 보호 | 구현됨 | 최신 시작 세대만 공개, 이전 실행의 늦은 덮어쓰기 방지 |
| 수집·색인 실패 처리 | 구현됨 | 기존 공개 카탈로그 유지, 다음 스케줄에서 재시도 |
| 동기화·색인 준비 상태 기록 | 구현됨 | 공개 세대·지문·공고 수·색인 준비와 최근 성공/실패를 분리해 기록; 조건부 복구 갱신으로 새 스냅샷 덮어쓰기 방지 |
| 누락 공고 비활성화 | 구현됨 | 완전한 목록을 공개할 때 해당 제공처의 누락 공고만 미노출 처리 |
| DB 반영 원자성 | 구현됨 | 비활성화·UPSERT를 하나의 트랜잭션으로 묶어 실패 시 롤백 |
| 원본 ID 대소문자 갱신 | 구현됨 | UPSERT에서 최신 ID 표기를 반영해 사전 색인한 ID와 일치 유지 |
| 누락 벡터 복구 | 구현됨 | 현재 MySQL 공고를 기본 1분 간격으로 확인하고 기존 벡터 재사용·누락 생성 |
| 오래된 벡터 자동 정리 | 미구현 | 내부 `prune` API는 있으나 자동 동기화는 호출하지 않음 |
| 두 번째 공고 제공처 | 미구현 | 수집 Client·동기화는 `BIZINFO` 전용. 검색·색인·AI 점수화 내부 식별자는 제공처를 포함해 준비됨 |

세대 관리는 결과 공개 순서를 보호합니다. 여러 서버가 같은 데이터를 동시에 수집·임베딩하는 작업 자체를
막지는 않습니다. 수집·색인에 실패하면 새 데이터 공개가 지연되며, 첫 동기화가 완료되기 전에는 저장된
공고가 없어 검색 결과가 비어 있을 수 있습니다. Frontend 원문 URL 검증은 제공처 코드별 공식 호스트
허용 목록을 사용합니다. 실제 두 번째 제공처를 추가할 때는 수집·동기화뿐 아니라 그 제공처의 공식 호스트
정책과 원문 질문 지원 여부도 함께 구현해야 합니다.

관련 코드: [동기화 Service](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/service/sync/BizInfoSupportProgramCatalogSyncService.kt),
[Repository](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/repository/SupportProgramRepository.kt),
[색인·복구 Service](../backend/core-api/src/main/kotlin/ai/govbiz/core/supportprogram/service/sync/SupportProgramIndexSyncService.kt).

## 검색·AI 구현 범위

| 기능 | 상태 | 현재 동작 |
|---|---|---|
| 전체 현재 공고 의미 검색 | 구현됨 | 최신 20개로 먼저 자르지 않고 검색 가능 공고 전체를 대상으로 후보 최대 20개 선정 |
| 공고 버전 일치 확인 | 구현됨 | 현재 DB의 공고 ID·내용 해시로 검색 허용 목록 구성 |
| 후보 점수화 | 구현됨 | 단일 OpenAI Agent가 모든 후보의 세부 점수·자격을 판단하고 Service가 총점을 합산; 지원 목적 채점 지침 개선 |
| 추천 최소 기준 | 구현됨 | 의미 관련성 ≥20/40, 총점 ≥60/100, 최종 최대 5개 |
| 대상·지역 불일치 제외 | 구현됨 | AI가 `INCOMPATIBLE`로 판정한 공고 제외; `UNKNOWN`은 자동 제외하지 않음 |
| AI 응답 검증 | 구현됨 | 후보 ID·중복·점수 범위·합계·정렬·자격 필드·추천 이유 검증 |
| 기업마당 공식 상세 HTML 원문 수집·저장 | 구현됨 | 명시적 상세 질문에서만 HTTPS 공식 HTML을 정규화해 MySQL에 저장·6시간 후 갱신 |
| 원문 청킹·근거 인용 RAG 답변 | 구현됨 | 최대 50개 결정적 청크를 별도 Qdrant 컬렉션에서 검색하고 최대 5개 근거만 Agent에 전달 |
| 첨부문서·PDF/OCR·다른 제공처 원문 | 미구현 | 기업마당 공식 상세 HTML 이외의 원문 형식·제공처는 수집·질문 지원 없음 |
| 하이브리드 검색 | 구현됨 | 의미 검색 20개·키워드 검색 20개의 순위를 동일 가중치 RRF로 결합해 최종 후보 최대 20개 선정; 의미 검색 장애를 숨기지 않음 |
| 검색어 캐시 | 미구현 | 같은 검색어도 기존 검색 흐름을 다시 실행 |

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
| Frontend | ViewModel·HTTP 계약·라우팅·상세·원문 질문·인용·IME·입력 검증 테스트, lint·build | 화면과 요청 처리의 회귀 확인 |
| Core API | Controller·외부 경계·검색·동기화 테스트, MySQL 8.4 Testcontainers | API 계약·SQL·롤백·동기화 동작 확인 |
| AI Service | Agent 출력·점수·자격 필터·일반·원문 근거 벡터 API·Compose 스텁 계약 테스트. 2026-09-07 로컬 전체 207개 통과 | 내부 계약과 검색·근거 청크 색인 동작 확인. 실제 Qdrant 연동 테스트는 CI 설정으로 별도 실행 |
| Compose smoke | 실제 MySQL·Qdrant와 로컬 기업마당·OpenAI 스텁. 구형 스텁 응답 수정 후 2026-09-06 전체 통과 | 전체 연결, 오래된 관련 공고 경로, 장애 격리와 재시작 복구. 실제 모델 품질·운영 부하 검증 아님 |
| 가상 공고 평가 | 공고 40개·질문 30개, 최신순·키워드 비교, 외부 의미 검색 결과 파일 입력 | 후보 검색 회귀 평가 도구; 실제 추천 정확도 증거 아님 |
| 실데이터 fixture 초안 내보내기 | `evaluation-fixture-export` profile이 지정한 기준 날짜의 현재 모든 제공처 `OPEN` 공고를 운영 색인 Mapper와 같은 ID·내용 해시·검색 문서로 JSON 기록 | 웹·동기화·Qdrant·AI·OpenAI 호출 없음; `cases: []`은 선택한 방식으로 판정해야 함 |
| 실제 검색 흐름 캡처 | `evaluation-capture` profile이 같은 기준 날짜로 내부 Search Service의 Qdrant 후보·AI 최종 추천 ID와 카탈로그 지문을 v2 JSON으로 기록 | fixture와 기준 날짜가 다르면 평가 거부; 공개 endpoint·자동 실행 없음, 실제 MySQL·AI Service 연결과 명시적 실행 필요 |
| 실데이터 판정 공유 | 고정 공고 1,422건·질문 16개·최종 570쌍(기존 321 + 신규 249), 누적 AI 원표 3,060표 보존 | AI-only 합의 541건·미확정 29건. 독립적인 사람 검증 정답이 아님 |
| 3단계 실데이터 검색 평가 | 출력 계약 오류 수정 후 실제 검색 16/16 성공, 후보·최종 지표와 재현 보고서 보관 | 제한적인 AI-only 기준선 측정 완료. 평가 가능 6개·양성 2개에서 후보 Recall@20·최종 MRR@5 각각 0.50; 전체 품질 합격 아님 |
| AI 판정 이전 | `transfer-ai-review.py`로 기존 판정·재검토 원본을 보존하고 실제 캡처의 신규 249쌍만 추가 판정·병합 | 실제 최종 풀 이전과 API 없는 원표·캡처·보고서 재현 검증 완료 |
| 4단계 후보 검색 개선 | 동일 스냅샷·16개 질문으로 의미·키워드 결합 검색을 전후 비교 | 기존 양성 질문 2개에서 후보 Recall@20 0.50→0.75, 최종 MRR@5 0.50→1.00. 새 미판정은 무관으로 세지 않음 |
| 4단계 지원 목적 판단 개선 | 후보를 고정한 랭킹 전후 각 16회와 응답시간·실패·토큰 사용량 기록 | dev 무관 추천 6→4건, 관련 추천 15→16건. heldout 무관 5건은 그대로, 평균 API 지연 14.74→16.56초; 부분 개선 |
| 5단계 RAG 오류 수정·전체 경로 검증 | Core 전체 344개·AI 전체 206개 통과. 이번 추가 API 19회로 ID 복사 오류 확인/수정·가상 4건 재확인·공식 6건 전체 경로 성공 | 공식 HTML 재생·실제 MySQL/Qdrant/모델 연결 검증. 공식 의미 일치 5·부분 1, 공고당 청크 1개여서 다수 청크 검색 품질은 미평가 |
| 5단계 대상 조건 요약 후속 검증 | 프롬프트 보완 후 AI 전체 207개·평가 도구 118개 통과. 별도 20회로 가상 6건·공식 6건 실측 완료 | 단회 AI 의미 검토 12건 일치, 공식 H01 부분→일치. 답변 입력·출력 토큰 증가도 기록. 실제 다중 청크 검색·운영 부하는 미평가 |

평가 도구는 후보 단계의 `macroRecallAtK`·무결과 오추천율과 최종 단계의 `macroRecallAt5`·`MRR@5`·무결과
오추천율을 분리해 계산합니다. `evaluation-fixture-export`는 현재 MySQL 카탈로그에서 미라벨 fixture 초안을
만들고, `evaluation-capture`는 실제 Search Service를 다시 구현하지 않고 같은 기준 날짜의 호출 흐름에서 나온
후보·최종 ID를 기록합니다. 실제 snapshot·capture는 `evaluation/support-program-search/runs/`에 보관합니다.
현재 고정 스냅샷·AI 판정·모드 선택 결과는 Git 포함 대상이고 새 실행·임시 출력은 기본적으로 제외합니다.
[공유 자료 검증과 이어받기](../evaluation/support-program-search/runs/support-program-catalog-20260906-v1/README.md)를 따릅니다.
선택한 판정 출처와 실제 캡처 없이 검색 품질 점수를 주장하지 않으며, 의미 검색 결과
파일이나 캡처 파일을 제공하지 않으면 각각 출력의 `semantic`·`capture`는 `null`입니다.
[평가 자료와 실행법](../evaluation/support-program-search/README.md)을 참고하세요.

메인 README에서 분리한 단계별 평가 결과는 원본 실행 폴더에서 이어서 확인합니다.

- [3단계 기준선 보고서](../evaluation/support-program-search/runs/support-program-catalog-20260906-v1/review-final-v1/report.md): 판정 출처·표본 한계·후보/최종 지표
- [4단계 1차 후보 검색 비교](../evaluation/support-program-search/runs/support-program-catalog-20260906-v1/stage4-v1/README.md): 동일 질문의 RRF 결합 전후·제한시간 정렬
- [4단계 2차 고정 후보 랭킹 비교](../evaluation/support-program-search/runs/support-program-catalog-20260906-v1/stage4-v2/README.md): 지원 목적 판단 전후·응답 지연·토큰 사용량
- [4단계 요청 제한·최종 통합 검증](support-program-request-limits.md#4단계-최종-통합-검증-2026-09-06): 제한 구현과 전체 Compose 통과 기록
- [5단계 HTML RAG 후속 검증](../evaluation/support-program-evidence/runs/official-flow-20260907-v1/README.md): 인용 ID 오류 수정·공식 공고 전체 경로·AI-only 정성 검토·API 없는 기록 재검사
- [5단계 대상 조건 요약 검증](../evaluation/support-program-evidence/runs/official-flow-20260907-v2/README.md): H01 누락 보완·가상 6건 및 공식 6건·전후 조건/사용량 비교

저장소를 받은 개발자는 공유 자료 안내의 `verify-shared-run.py --with-capture` 명령으로 API 키·DB·Excel 없이
3단계 원표부터 지표까지 다시 확인할 수 있습니다. 4단계 전후 비교의 재계산 명령은 각 보고서에 있습니다.

현재 Health API는 서비스 응답과 Core→AI 연결을 확인합니다. 검색 화면의 `GET /api/v1/support-programs/readiness`는
마지막 전체 색인 준비 결과와 동기화 이력을 보여 주지만, MySQL·Qdrant·OpenAI의 실시간 Health를 한 번에
확인하는 endpoint는 아닙니다. CI 정의가 있다는 사실만으로 특정 원격 실행의 성공을 뜻하지는 않습니다.
실행 명령은 [서비스별 안내](../README.md#상세-문서)와 [CI 정의](../.github/workflows/ci.yml)에 있습니다.

## 현재 제약과 다음 작업

| 우선순위 | 작업 | 완료를 판단할 기준 |
|---|---|---|
| 1 | 다음 제품 범위 선택 | 5단계 HTML 최소 범위 및 대상 요약 보완 실측 완료. 두 번째 제공처·사용자 기능 또는 첨부/PDF 중 다음 범위를 별도 결정 |
| 2 | 외부 공개 전 운영 검증 | 배포 경계와 신뢰할 프록시·서버 수를 확정하고 실제 동시 부하·제한값·상시 지연/실패/사용량 관측을 검증. 현재 요청 제한과 평가 실행 로그를 운영 검증 완료로 취급하지 않음 |
| 3 | 추가 검색·답변 품질 개선 | 새로 관측되는 후보 누락·오추천·답변 누락·지연을 dev 근거와 같은 조건으로 전후 측정. H01 단회 개선을 일반화하지 않고 기존 자료·미확정 질문을 보존하며 이미 노출된 heldout에 맞춰 튜닝하지 않음 |
| 4 | 6단계: 데이터·사용자 기능 확장 | 두 번째 제공처나 기업 프로필·북마크를 선택하고 해당 API·저장·화면까지 연결 |

검색은 현재 MySQL의 모든 제공처 공개 공고를 읽습니다. 비어 있지 않은 검색어를 처리할 때는 접수 상태
필터를 통과한 공고의 제공처 포함 ID와 문서 해시를 내부 API에 보냅니다.
검색·색인 경계의 공고 수 제한은 20,000개이며, 그 이상의 카탈로그나 동시 사용자 부하는 검증된 범위가 아닙니다.
오래된 벡터의 저장 공간 정리도 아직 자동화하지 않았습니다. 규모 확장 전에 조회 비용과 색인 수명주기를
측정·개선해야 합니다.

현재 Compose는 개발 환경이며 회원 인증, 운영 접근 제어, 배포 자동화, 백업·복구 절차는 별도 구현·검증이
필요합니다. 문서 기반 RAG는 기업마당 공식 HTML 한 종류의 공고별 명시적 질문에만 구현되어 있으며,
대화 이력 저장이나 첨부파일 기반 RAG가 구현된 것은 아닙니다.
