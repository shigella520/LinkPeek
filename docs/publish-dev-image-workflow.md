# DEV 镜像发布工作流指南

本文面向 AI/CLI 操作，用于精确触发 `dev` 分支的 Docker 镜像发布 workflow。

当前 workflow 文件：

```text
.github/workflows/publish-dev-image.yml
```

发布目标：

```text
ghcr.io/shigella520/linkpeek:dev
ghcr.io/shigella520/linkpeek:sha-<commit>
```

## 前置事实

- GitHub remote 是 `git@github.com:shigella520/LinkPeek.git`。
- workflow 只能手动触发：`workflow_dispatch`。
- workflow 内部会强制校验 `GITHUB_REF == refs/heads/dev`。
- workflow 包含两个 job：
  - `verify`：运行 `./mvnw -B verify`
  - `docker`：登录 GHCR，构建并推送 `dev` 和 SHA tag
- 不要在 `main` 或其他分支触发；非 `dev` ref 会直接失败。

## 操作命令

所有命令都在仓库根目录执行。

### 1. 检查本地状态

```bash
git status --short
```

期望输出为空。如果有未提交改动，先停止发布，让用户确认是否提交、暂存或放弃这些改动。

### 2. 同步 `dev`

```bash
git fetch origin
git switch dev
git pull --ff-only origin dev
```

如果 `git pull --ff-only` 失败，说明本地 `dev` 与远端存在分叉。不要强推、不要 reset，先停止并报告。

### 3. 本地验证

```bash
./mvnw -B verify
```

本地验证失败时不要触发发布。先记录失败模块、测试名和关键错误。

### 4. 检查 GitHub CLI 登录

```bash
gh auth status
```

需要当前账号有仓库 Actions 触发权限和 GHCR package 写权限。认证失败时停止发布。

### 5. 触发 DEV 镜像发布

```bash
gh workflow run publish-dev-image.yml --ref dev
```

这条命令只排队触发 workflow，不代表发布成功。

### 6. 获取最新 run

```bash
gh run list \
  --workflow publish-dev-image.yml \
  --branch dev \
  --limit 1 \
  --json databaseId,status,conclusion,headSha,url
```

记录输出中的：

- `databaseId`
- `headSha`
- `url`

后续命令用 `databaseId` 作为 `<run-id>`。

### 7. 等待完成

```bash
gh run watch <run-id> --exit-status
```

如果命令以非 0 状态退出，发布失败。

### 8. 失败排查

```bash
gh run view <run-id> --log-failed
```

只需要总结失败 job、失败 step 和关键错误。不要重跑 workflow，除非用户明确要求。

### 9. 验证镜像

确认 workflow 成功后，可以拉取 `dev` tag：

```bash
docker pull ghcr.io/shigella520/linkpeek:dev
```

也可以按 run 输出中的 `headSha` 验证 SHA tag。`docker/metadata-action` 的 SHA tag 通常形如：

```text
ghcr.io/shigella520/linkpeek:sha-<short-sha>
```

## 一键发布脚本片段

只有在工作树干净、用户明确要求发布时才使用。

```bash
set -euo pipefail

git status --short
git fetch origin
git switch dev
git pull --ff-only origin dev
./mvnw -B verify
gh auth status
gh workflow run publish-dev-image.yml --ref dev

sleep 5
gh run list \
  --workflow publish-dev-image.yml \
  --branch dev \
  --limit 1 \
  --json databaseId,status,conclusion,headSha,url
```

拿到 `databaseId` 后继续：

```bash
gh run watch <run-id> --exit-status
```

## 禁止事项

- 不要在 `main`、feature 分支或 tag 上触发 `publish-dev-image.yml`。
- 不要修改 workflow 来绕过 `dev` 分支校验。
- 不要用 `git reset --hard`、强推或删除分支来处理同步问题。
- 不要在本地验证失败时触发发布。
- 不要把 `GITHUB_TOKEN`、Personal Access Token、API Key 或 GHCR 凭据写入文档、日志或命令输出。
