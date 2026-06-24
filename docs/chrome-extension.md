# LinkPeek Chrome 扩展

`extensions/linkpeek-chrome/` 是一个无需编译的 Chrome MV3 扩展，用于个人本地安装。

## 安装

1. 打开 `chrome://extensions`。
2. 开启右上角 `Developer mode`。
3. 点击 `Load unpacked`。
4. 选择仓库里的 `extensions/linkpeek-chrome/` 目录。
5. 打开扩展详情页，在 `Keyboard shortcuts` 中确认或修改快捷键。

默认快捷键：

- macOS：`Command+Shift+L`
- 其他平台：`Ctrl+Shift+L`

如果 Options 页面里“当前快捷键”显示“未绑定”，请打开 `chrome://extensions/shortcuts` 手动给 `生成当前页面的 LinkPeek 链接` 设置快捷键。Chrome 在快捷键冲突或加载未打包扩展时，可能不会自动采用建议快捷键。

## 配置引导

Options 页面按两种使用场景组织：

- `使用 Cookie 同步`：需要配置 Base URL、授权 LinkPeek 域名、绑定快捷键、加载 Style，并在需要同步 Cookie 时填写 Admin 密码。
- `仅使用快捷键生成链接`：只需要配置 Base URL、授权 LinkPeek 域名、绑定快捷键、加载 Style、选择默认 Style 和测试 URL Scheme；不需要 Admin 密码。

引导步骤包含：

1. 配置 `Base URL`。
2. 授权 LinkPeek 域名。
3. 设置快捷键绑定。
4. 加载 Style。
5. 配置 Admin 密码（仅 Cookie 同步场景需要；可选）。
6. 测试 Admin 连接（可选）。
7. 选择默认 Style（可选）。
8. 测试联动 URL Scheme。

Chrome 扩展不能直接替用户写入快捷键。Options 页面会显示当前绑定状态，并提供按钮打开 `chrome://extensions/shortcuts`。

## 配置项

在扩展的 Options 页面填写：

- `Base URL`：LinkPeek 服务根地址，例如 `https://preview.example.com`。
- `Admin 密码`：仅 Cookie 同步需要。LinkPeek 管理后台密码保存在 Chrome 本地扩展存储中，仅建议在个人设备上使用。
- `默认 Style`：可留空；非空时生成 `/preview` 链接会追加 `style` 参数。
- `同步 LinuxDo Cookie` / `同步 NGA Cookie`：分别控制是否读取浏览器 Cookie 并写入 LinkPeek Provider 配置。
- `同步间隔`：默认 60 分钟。
- `执行快捷键后调起应用`：开启后，每次执行快捷键都会打开配置的 URL Scheme；链接生成失败只影响是否复制到剪贴板。
- `URL Scheme`：默认 `shortcuts://run-shortcut?name=LinkPeek联动打开`。
- `跳转页自动关闭延迟`：默认 10 秒，可配置 1 到 120 秒。

填写 `Base URL` 后，先点击 `授权域名`。扩展会按这个 LinkPeek origin 请求精确 host 权限。

## Cookie 同步

扩展读取浏览器里的登录 Cookie，并写入 LinkPeek 现有 Admin Provider 配置：

- LinuxDo：读取 `_t`、`cf_clearance`、`_forum_session`，写入 `linuxdo`。其中 `_t` 缺失时会跳过 LinuxDo；`cf_clearance` 和 `_forum_session` 有则同步，缺失时不阻断登录 Cookie 写入。
- NGA：读取 `ngaPassportUid`、`ngaPassportCid`，写入 `nga` 的 `NGA_PASSPORT_UID`、`NGA_PASSPORT_CID`。

第一版不修改 LinkPeek 后端。同步时扩展会打开一个非激活的临时 LinkPeek Admin 标签页，在同源上下文里完成登录和保存配置，然后关闭标签页。

如果某个平台缺少任一必需 Cookie，扩展会跳过该平台，不会用空值覆盖 LinkPeek 现有配置。LinuxDo 的 `cf_clearance` 可能是分区 Cookie，扩展会同时读取未分区 Cookie 和 `https://linux.do` 顶级站点下的分区 Cookie。

## 生成预览链接

快捷键会读取当前活动标签页 URL：

1. 调用 `${Base URL}/api/preview/support?url=...` 判断是否支持。
2. 支持时生成 `${Base URL}/preview?url=...`。
3. 默认 Style 非空时追加 `style` 参数。
4. 支持时把最终链接复制到剪贴板。
5. 如果启用调起应用，则无论链接是否复制成功，都会在后台打开扩展调起页，由调起页自动请求系统打开配置的 URL Scheme。

调起页默认不激活到前台。页面在配置的延迟内没有获得焦点会自动关闭；如果你手动打开该页，它仍会保留“重新调起”按钮。

扩展图标没有 popup。点击图标会打开 `${Base URL}/admin`；未配置 `Base URL` 时打开 Options 页面。

## 注意事项

- 建议使用 HTTPS 的 LinkPeek 地址。
- URL Scheme 调起依赖 Chrome 和系统对目标协议的处理；如果系统弹出确认框，扩展不会绕过。
- 本扩展面向个人本地安装，不需要 npm、打包或 Chrome Web Store 发布流程。
