# Séance Android App — 开发文档

> 目的：就算所有聊天记录都没了，看这份文档也能明白这个 APP 的结构、每个设计决定的原因、以及改动该动哪里。配合网页项目的 `SEANCE_DEV_NOTES.md`（在 `~/seance/` 和交付 zip 里）一起看。
> 最后更新：2026-09-18（v1.9）

---

## 1. 这是什么

Séance 网页版（`ace-step-studio-mobile.html`）的安卓壳。**它不是重写，是容器**：一个 Activity + 一个 WebView + 一个原生桥，网页代码 100% 复用。存在的唯一理由：手机 Chrome 对"网页访问局域网设备"的安全拦截（Local Network Access / HTTPS 优先 / 私有网络预检）越收越紧，用户隔三差五就被挡在门外；自家 APP 里这些浏览器政策全部可控。

- 仓库：github.com/lwd002/seance-app（私有），本地克隆 `~/seance-app`
- 构建：GitHub Actions 云端出 APK（本机不需要任何安卓工具链）
- 签名：debug 自签名，仅自用侧载，不上架

## 2. 文件结构

```
.github/workflows/build.yml    # push 到 main 自动构建 APK（artifact 名 seance-apk）
app/build.gradle               # versionCode/versionName 在这里改
app/src/main/
  AndroidManifest.xml          # INTERNET 权限 + usesCleartextTraffic
  java/com/seance/acestep/MainActivity.java   # 全部原生代码都在这一个文件里
  assets/ace-step-studio-mobile.html          # 网页本体（从 ~/seance 拷贝）
  assets/ace-step-studio.html                 # 桌面版（顺带打包，APP 未使用）
  res/                         # 图标（纯 XML 矢量，无 PNG）+ 应用名
```

## 3. 核心机制（每一条都是踩坑后的决定，别轻易改）

### 3.1 页面加载：WebViewAssetLoader 伪 https 源

页面不是用 `file://` 加载的，而是 `https://appassets.androidplatform.net/assets/...`（`WebViewAssetLoader` 在 `shouldInterceptRequest` 里把这个虚拟域映射到 APK assets）。**不要改回 file://**——file 源下 fetch/CORS/localStorage 行为都不可靠。

### 3.2 API 请求：全部走原生桥，不走 fetch（v1.1 的教训）

v1.0 直接让页面 fetch LAN 的 http API，结果：预检请求能到服务器（服务器日志有记录），正式响应被 WebView 客户端掐掉（新版 Chromium 对"安全源→私网地址"要求服务器响应 `Access-Control-Allow-Private-Network`，ACE-Step 没有），表现为红点连不上、无任何报错。混合内容放行（`MIXED_CONTENT_ALWAYS_ALLOW`）解决不了这一层。

**方案：Java 层发请求，浏览器政策整个绕开。** 协议如下：

- JS 调 `AndroidBridge.httpRequest(id, method, url, headersJson, bodyBase64)`（void，立即返回）
- Java 在线程池里用 `HttpURLConnection` 执行，完成后 `evaluateJavascript("window.__nativeHttpDone(id, status, base64Body)")` 回调
- JS 侧用 `{id → {resolve,reject}}` 的 pending 表把回调接回 Promise
- **status 为 0 表示原生层异常**（网络不通/地址错误），JS 侧转成 reject

网页侧的适配代码在 HTML 的 "Native HTTP adapter" 段：`NATIVE` 常量（检测 `window.AndroidBridge` 存在）决定 `apiRequest()` 走桥还是走原 fetch。**浏览器里 NATIVE=false，一切照旧——这段代码在 `~/seance/` 的源文件里维护，两个 HTML 都有，改动要保持同步（网页项目的双文件纪律照旧适用）。**

FormData（翻唱模式上传音频）的序列化技巧：`new Response(formData)` 会生成完整 multipart 编码，`r.headers.get('content-type')` 拿到含 boundary 的头，`r.arrayBuffer()` 拿到字节 → base64 交给桥。不需要手拼 multipart。

### 3.2.1 网络韧性：重试、连接复用、超时（v1.4–v1.7 的教训）

现场表现是**"时好时坏"**：服务器健康、Tailscale 也通，但隔一阵就 `Software caused connection abort` / `failed to connect`。三个原因叠在一起：

1. **陈旧连接**。`HttpURLConnection` 会把 TCP 连接放进池子复用；隧道或服务端悄悄关掉一条之后，下一次请求继承这条死 socket，**立刻**失败。
2. **全链路零重试**。Java 侧和 JS 的 `apiRequest` 都没有重试，一次抖动就是硬报错——而陈旧连接恰恰是"重试一次就好"的那种。
3. **错误被丢掉 + Toast 轰炸**。失败只回 `status 0` 和空 body（真实原因丢失），而且每次失败弹一个 Toast，轮询时网络一抖就是满屏"请求失败"。

现在的做法：

