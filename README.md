# 통화녹음 전달 (CallRelay)

갤럭시 폰에서 통화가 끝나면 **자동으로 저장되는 통화 녹음 + 텍스트 변환 결과**를 감지해서
**이메일 · Microsoft Teams · 카카오톡 · Slack · Telegram · Discord · 사용자 웹훅**으로 자동 전송하는 안드로이드 앱입니다.

## 동작 방식

```
통화 종료 ──▶ 삼성 전화 앱이 녹음 저장 (Recordings/Call/통화 녹음 홍길동_241109_143012.m4a)
               │
   감지 ◀──────┘  ① 통화 종료(PHONE_STATE)  ② 새 오디오 파일(MediaStore 트리거)  ③ 15분 주기 검사
   │
   ├─ 같은 이름의 .txt 텍스트 파일을 찾음 (최대 N분 대기)
   ├─ 없으면 (선택) 음성인식 API로 변환
   │
   └─▶ 활성화된 모든 채널로 전송 (채널별 성공 기록 → 중복 전송 없음, 실패 채널만 재시도)
```

전송되는 내용: 상대 이름/번호(파일명에서 추출), 통화 일시, 통화 길이, 텍스트 내용, (선택) 녹음 파일 첨부.

### 갤럭시 텍스트 변환 결과에 대해
삼성 기종/One UI 버전에 따라 통화 텍스트(Galaxy AI 텍스트 변환)는 파일이 아니라 **삼성 앱 안에만 저장**되는 경우가 많습니다. 그래서 3가지 방법을 지원합니다.

| 방법 | 자동화 | 설명 |
|---|---|---|
| 텍스트 파일 매칭 | 완전 자동 | 녹음 폴더(또는 지정한 텍스트 폴더)에 같은 이름의 `.txt` 가 있으면 함께 전송 |
| 공유하기 | 원터치 | 삼성 녹음/통화 텍스트 화면 → **공유 → "통화녹음 전달로 보내기"** → 설정된 모든 채널로 즉시 전송 |
| 음성인식 API | 완전 자동 | 텍스트 파일이 없으면 OpenAI 호환 `/audio/transcriptions` (whisper-1, gpt-4o-transcribe 등)로 앱이 직접 변환 |

## 설치 / 빌드

- GitHub Actions(`.github/workflows/android.yml`)가 푸시마다 APK를 빌드합니다. Actions 실행 결과의 **CallRelay-apk** 아티팩트에서 APK를 받아 폰에 설치하세요 (출처를 알 수 없는 앱 설치 허용 필요).
- 직접 빌드: Android Studio로 열거나 `./gradlew assembleDebug` (JDK 17, Android SDK 35).
- 최소 Android 8.0, 대상 Android 15.

## 처음 설정

1. **삼성 전화 앱 → 설정 → 통화 녹음 → 자동으로 통화 녹음** 켜기 (텍스트 변환도 사용 중이면 그대로 둡니다).
2. 앱 실행 → **권한 허용**(전화 상태, 알림) → **배터리 최적화 제외** (삼성 절전 기능이 백그라운드 작업을 끊지 않도록).
3. **녹음 폴더 선택** → `내장 메모리/Recordings/Call` 선택 → "이 폴더 사용" 허용.
   선택한 시각 이후의 새 녹음만 보냅니다 ("최근 24시간 것도 보내기"로 소급 가능).
4. 사용할 채널을 켜고 값을 입력한 뒤 **테스트 전송**으로 확인합니다.

### 채널별 설정

| 채널 | 필요한 값 | 비고 |
|---|---|---|
| 이메일 | SMTP 서버/포트, 계정, 비밀번호, 받는 주소 | Gmail: 2단계 인증 후 **앱 비밀번호**. 네이버: 메일 환경설정 → POP3/IMAP → **IMAP/SMTP 사용**. 프리셋 버튼(Gmail/Naver/Daum/Outlook) 제공. 텍스트 .txt + 녹음 파일(20MB 이하) 첨부 |
| Teams | 웹훅 URL | 채널 → ⋯ → **워크플로** → "웹후크 요청이 수신되면 채널에 게시" 템플릿으로 URL 발급 (기존 Incoming Webhook URL도 동작). Adaptive Card로 게시 |
| 카카오톡 | REST API 키 (+ Client Secret) | 아래 참고 |
| Slack | Incoming Webhook URL | |
| Telegram | 봇 토큰, chat_id | 녹음 파일도 전송(50MB 이하) |
| Discord | 웹훅 URL | 2000자 단위로 나눠 전송, 녹음 첨부 선택 |
| 사용자 웹훅 | URL, (선택) Authorization 헤더 | JSON POST → Make/Zapier/n8n/Power Automate 로 **네이버웍스, 잔디, 구글 시트, 노션** 등 어디로든 연결 |

웹훅 JSON 형식:
```json
{
  "event": "call_recording",
  "contact": "홍길동",
  "datetime": "2024-11-09T14:30:12",
  "duration_sec": 312,
  "file_name": "통화 녹음 홍길동_241109_143012.m4a",
  "transcript": "...",
  "note": null,
  "subject": "[통화녹음] 홍길동 2024-11-09 14:30",
  "text": "사람이 읽기 좋은 전체 본문"
}
```

### 카카오톡 설정 (나에게 보내기)
1. [Kakao Developers](https://developers.kakao.com) → 내 애플리케이션 → 애플리케이션 추가.
2. **카카오 로그인** 활성화 → **Redirect URI** 에 `https://localhost/kakao-oauth` 등록 (앱 설정 값과 같아야 함).
3. **동의항목** → "카카오톡 메시지 전송(talk_message)" 사용 설정.
4. 앱 키의 **REST API 키**를 앱에 입력 (보안 → Client Secret을 켰다면 그 값도 입력) → **카카오 로그인**.

> 카카오 정책상 다른 사람/단톡방으로의 자동 전송은 비즈니스 검수가 필요해서, 이 앱은 **본인 카톡(나와의 채팅)** 으로 보냅니다.
> 텍스트 템플릿이 1건당 200자로 제한되어 긴 내용은 여러 건으로 나눠 보냅니다 (최대 건수 설정 가능). 전체 내용은 메일/Teams로 함께 받는 것을 권장합니다.

## 개인정보 / 보안 메모
- 모든 설정(비밀번호, 토큰)은 기기 내부 앱 저장소에만 보관되며 백업에서 제외됩니다(`allowBackup=false`).
- 통화 녹음은 상대방 개인정보가 포함됩니다. 전송 대상 채널의 접근 권한을 확인하고, 관련 법규·회사 정책을 지켜 사용하세요.
- 음성인식 API를 켜면 녹음 파일이 해당 API 제공자에게 업로드됩니다.

## 코드 구조
```
app/src/main/java/com/androiddream/callrelay/
├─ data/      Settings(설정), History(채널별 전송 상태·기록)
├─ scan/      DocumentScanner(SAF 폴더 조회), RecordingName(파일명 파싱·텍스트 매칭)
├─ relay/     Payload(메시지), Senders(채널별 전송), KakaoAuth(OAuth), Http
├─ stt/       Transcriber(음성인식 API)
├─ work/      RelayEngine(검사·전송 로직), Workers(WorkManager 스케줄링)
├─ receiver/  통화 종료 / 부팅 수신
└─ ui/        MainActivity(설정 화면), ShareReceiverActivity(공유 받기), KakaoLoginActivity
```
