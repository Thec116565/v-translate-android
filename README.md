# V-Translate Live Android (Ứng Dụng Độc Lập Cho Android)

Ứng dụng Android Native (**Kotlin + Jetpack Compose Material 3**) chuyên dịch trực tiếp phụ đề nổi & lồng tiếng AI theo thời gian thực khi bạn lướt xem **Douyin, TikTok, Bilibili, YouTube, Kuaishou, Xiaohongshu** hoặc bất kỳ trình duyệt/ứng dụng phát video nào trên điện thoại Android (Android 10+ / API 29+).

---

## 1. Tính Năng Cốt Lõi
1. **Hai Chế Độ Bắt Trực Tiếp (Dual-Capture Engine)**:
   - **📷 Quét Phụ Đề Màn Hình (`MediaProjection` + `ML Kit On-Device Multi-Script OCR`)**: Quét trực tiếp vùng phụ đề tiếng Trung/Anh/Nhật/Hàn trên ứng dụng Douyin/TikTok, tự động lọc khung hình tĩnh (`meanLumaDifference`) để tiết kiệm pin và chống dịch lặp khi chữ nhấp nháy (`TextDeduplicator`).
   - **🔊 Thu Âm Thanh Nội Bộ (`AudioPlaybackCapture` - Android 10+)**: Thu trực tiếp âm thanh phát ra từ ứng dụng video, tự động cắt câu theo khoảng nghỉ (`RMS VAD`) và gửi lên Gemini Flash dịch 1-Hop (`Audio -> Tiếng Việt`).
2. **Dịch Thuật Google AI Studio Pool (`GeminiPoolClient`)**:
   - Hỗ trợ nhập nhiều API Key (`AIza...`) và tự động xoay vòng qua các model `gemini-2.5-flash`, `gemini-2.5-flash-lite`, `gemini-2.0-flash`, `gemini-2.0-flash-lite` khi chạm giới hạn `429`.
   - Duy trì **Bộ nhớ ngữ cảnh cuốn chiếu 5 câu liên tiếp (Sliding Context)** giúp xưng hô chuẩn xác.
3. **Khung Phụ Đề Nổi Xuyên Chạm (`FloatingOverlayController`)**:
   - Hiển thị song ngữ (câu gốc + câu dịch in đậm nổi bật) đè lên trên ứng dụng Douyin.
   - Chế độ **🔒 Khóa xuyên chạm (`FLAG_NOT_TOUCHABLE`)** giúp bạn vuốt lướt video Douyin, thả tim, tạm dừng xuyên qua khung phụ đề mà không bị cản trở thao tác cảm ứng.
   - Có công tắc bật **Khung viền xanh căn chỉnh vùng OCR** trực quan ngay trên màn hình.
4. **Lồng Tiếng Trực Tiếp & Tự Động Hạ Nhỏ Tiếng Video Gốc (`TtsAudioDuckerSpeaker`)**:
   - Sử dụng `AudioFocusRequest` (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) giúp hệ điều hành Android **tự động hạ nhỏ tiếng ứng dụng Douyin/YouTube** khi giọng đọc tiếng Việt cất lên và trả lại 100% âm lượng ngay khi đọc xong câu.
   - Tự động tăng tốc độ đọc (`1.15x` -> `1.65x`) khi nhân vật trong video nói nhanh.

---

## 2. Cấu Trúc Dự Án
```text
v_translate_android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/vtranslate/live/
│       │       ├── domain/
│       │       │   ├── LiveModels.kt
│       │       │   ├── TextDeduplicator.kt
│       │       │   └── WavAudioEncoder.kt
│       │       ├── data/
│       │       │   ├── GeminiPoolClient.kt
│       │       │   └── SettingsRepository.kt
│       │       ├── service/
│       │       │   ├── FloatingOverlayController.kt
│       │       │   ├── LiveSessionStateBus.kt
│       │       │   ├── LiveTranslateForegroundService.kt
│       │       │   └── TtsAudioDuckerSpeaker.kt
│       │       └── ui/
│       │           └── MainActivity.kt
│       └── test/java/com/vtranslate/live/
│           └── CoreDomainUnitTest.kt
├── .github/workflows/build-apk.yml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 3. Cách Biên Dịch Ra File Cài Đặt `.APK`

### Cách 1: Mở trực tiếp bằng Android Studio (Khuyên dùng trên PC)
1. Mở **Android Studio** $\rightarrow$ chọn **Open** $\rightarrow$ trỏ tới thư mục:
   `c:\Users\Administrator\OneDrive\Desktop\mod\v_translate_android`
2. Đợi Gradle đồng bộ xong, chọn menu **Build $\rightarrow$ Build Bundle(s) / APK(s) $\rightarrow$ Build APK(s)**.
3. File `.apk` sẽ nằm tại: `app/build/outputs/apk/debug/app-debug.apk`. Copy file này sang điện thoại Android để cài đặt.

### Cách 2: Biên dịch tự động trên đám mây bằng GitHub Actions (Không cần cài Android SDK vào máy)
Dự án đã tích hợp sẵn workflow tại `.github/workflows/build-apk.yml`:
1. Đẩy thư mục `v_translate_android` lên 1 kho chứa (repository) trên GitHub của bạn.
2. Vào tab **Actions** trên GitHub $\rightarrow$ chọn workflow **Build V-Translate Live Android APK** $\rightarrow$ tải file `VTranslate-Live-Android-Debug-APK.zip` về điện thoại và cài đặt.
