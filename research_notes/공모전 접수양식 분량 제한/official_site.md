# 공식 사이트(appdevchallenge.auto-hmg.io)의 접수양식·첨부 분량/형식/용량 규정

조사일: 2026-11-10. 방법: `curl -sS -L`로 홈에서 링크된 모든 내부 페이지(HTML과 Next.js `self.__next_f` 데이터 모두), 공지 상세 3건, 내려받기 양식(DOCX)을 받아 글자만 뽑아 "분량·글자·페이지·장·용량·MB·형식·PDF·HWP·DOCX·분 이내·길이·별지·파일명·첨부·제한" 등을 검색했다. 페이지에서 쓰는 JS 묶음 11개도 같은 단어로 검색했다(해당 문구 없음, 코드 일치 1건은 오탐).

확인한 페이지(전부 HTTP 200): `/`, `/guide`, `/guide/overview`, `/guide/schedule`, `/guide/shortform`, `/guide/submission`, `/guide/development`, `/guide/judging-awards`, `/guide/rules`, `/guide/help`, `/faq`, `/qna`, `/notice`, `/notice/c0a1a000-2026-4923-8000-000000000001`(모집 공고), `…0005`(설명회), `…0006`(참가 및 출품 유의사항), `/terms`, `/privacy`, `/downloads/contest-application-consent.docx`(접수양식, 문서 속성 수정일 2026-10-20). 공지 목록은 "전체 3건"이고, 다른 번호(`…0002~0004`, `…0007~0010`)는 홈 화면과 같은 내용이 돌아와 없는 공지로 판단했다.

## 1. 접수양식(기획서) 전체 분량·항목별 글자 수 제한이 있는가?

### Takeaway
없다. 분량 제한이 없다는 명시 문장은 **사이트 웹페이지가 아니라 내려받는 접수양식(DOCX) 안에만** 있다("소개 자료 분량 제한은 없습니다."). 웹페이지 어디에도 글자 수·페이지·장수 제한은 없고, 오히려 양식은 "필요 시 별지를 추가할 수 있습니다"라고 허용한다.

### Cited Findings
- 접수양식 02 섹션 머리말 원문: "Word에서는 박스 안을 클릭해 입력하세요. 여러 줄 항목은 4줄 정도 작성할 수 있으며, 내용이 길면 작성란이 늘어납니다." 바로 다음 줄: "소개 자료 분량 제한은 없습니다." — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx)
  - 맥락: 이 두 문장은 "02 앱 프로젝트 소개 / 심사 자료" 제목과 "대표자·구성원의 성명, 연락처, 이메일, 소속은 기재하지 않습니다. 해당 정보는 01 참가신청서에만 기재해 주세요." 바로 아래, "앱명 / 프로젝트명" 칸 위에 있다. 즉 1~8번 항목 전체에 걸리는 안내다.
  - "4줄 정도"는 제한이 아니라 칸의 기본 크기 설명이다(같은 문장에서 "내용이 길면 작성란이 늘어납니다").
- 같은 섹션 끝 원문: "공유 링크와 첨부자료에도 개인정보·계정정보·개발자 실명이 노출되지 않도록 확인하세요. 상세 기술요건은 공식 안내를 따르며, 필요 시 별지를 추가할 수 있습니다." — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx)
- 항목 1~8의 안내문(글자 수 지시 없음, 원문 그대로): "1. 앱 한 줄 소개 앱을 한 문장으로 소개해 주세요." / "2. 기획 배경 어떤 고객의 니즈나 불편한 순간에 주목했으며, 왜 해결이 필요한지 작성해 주세요." / "3. 앱 주요 기능 및 차량 특화 요소 …" / "4. 주요 사용 시나리오 누가, 어떤 상황에서, 어떻게 앱을 사용하는지 구체적인 이용 흐름을 설명해 주세요." / "5. 개발 계획 Pleos Connect 환경에서 앱을 어떻게 구현할지 주요 개발 범위와 SDK/API 사용계획을 구체적으로 작성해 주세요." / "6. 기대효과 및 발전 가능성 … (타깃 고객 분석, 예상 MAU, Retention Rate, 향후 서비스 확대 계획 등)" / "7. 참고자료(선택 · 가산점 부여) 앱 화면 시안, 시연 영상, GitHub 저장소 등 … 파일 또는 링크로 제출해 주세요" / "8. 숏폼 링크(선택 · 가산점 없음) … 게시한 세로 방향 영상의 링크를 기재해 이메일로 제출합니다." — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx)
  - 1번만 "한 문장으로"라는 형태 지시가 있다(글자 수 아님).
