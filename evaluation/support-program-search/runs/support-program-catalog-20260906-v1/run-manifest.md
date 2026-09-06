# 지원사업 검색 최소 평가 실행 기록

## 실행 식별

- runId: `support-program-catalog-20260906-v1`
- 준비 시각(KST): `2026-09-06`
- Core API commit SHA: `f7bf6a8ae2bbaf06567e5b48fb8fcee4880a9fcf`
- AI Service commit SHA: `f7bf6a8ae2bbaf06567e5b48fb8fcee4880a9fcf`
- 준비 당시 작업 트리: tracked 파일 변경 없음
- 공유 범위: 평가 검토 도구·테스트·현재 고정 공고·판정·README는 Git 포함 대상. 실제 서비스 코드 변경 없음
- 실제 캡처 실행자 및 시각: 대기
- 라벨 검토 방식: 사용자 선택에 따라 AI-only. Codex `gpt-5.6-luna` 독립 작업 5개, 1,605개 판정 완료

## 고정한 공고·질문

- referenceDate: `2026-09-06`
- fixture: `fixture-unlabeled.json`
- fixture SHA-256: `d59feefeb44eac64a92c96cc4e1f44462c1f8110aeaf7894881dae5f5f5bc067`
- fixture catalog fingerprint: `d3a81627a9e0dd6480091445d6cfb4f99cfdebc7e5cdc8ebfbb362e9f2283deb`
- 전체 공개 공고 수 / 평가 대상 OPEN 공고 수: `1,506 / 1,422`
- query set: `query-set.json`
- query-set 파일 SHA-256: `46b3e0d5db81f318785cb9eec441f3f7ff593c4c23eccdeaa756fa5574ff7d93`
- capture용 논리 query-set SHA-256: `3cce56a30ae70d4c186896d00388f77e9427c9d99efeba1fd64fe91c503cdb83`
- 질문 수정: 실제 캡처·사람 판정 전에 Q01의 온라인 판매 제품/제작 서비스와 Q04의 국내 통역을 명확히 함
- 질문 출처: 실제 공고를 참고해 만든 소규모 평가 질문이며 실제 사용자 분포를 대표하지 않음
- dev / heldout: `10 / 6`
- 무결과 예상 질문: `Q09`, `Q10`, `Q15`, `Q16`

## 후보 풀

- pool config SHA-256: `441448b527999cc5b4833d49a73fe6c3f45ac49c7835b10109cb8dda4133229d`
- 현재 검토 버전: `review-v2/`
- 오프라인 후보 수: `321행`
- 오프라인 review CSV SHA-256: `fa9e0e9c3a68192af90fb7b153a4bed15f5d155f96187a15260e52dd6e77b3b7`
- 오프라인 pool manifest SHA-256: `02891591f60d1235094fb3cfe9d0b84af12752eb18c7eb7cc2e8f050f22ee24c`
- 검토 XLSX SHA-256: `6142be4c5c3c97364e977bb7cf7eaf9aba60d7430fab365be4651b9b320e9231`
- 이전 최상위 323행 CSV와 outputs 초안은 로컬 보존용이며 Git 공유·새 검토에 사용하지 않음
- 검증: 스냅샷 1,422건 본문 해시 일치, XLSX 왕복 321행 전체 필드 일치, 판정 입력 전부 빈칸
- 도구 테스트: 기존 평가 26건, 사람 검토 변환 9건, XLSX 작업 5개 시나리오(부모 포함 Node 6건) 통과
- XLSX 검증: 두 시트 렌더 확인, 불변 내용 해시 확인, 수식 오류 없음, 입력값 미리 판정하지 않음
- 실제 검색 후보 병합: 대기
- 최종 review CSV SHA-256: 대기
- 라벨 완료 / 무결과 / 판단 보류 질문 수: 대기

## 검색 실행 설정

- acceptingOnly: `true`
- 후보 최대 개수: `20`
- 최종 추천 최대 개수: `5`
- ranking scoringVersion: 캡처 후 기록
- OpenAI ranking model: 캡처 후 기록
- 임베딩 모델 / 차원: 캡처 후 기록
- Qdrant collection 이름 및 버전: 캡처 후 기록
- ranking prompt SHA-256: 캡처 후 기록

## 결과

- 엑셀 없는 검토 화면: 초기 로컬 파일은 `review-v2/web/index.html`. 다른 PC는 [공유 안내](README.md)에 따라 새로 생성
- 사용자 구성: 단일 검토자. 대화 요약을 보고 확인한 두 공고의 판정과 원문 응답을 화면에 이어받음
- 초기 화면: 321행 중 2행 입력 완료, 319행 미완료. 실제 검색 품질 점수나 질문 전체 라벨 완료가 아님
- 웹 검토 검증: 기존 평가 26건 + 검토 Python 24건 통과. 합성 데이터 브라우저 시나리오 12개 통과
- 브라우저 검증 범위: 부분 입력 저장·새로고침·JSON 저장/불러오기·CSV 변환·잘못된 파일 거부·저장 충돌·360px 너비
- 실제 검토 데이터에 테스트 판정을 추가하지 않음. OpenAI/API 호출 없이 로컬 화면만 구현
- 결과 회수: 화면에서 저장한 JSON 원본 보관 후 `extract-review-json.py`로 새 CSV 생성
- capture schemaVersion: `support-program-search-capture-v2`
- capture 파일명 및 SHA-256: 대기
- capture capturedAt: 대기
- dev 결과 파일: 대기
- heldout 결과 파일: 대기
- 실행 결과: AI 참조 판정 생성 완료(합의 279/미확정 42개). 실제 검색 캡처 및 후보/최종 추천 점수는 아직 없음
- 판정 실행 경로: 프로젝트 OpenAI API 키 대신 Codex Luna 하위 에이전트. 프로젝트 API 호출 없음
- 캡처 상태: 별도의 실제 운영 검색 실행이 필요하며 아직 실행하지 않음. Codex 판정으로 검색 결과를 대체하지 않음

## 선택 가능한 판정 방식

- `ai-only`: 기본값. 5개 독립 판정 중 4개 이상 합의한 결과만 사용. 사람 확인 불필요
- `hybrid`: 불일치/정보 부족 전부와 질문별 합의 표본 10%(최소 1개)의 실제 사람 확인 후 사용
- `human`: 기존 브라우저 검토에서 사람이 판정한 값만 사용
- 사람 판정 원본: `review-v2/conversation-judgments.json`의 2건을 별도 보존. AI-only 정답으로 주입하지 않음
- 사람 모드 준비: `review-v2/selected-human-v1/`, 2건 입력/319건 대기. 기존 검토 화면과 저장 공간 분리
- Codex 입력/정책/실행 배정/원본 판정: `review-v2/codex-ai-v1/`
- 현재 선택: `review-v2/selected-ai-v1/`. 사람 확인 0건, 사용할 수 있는 질문 4/16개. 검색 평가 완료 아님
- 혼합 모드 준비: `review-v2/selected-hybrid-v1/`. 사람 필수 확인 79건 대기(미확정 42 + 합의 표본 37)
- 결과 보고서: `review-v2/codex-ai-v1/labeling-report.md`
- 초안 거부 및 교정 기록: `review-v2/codex-ai-v1/execution-log.md`
- 검증: Python 평가 32건 + 검토 도구 68건 통과, 합성 브라우저 시나리오 13개(부모 포함 14건) 통과
- 재사용: 같은 스냅샷·질문·정책의 판정은 보관 후 재사용. 검색/프롬프트/모델의 중요한 변경 때만 전후 비교
