# MoeMusic SoundCloud Source

Standalone MoeMusic source for public SoundCloud tracks.

> [!NOTE]
> This project is fully vibe-coded, with limited manual review.

It uses the undocumented API used by SoundCloud's public web app and resolves a fresh progressive MP3 URL at playback time.
Snipped, encrypted, HLS-only, and account-restricted tracks are unavailable. 
Due to lack of api, no loudness normalization data can be provided. Expect lower volume for tracks from this source.

An optional web client ID can be set in the plugin config; otherwise the source discovers one from the public web app.

The resolution flow was checked against [yt-dlp's maintained SoundCloud extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/soundcloud.py) and Lavaplayer source.

Build with ./gradlew build. Install the generated full jar from build/libs into config/moemusic/plugins/.

---

# MoeMusic SoundCloud 音源

面向公开 SoundCloud 单曲的 MoeMusic 独立音源插件。

> [!NOTE]
> 本项目完全由 AI 生成，仅进行了基本的人工审阅。

插件使用 SoundCloud 公开网页所调用的 API，并在播放时解析新的渐进式 MP3 地址。试听片段、加密流、仅 HLS、需要帐号或受限的曲目不可用。
由于 API 限制，无法提供响度均衡数据，因此该平台的曲目播放时的音量可能略低。

配置中可以填写网页客户端 ID；留空时插件会从公开网页应用中自动发现。

解析流程参考了 [yt-dlp 持续维护的 SoundCloud 提取器](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/soundcloud.py) 和 Lavaplayer 的实现。

使用 ./gradlew build 构建，并将 build/libs 中生成的 full jar 放入 config/moemusic/plugins/。
