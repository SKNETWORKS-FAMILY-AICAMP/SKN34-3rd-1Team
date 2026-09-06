# 실데이터 검색 평가 산출물 보관

한 번의 실데이터 평가를 `runs/<runId>/` 하나에 보관합니다. **협업에 필요한 고정 자료는 Git으로 공유**하고,
새 실행·임시 파일은 기본적으로 제외합니다. `.gitignore`의 명시적 허용 목록으로 공유 범위를 관리합니다.
실제 공고 텍스트·문의처·판정 기록을 포함하므로 새 파일의 비밀정보·개인정보·공유 범위를 확인한 뒤 추가합니다.

현재 공유 자료는 [support-program-catalog-20260906-v1](support-program-catalog-20260906-v1/README.md)입니다.
공고 1,422건, 질문 16개, 321개 조합의 최초 AI 판정 1,605건과 미확정 42건의 추가 판정 210건을 포함합니다.
새 선택 결과는 합의 303건·미확정 18건이며, 기존 세 모드 결과와 사람 입력도 보존합니다.
저장소를 받은 뒤 API 키·DB·엑셀 없이 검증하고 이어가는 명령은 해당 안내를 따릅니다. 실제 검색 점수는 아직 없습니다.

권장 파일 구성은 다음과 같습니다.

재사용 도구와 AI-only·혼합·사람 검토 기준은 [검토 도구 안내](../review/README.md)에서 관리합니다.
현재 버전의 공고 스냅샷·판정 원본·보고서와 생성·변환 스크립트·회귀 테스트는 Git 포함 대상입니다.
XLSX·HTML·미리보기 이미지는 공유된 원본으로 다시 생성하므로 제외합니다. 브라우저 입력은 JSON으로 내보내야
다른 개발자가 이어받을 수 있습니다. Git 공유는 실시간 브라우저 동기화를 의미하지 않습니다.

- `fixture-unlabeled.json`: `evaluation-fixture-export`가 만든 기준일 고정 초안
- `fixture-labeled.json`: 선택한 방식으로 질문·정답을 판정한 파일. `labelReview.mode`에 출처 기록
- `query-set.json`: capture에 전달한 질문 묶음
- `capture.json`: 같은 기준일의 실제 후보·최종 추천 결과(v2)
- `run-manifest.md`: 실행 시각·기준일·커밋·모델·해시 기록
- `report.md`: 후보와 최종 추천 지표 및 오류 사례
- `review-v1/`, `review-final/`: 판정을 보존하는 버전별 CSV·manifest·XLSX
- `review-v2/web/index.html`: 엑셀 없이 여는 로컬 검토 화면
- `review-progress-*.json`: 브라우저에서 저장한 판정과 대화 출처. 원본 JSON을 보관하고 CSV는 도구로 변환
- `review-v2/codex-ai-v1/`: 고정 입력·정책·실제 하위 에이전트 배정·독립 판정 원본·수집 결과
- `review-v2/selected-ai-v1/`: 선택 모드의 CSV·selection.json·사람 입력 보존 파일과 검토 화면
- `review-v2/codex-ai-recheck-v1/`: 한 차례 추가 독립 판정 210건·원인 감사·원본 참조·보고서
- `review-v2/selected-ai-recheck-v1/`, `selected-hybrid-recheck-v1/`: 추가 판정이 반영된 현재 선택 결과

AI-only는 기존 사람 판정을 정답에 섞지 않습니다. 동일 자료의 판정은 한 번 보관한 뒤 검색 변경 때 재사용할 수
있습니다. 검토 방식이나 내용이 바뀌면 새 출력 경로에 생성합니다. AI 판정 파일만 완성했다고 실제 검색 지표를
측정한 것은 아니며, 실제 캡처 없이 Recall/MRR 결과를 만들어 기록하지 않습니다.

fixture와 capture의 `referenceDate`는 반드시 같아야 합니다. 날짜가 달라지면 `OPEN` 공고 집합도 달라질 수
있으므로 `evaluate.py`가 평가를 거부합니다. 생성 파일을 임의로 덮어쓰지 말고 새 `runId` 디렉터리를 만들어
보관합니다.