- `/guide/submission` 웹페이지에는 "분량 제한" 문장이 **없다**. 이 페이지의 관련 원문은 "접수양식이 곧 기획서입니다. 작성한 접수양식을 준비해 주세요." 와 제출물 표 "01접수양식(기획서)필수 / 접수양식에 아이디어와 기획내용 작성" 뿐이다. — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
- FAQ "어떤 파일을 내야 하나요?" 답: "작성한 접수양식이 곧 기획서입니다." / "기획서만으로도 지원할 수 있나요?" 답: "접수양식(기획서)만으로 지원할 수 있습니다." 분량 언급 없음. — [FAQ](https://appdevchallenge.auto-hmg.io/faq)
- 참가 규정: "접수양식(기획서)만으로 지원할 수 있습니다." 분량 언급 없음. — [참가 규정](https://appdevchallenge.auto-hmg.io/guide/rules)
- 이용약관 제6조(출품작의 작성과 제출)는 수정 가능 시점·마감만 정하고 분량·형식은 정하지 않는다: "1. 지원자는 접수 마감 시각 전까지 출품작을 여러 차례 수정할 수 있습니다. 2. 마감 시각 이후에는 제출, 제출 취소, 수정이 모두 불가능합니다." — [이용약관](https://appdevchallenge.auto-hmg.io/terms)

### Inferences
- 과제 설명의 "이미 알려진 사실" 중 "/guide/submission도 분량 제한 없음이라고 쓴다"는 부분은 사실과 다르다. 그 문장은 양식(DOCX)에만 있다. 웹페이지는 분량에 대해 아무 말도 하지 않는다(언급 없음 = 제한 없음과 모순되지 않음).
- 양식 칸을 넘겨 길게 써도 되고, 별지를 붙여도 된다. 다만 "세부 심사기준 및 과정은 공개하지 않습니다"(공지 0006)이므로 길이가 점수에 어떻게 작용하는지는 알 수 없다.

### Gaps
- 별지의 형식(같은 파일 뒤에 붙이기 / 별도 파일)에 대한 안내는 없다.
- 운영국 이메일 답변이나 설명회(10-28) 다시보기 영상 속 발언은 확인하지 않았다(사이트 텍스트 범위 밖).

## 2. 접수양식 파일 형식(DOCX/PDF/HWP) 규정이 있는가?

### Takeaway
제출 파일 형식을 정한 문장은 없다. 양식이 DOCX로 배포되고 "Word에서는 박스 안을 클릭해 입력하세요"라고 쓸 뿐, "DOCX로 제출" 또는 "PDF로 변환해 제출" 같은 요구는 어디에도 없다. 서명이 필요하다는 점만 정해져 있다.

### Cited Findings
- "공모전 접수양식 · 개인정보 수집·이용 동의서 다운로드 (DOCX)" — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
- "03 · 이메일 제출 … 작성·서명한 접수양식(기획서)과 선택 제출물을 아래 주소로 보내 주세요." — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
- "※ 이 접수양식이 곧 기획서입니다. 작성·서명한 접수양식을 이메일로 제출합니다." — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx)
- "Word에서는 박스 안을 클릭해 입력하세요." (Word 사용을 전제로 한 입력 방법 안내, 형식 강제 아님) — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx)
- 파일 손상 책임: "신청기간 마감일 이후에 제출된 서류는 인정되지 않으며, 지원자의 실수로 인한 파일 오류와 파손 등으로 발생되는 모든 책임은 지원자에게 있습니다" — [공지: 공모전 참가 및 출품 유의사항 안내](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000006)
- 전 페이지·JS 검색에서 "PDF", "HWP", "파일 형식", "제출 형식" 문구는 0건(개인정보처리방침의 "별지 제11호 서식" 위임장 문구는 무관).

### Inferences
- 형식은 자유로 해석된다(언급 없음). 서명·레이아웃 깨짐 위험을 줄이려면 DOCX 원본 + PDF 사본을 같이 보내는 방식이 공식 규정과 충돌하지 않는다(추론, 공식 안내 아님).

### Gaps
- "서명"을 손글씨 스캔·이미지 서명·전자서명 중 무엇으로 해야 하는지 방법 규정 없음.

## 3. 첨부물(시연 영상, GitHub, APK, 이메일)의 길이·용량·파일명 규정

### Takeaway
정해진 것은 두 가지뿐이다: **APK 파일명 `팀명_앱이름_release.apk`, 저장소 `apk/` 폴더**, 그리고 **GitHub 용량 초과 시 GitHub Releases 주소 제출**. 시연 영상 길이·용량, 이메일 첨부 용량, 대용량 첨부 방법, 접수양식 파일명은 언급이 없다. "30초~1분"은 숏폼 챌린지 영상에만 해당한다.

### Cited Findings
- APK 파일명: "APK 파일명 / 팀명_앱이름_release.apk" — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
- 위치: "APK 파일은 저장소 내 apk/ 폴더에 업로드합니다." / "저장소 주소와 APK 파일 경로를 제출 페이지에 입력합니다." — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
  - 주의: "제출 페이지에 입력"이라고 쓰여 있지만 사이트에는 입력 페이지가 없고 접수는 이메일이다(같은 페이지 "제출물을 준비한 후, 아래 안내에 따라 이메일로 접수해 주세요."). 모호한 문장.
- 용량 관련 유일한 문장: "APK 파일이 GitHub 업로드 용량을 초과하는 경우, GitHub Releases에 등록한 후 해당 Release 페이지 주소를 제출해 주세요." (구체적 MB 수치 없음) — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
- "APK는 실행 가능한 release 버전으로 제출해 주세요." — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
- APK 단독 제출 금지: "APK 파일만 따로 제출하지 않고, GitHub 저장소에 소스 코드와 APK 파일을 함께 업로드해 제출해 주세요." — [FAQ](https://appdevchallenge.auto-hmg.io/faq); 같은 문장 [모집 공고](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000001)
- README 필수 내용: "README.md에는 아래 내용을 작성해 주세요. 앱 이름 및 한 줄 소개 / 실행 방법 / 테스트 환경 및 필요한 설정 / APK 파일 위치 또는 GitHub Release 링크" — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)
- 시연 영상: 길이·용량·형식 없이 인정 범위만 있음. "02시연 영상선택 / 목업 · 프로토타입 · 화면 녹화 인정" — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission); "프로토타입 시연 영상 (화면 녹화 포함)" — [심사·시상](https://appdevchallenge.auto-hmg.io/guide/judging-awards); "(목업, 프로토타입, 화면 녹화 모두 인정, 함께 제출하면 가산점)" — [모집 공고](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000001)
- 제출 방식: 양식 7번 "파일 또는 링크로 제출해 주세요" — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx)
- 숏폼 영상 길이(본 심사 영상 아님): "출품할 앱의 아이디어나 화면을 30초~1분 이내 길이의 세로 방향 영상으로 담습니다" — [숏폼 챌린지](https://appdevchallenge.auto-hmg.io/guide/shortform); FAQ도 "30초~1분 이내 세로 방향 숏폼 영상(릴스/쇼츠)" — [FAQ](https://appdevchallenge.auto-hmg.io/faq). 게시 조건: "필수 지정 해시태그 1개를 포함하여 총 2~3개의 해시태그를 본문 또는 설명란에 달아주셔야 정상 참여로 인정됩니다." 해시태그 "#HMG차량용앱개발공모전" — [숏폼 챌린지](https://appdevchallenge.auto-hmg.io/guide/shortform)
- 이메일 규칙(용량 언급 없음): 주소 "appdevchallenge@hyundai-partners.com", 제목 "[차량용 앱 개발 공모전] 팀명(개인은 성명)_대표자명", 수정 시 "[수정제출] [차량용 앱 개발 공모전] 팀명(개인은 성명)_대표자명", "수정 제출 시에도 전체 제출 자료와 서명한 동의서를 모두 첨부하고, 팀원 전원을 참조(CC)해 주세요." — [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission), [참가 규정](https://appdevchallenge.auto-hmg.io/guide/rules)
- 재제출 유효 조건: "출품작 수정 및 재제출 시에는 기존 파일을 포함한 전체 제출물과 서명 완료된 개인정보 동의서를 다시 첨부하고, 팀원 전원을 이메일 참조(CC)에 포함하여 발송해 주셔야 최종 유효 접수로 인정됩니다" — [공지 0006](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000006)
- 마감 시각: "접수기간: 2026.10.21(수) ~ 2026.11.15(일) 23:59까지 지원접수" — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx); "모든 날짜는 한국 시간(KST) 기준입니다." — [일정·설명회](https://appdevchallenge.auto-hmg.io/guide/schedule)
- 링크·첨부의 개인정보: "공유 링크와 첨부자료에도 개인정보·계정정보·개발자 실명이 노출되지 않도록 확인하세요." — [접수양식 DOCX](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx)

### Inferences
- 이메일 첨부 용량 규정이 없으므로, 큰 파일(시연 영상, 90MB급 APK)은 메일에 직접 붙이기보다 링크(GitHub Releases, 영상 공유 링크)로 내는 편이 공식 안내("파일 또는 링크", "GitHub Releases … 주소를 제출")와 맞고 전송 실패 위험이 적다. 수신 서버(hyundai-partners.com)의 실제 첨부 한도는 사이트에 없다(추론).
- 공식 파일명 규칙은 APK에만 있다. 접수양식·영상 파일명은 자유(언급 없음).

### Gaps
- 시연 영상의 길이·해상도·형식·용량: 언급 없음.
- 이메일 첨부 최대 용량, 대용량 첨부(클라우드 링크) 허용 여부: 언급 없음("파일 또는 링크"만 있음).
- "공지된 심사용 GitHub 계정": 사이트 공지 3건 어디에도 계정 이름이 없다(비공개 저장소일 때만 필요, 이 프로젝트는 공개 저장소라 영향 없음).

## 판정 요약

| 항목 | 판정 | 근거 |
|---|---|---|
| 기획서 전체 분량 | 명시: 제한 없음 | DOCX "소개 자료 분량 제한은 없습니다." |
| 항목별 글자 수 | 명시: 칸은 4줄 정도, 길면 늘어남(제한 아님) | DOCX 02 머리말 |
| 별지 | 명시: 허용 | DOCX "필요 시 별지를 추가할 수 있습니다." |
| 1번 한 줄 소개 | 형태 지시만("한 문장으로") | DOCX |
| 기획서 파일 형식(DOCX/PDF/HWP) | 언급 없음 | 전 페이지 검색 0건 |
| 기획서 파일명 | 언급 없음 | — |
| 이메일 제목 | 명시 | /guide/submission |
| 이메일 첨부 용량 | 언급 없음 | — |
| 시연 영상 길이·용량·형식 | 언급 없음(목업·프로토타입·화면 녹화 인정) | /guide/submission |
| 숏폼 길이 | 명시: 30초~1분 이내, 세로 | /guide/shortform, /faq |
| APK 파일명·위치 | 명시: `팀명_앱이름_release.apk`, `apk/` | /guide/submission |
| APK 용량 | 모호: "GitHub 업로드 용량 초과 시 Releases" (수치 없음) | /guide/submission |
| 웹페이지의 "분량 제한 없음" 문구 | 없음(DOCX에만 있음) | /guide/submission 전문 |
