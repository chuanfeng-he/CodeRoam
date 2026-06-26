# CodeRoam

CodeRoam 是一个把 Android 手机连接到 Linux 主机 Codex CLI 的远程控制工具。
目标是让你离开电脑时，仍然可以在手机上继续查看任务输出、发送新问题、处理审批和接管正在运行的编码会话。

> 当前公开项目名是 **CodeRoam**。为了兼容旧安装，部分内部目录、服务名和协议标识仍保留 `codex-android-remote`。

## 使用环境

### 手机端

- Android 手机或平板。
- 可访问 relay 地址的网络环境。
- 安装 CodeRoam Android App。

### Linux 主机端

- Linux 主机，建议 Ubuntu/Debian。
- 已安装并登录可用的 Codex CLI。
- Node.js 18 或更新版本。
- 主机和手机都能访问同一个 relay URL。

### 构建环境

如果你从源码构建 Android App，还需要：

- JDK 17。
- Android SDK 或 Android Studio。
- Gradle 8.x。

## 部署方式

CodeRoam 由三部分组成：

- Android App：手机端界面。
- Linux bridge：运行在你的 Linux 主机上，负责连接 Codex CLI。
- Relay：WebSocket 转发服务，负责让手机和 Linux bridge 建立端到端加密通道。

### 方式一：局域网测试

适合手机和 Linux 主机在同一个局域网内测试。

在 Linux 主机上启动 relay：

```bash
npm install
HOST=0.0.0.0 PORT=9000 npm run relay
```

再启动 bridge：

```bash
npm run quickstart -- --relay-url ws://YOUR_LINUX_HOST_IP:9000/relay
```

执行成功后，会生成：

```text
codex-remote-pairing.html
codex-remote-pairing.png
codex-remote-pairing.json
codex-remote-pairing.svg
```

用手机 App 扫描 `codex-remote-pairing.png` 即可配对。

### 方式二：单机一键测试

如果你只想在一台 Linux 主机上快速验证，可以让脚本同时启动本地 relay 和 bridge：

```bash
bash scripts/bootstrap-ubuntu.sh --relay-url ws://YOUR_LINUX_HOST_IP:9000/relay --start-local-relay --local-relay-host 0.0.0.0 --local-relay-port 9000
```

这个命令会在 Debian/Ubuntu 上自动安装 Node.js，然后启动 relay、安装 bridge 服务并生成扫码文件。

### 方式三：公网部署

适合手机不在同一个局域网时使用。

推荐部署结构：

```text
Android App  <--WSS-->  公网 relay  <--WSS-->  Linux bridge  <--local-->  Codex CLI
```

公网 relay 应放在 HTTPS/WSS 后面，例如使用 Caddy、Nginx 或云服务商反向代理。

relay 可以用仓库里的 Docker 配置启动：

```bash
docker compose -f deploy/docker-compose.yml up -d
```

然后在 Linux 主机上配置 bridge：

```bash
npm install
npm run quickstart -- --relay-url wss://YOUR_DOMAIN/relay
```

### 停止或卸载

停止 bridge：

```bash
systemctl --user disable --now codex-android-remote.service
```

删除 bridge 服务文件：

```bash
rm -f ~/.config/systemd/user/codex-android-remote.service
systemctl --user daemon-reload
```

删除本机配对状态：

```bash
rm -rf ~/.config/codex-android-remote
```

删除本地生成的配对文件：

```bash
rm -f codex-remote-pairing.*
```

## 使用方式

1. 在 Linux 主机上确认 Codex CLI 可以正常使用：

```bash
codex
```

2. 启动 relay 和 Linux bridge。

3. 打开生成的 `codex-remote-pairing.html`，或者直接用手机扫描 `codex-remote-pairing.png`。

4. 手机 App 配对成功后，可以：

- 发送新的 Codex prompt。
- 查看 Codex 的输出、命令结果和运行状态。
- 处理需要确认的操作。
- 上传图片或文件作为上下文。
- 复制消息内容。
- 编辑已发送消息。编辑会回滚这次问题以及之后产生的回复，然后把原问题放回输入框。
- 在支持 shared app-server 的 Codex CLI 上，从手机接管桌面端会话。

5. 查看 bridge 状态：

```bash
systemctl --user status codex-android-remote.service
```

6. 查看实时日志：

```bash
journalctl --user -u codex-android-remote.service -f
```

## 注意事项

- `ws://127.0.0.1:9000/relay` 只适合 Linux 主机自己访问。手机连接时要使用手机能访问到的 IP、域名或公网 WSS 地址。
- 公网使用建议只用 `wss://`，不要裸露 `ws://`。
- Linux bridge 所在主机必须已经安装并登录 Codex CLI。只部署 relay 或 bridge 不等于 Codex 已可用。
- 如果 `systemctl --user` 不可用，quickstart 会退回后台进程，并把 `bridge.pid`、`bridge.log` 写入 `~/.config/codex-android-remote/`。
- shared app-server 模式依赖 Codex CLI 支持远程 socket。如果你的 Codex CLI 不支持，可改用 private 模式：

```bash
CODEX_REMOTE_APP_SERVER_MODE=private npm run quickstart -- --relay-url wss://YOUR_DOMAIN/relay
```

- 重新生成配对二维码后，旧二维码可能失效，应以最新生成的文件为准。

## 安全说明

- Relay 只转发加密消息，不应该保存或读取聊天内容。
- Codex 登录态、API key 和本地配置保留在 Linux 主机上，不需要放到手机端。
- `codex-remote-pairing.json`、二维码和配对 HTML 都等同于访问凭据，不要上传到公开仓库、聊天窗口或截图分享。
- 不要提交以下文件：

```text
codex-remote-pairing.*
android-app/local.properties
node_modules/
android-app/build/
android-app/app/build/
.env*
*.apk
*.aab
*.jks
*.keystore
device-state.json
auth.json
config.json
```

- 如果怀疑配对信息泄露，请删除 Linux 主机上的状态并重新配对：

```bash
rm -rf ~/.config/codex-android-remote
rm -f codex-remote-pairing.*
npm run quickstart -- --relay-url wss://YOUR_DOMAIN/relay
```

## 常见问题

### 手机扫了二维码但连不上

先确认手机能访问 relay URL。不要把 `127.0.0.1` 当成手机可访问地址；手机上的 `127.0.0.1` 指的是手机自己。

### 服务是 active，但 Codex 任务失败

检查 Linux 主机是否安装并登录 Codex CLI：

```bash
codex
```

如果日志里出现 `spawn codex ENOENT`，说明系统找不到 Codex CLI。

### 配对成功，但消息没有响应

查看 bridge 日志：

```bash
journalctl --user -u codex-android-remote.service -n 80 --no-pager
```

重点看 Codex CLI 是否报错、relay 是否断开、是否有认证或网络问题。

### 手机和电脑端会话不一致

优先使用 shared app-server 模式。它允许手机和桌面端连接同一个 Codex app-server socket。若你的 Codex CLI 不支持该能力，可以使用 private 模式，但 private 模式下每个移动端 worker 会更独立。

### 如何重新配对

在 Linux 主机上删除旧状态并重新运行 quickstart：

```bash
rm -rf ~/.config/codex-android-remote
rm -f codex-remote-pairing.*
npm run quickstart -- --relay-url wss://YOUR_DOMAIN/relay
```

然后用手机重新扫描新的二维码。

### 如何更新代码

更新源码后重新安装依赖并重启 bridge：

```bash
npm install
npm run quickstart -- --relay-url wss://YOUR_DOMAIN/relay
systemctl --user restart codex-android-remote.service
```