- **重试 3 次 + 退避**（500ms → 2s）。**GET / 下载可整段重来**（包括传到一半断流）；**POST 只在"服务器肯定没应答"时才重试**（`answered` 标志），否则丢失的响应会让同一个生成任务被提交两次。
- **连接复用保持开启**。v1.4 曾用 `http.keepAlive=false` 关掉它——确实治好了死 socket，但**每次轮询都要新建一条 TCP 穿隧道**（服务器日志里每个请求都是新端口），这些 churn 会**和几十 MB 的歌曲下载抢带宽**，表现为"歌拉不下来"。重试能兜住同一个故障，且便宜得多。
- **⚠️ 陷阱：成功之后不要调 `c.disconnect()`。** 它是关掉 socket、不是还给连接池；放在成功路径上（比如写在 `finally` 里）等于复用白开。**只有失败时才 disconnect**（丢掉坏连接），成功时靠关流把连接还回池子。v1.6 就是栽在这一句上——以为恢复了复用，其实没有。
- **连接超时 30s**（隧道从休眠唤醒 + 手机 doze，15s 不够）；读超时 API 120s、下载 300s。
- **真实错误透传**：`__nativeHttpDone(id, status, b64, err)` 加了第四个参数；Toast **8 秒最多弹一次**。
- `safeMsg` 对没有 message 的异常回落到异常类名——以前会给用户显示字面量 `null`。

### 3.2.2 连接状态：文字 + 自动巡检（v1.5）

原来只有一个 8px 的点，而且它唯一的文字标签是**屏幕阅读器专用、肉眼看不见**的，所以隧道断没断，界面长得一模一样——只能等下一次操作失败才发现。

- 状态直接写字：**连接中 / 已连接 / 重连中 / 断开**，并跟着变色。
- **自己巡检**：连着时 30s 一次、断了 5s 一次，所以隧道一恢复 APP 会自己回到"已连接"；切回前台立刻复查，退到后台停掉（省电）。
- **漏一次不报红**：原生桥底下已经重试过 3 次，所以单次失败先显示黄色"重连中"，连续失败才转"断开"。
- **点一下指示器**：弹出真实原因并立即重连。

### 3.3 音频回传：本地缓存中转，不走 base64

生成的歌可能几 MB 到几十 MB（WAV），用 evaluateJavascript 传 base64 会爆。方案：JS 调 `AndroidBridge.downloadToCache(id, url)` → Java 下载到 `filesDir/audio/<uuid>.<ext>` → 回调返回虚拟地址 `https://appassets.androidplatform.net/audio/<file>`（AssetLoader 加了第二个 PathHandler：`InternalStoragePathHandler` 指向该目录）→ JS 对这个**同源**地址正常 fetch 出 blob，后续播放/入库逻辑不变。缓存目录每次启动清空（页面自己在 IndexedDB 里有持久副本）。

### 3.3.1 缓存读取失败的兜底（v1.3）

实测中出现过：native 下载成功，但页面 `fetch` 那个虚拟地址失败 → 曲库缓存不了 → 下载按钮报"音频还未缓存完成"，同时弹出误导性的 CORS 提示。原因没有完全定位（asset loader 在某些机型/WebView 版本上服务内部存储文件不稳定），所以改成**双通道**：

1. 主通道：`fetch('https://appassets.androidplatform.net/audio/<file>')`（快，不占内存）
2. 兜底：主通道抛异常时自动调 `AndroidBridge.streamCachedFile(id, fileName)`，Java 按 256KB 分块 base64 推给 `window.__nativeChunk(id, b64)`，结束时 `__nativeChunkDone(id, ok, msg)`，JS 侧拼成 Blob。慢一些、占内存，但没有任何中间层。

同时 `fetchAsBlobTrack` 的 catch 现在把真实 `e.message` 存进 `job.cacheError` 并显示在卡片提示里——**不要再改回笼统的 CORS 文案**，那次排查就是被这句误导多花了时间。

### 3.3.2 ❌ 走错过的一条路：v1.6–1.8 的"立刻显示"改造（v1.9 已全部回退）

**结论先写：别再试这个方向。音频/播放请保持 3.3 / 3.3.1 的原样。**

起因是"生成完成后好久拉不下来"——原逻辑确实是在轮询回调里 `await` 整首下载完才 `renderJob`，等待期间界面是空的。v1.6 想让歌"先出现、先能播"，于是把 track 的 `playUrl` 直接设成**服务器地址**，缓存转后台。

**为什么必然错**：APP 里页面是 `https://appassets.androidplatform.net` 安全源，服务器是私网 `http://`——**正是 §3.2 里 WebView 拦掉的那一对**。播放器根本加载不了（"无法播放音频"），下载按钮也没有 blob（"音频还未缓存完成"）。等于把 v1.0 的老 bug 原样请了回来。

v1.7/v1.8 又围着这个错方向继续打补丁（播放中重绘会断歌 → 占位卡 → 单卡替换…），越补越复杂。最后是用户一句话点破：**"最早的版本放歌、下载都没问题，就是有网络问题，被你越调越差"**。于是 v1.9 直接 `git checkout 97a73ef -- ace-step-studio-mobile.html`，把网页整个退回 v1.5，**只保留 3.2.1 / 3.2.2 的网络修复**（那两节从头到尾没碰过音频，用户也确认"网络连通了"）。

**"等太久"真正该怎么解决**：不是改播放路径，是**改文件大小**——MP3 约 4MB、WAV 约 40MB，差十倍。输出格式下拉已按体积排序并标注，默认 MP3；无损版本本来就是在服务器上出、存 `LWD/music`，手机端只管试听。

**教训（写给未来的自己）**：
1. 动这个 APP 的音频路径前**先重读 §3.2**——"安全源 + 私网 http"是整个项目所有设计的起点，任何"直接用服务器地址"的想法在 APP 里都必然失败。
2. **用户说"以前是好的"时，先去 git 里找那个好版本并回退**，不要在坏掉的基础上继续加补丁。查 `git show <commit> -- <file> | grep` 就能看出哪一版动了哪条路。

### 3.4 文件上传选择器（v1.2 的教训）

`<input type="file">` 在 WebView 里默认是**死的**（点了没反应）——必须实现 `WebChromeClient.onShowFileChooser` → `startActivityForResult(params.createIntent())` → `onActivityResult` 里 `parseResult` 回传。v1.1 漏了这个，翻唱页两个上传框点不动。

### 3.5 歌曲下载：saveFile 桥

WebView 里 blob 的 `<a download>` 也是死的。网页的 `triggerDownload()` 里有 APP 分支：FileReader 转 base64 → `AndroidBridge.saveFile(name, base64, mime)` → Java 用 MediaStore 写入系统「下载」目录（API 29+ 免存储权限，这也是 minSdk 29 的原因）。

### 3.6 其他

- 返回键：`onBackPressed` 先 `webView.goBack()`
- `usesCleartextTraffic="true"`：Java 层的 http 明文请求也受系统网络安全策略管，这个 manifest 开关对原生请求同样必要
- 图标：纯 XML 矢量（adaptive icon），项目里没有任何二进制资源，全文本可 git

## 4. 版本史

| 版本 | 内容 |
|---|---|
| 1.0 | 首版壳（fetch 直连）——❌ 被 WebView 私网拦截，红点 |
| 1.1 | API/音频全部原生化（3.2/3.3） |
| 1.2 | 修上传框（3.4 onShowFileChooser） |
| 1.3 | 音频缓存加分块兜底（3.3）+ 真实错误上报；网页侧模型/步数自动匹配 |
| 1.4 | 原生桥网络韧性：重试+退避、超时放宽、真实错误透传、Toast 限流（3.2.1）|
| 1.5 | 连接状态改文字 + 自动巡检自愈、点一下看原因并重连（3.2.2）|
| 1.6 | 生成完成立刻显示、缓存转后台（3.3.2）；撤回 1.4 的关 keep-alive；输出格式按体积排序（MP3 约 4MB / WAV 约 40MB）|
| 1.7 | 修 1.6 自身的两个缺陷：播放中重绘会断歌、成功后 disconnect 让连接复用失效（3.2.1 / 3.3.2）|
| 1.8 | （同样是在错方向上打补丁，见 3.3.2）|
| 1.9 | ↩️ **网页音频路径整体回退到 v1.5**（v1.6–1.8 方向错误，见 3.3.2）；保留 3.2.1/3.2.2 网络修复；输出格式按体积标注、默认 MP3 |

## 5. 日常操作

**更新网页**：在 `~/seance/` 改好并验证（node --check 等，见网页项目笔记）→ `cp ~/seance/ace-step-studio*.html ~/seance-app/app/src/main/assets/` → 改 `app/build.gradle` 的 versionCode/versionName → commit + push → Actions 自动构建。

**拿 APK**：
```bash
cd ~/seance-app
RUNID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
gh run download $RUNID -n seance-apk -D ~/Downloads/seance-apk
```

**只改文档不想触发构建**：commit message 里加 `[skip ci]`。

**手机侧**：覆盖安装即可（versionCode 必须递增）；首次装要允许"未知来源"。

## 6. 已知限制 / 未来可做

- debug 签名：换手机重装没问题，但 versionCode 回退会装不上
- 大文件上传（翻唱源音频）走 base64 过桥，几十 MB 的源音频会慢/占内存——真遇到再优化（可改成 content:// URI 直读）
- 没有做设置项的原生备份，清 APP 数据 = 曲库和设置全丢（和网页版清浏览器数据同理）
- 如果以后 ACE-Step 服务端加了 `Access-Control-Allow-Private-Network` 响应头，理论上可以退回纯 fetch——但原生桥更稳，没必要退
