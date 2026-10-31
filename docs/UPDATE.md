# 更新与强制更新（运维手册）

站点是 **Cloudflare Pages 托管的一堆静态文件**，没有后端、没有接口、没有数据库。
客户端更新检查就是 `GET` 一份 Markdown，解析后和本机 `versionCode` 比大小。

```
https://m7a.pages.dev/update.md    ← 人写的，唯一要维护的更新清单
https://m7a.pages.dev/update.json  ← 脚本生成的旧格式，只为 0.5.7beta 以前的客户端
https://m7a.pages.dev/_headers     ← 告诉 Pages 别缓存上面两个文件
```

## 为什么用 md 而不是 json

和公告同一套机制：都是静态文件，改完重新部署就生效。

- 更新要点本来就是 Markdown（Release 正文），放进 md 的正文部分，不用在 JSON 里
  到处写 `\n` 转义；
- 浏览器打开就能读，发版时不用先下载再校验；
- 客户端解析只需要认 front matter 里六行 `key: value`，没有引入 YAML 库。

## update.md 的格式

```markdown
---
versionCode: 8
versionName: 0.5.7beta
apkUrl: https://github.com/.../releases/download/v0.5.7beta/March7thAssistant-0.5.7beta.apk
forceUpdate: false
minSupportedVersionCode: 1
publishedAt: 2026-10-31
---

**界面重构**：首页与任务页合并……

- 修复进入主界面必然崩溃的问题
- 修复每日首次上线领不到列车补给的问题
```

`---` 之间的部分是元信息，下面的全部内容就是弹窗里的更新要点（原样按 Markdown 渲染）。

| 字段 | 说明 |
|---|---|
| `versionCode` | 整数，和本机 `BuildConfig.VERSION_CODE` 比。**必须随每次发版递增** |
| `versionName` | 展示用，如 `0.5.7beta`，弹窗标题用它 |
| `apkUrl` | 下载直链，点「立即更新」用系统浏览器打开。写空会自动兜底到 Release 页 |
| `forceUpdate` | `true` = 强制更新 |
| `minSupportedVersionCode` | 低于它的版本一律强制更新（比 `forceUpdate` 更精细） |
| `publishedAt` | 发布日期，仅记录 |

只认 `key: value`，不认 YAML 的嵌套、数组、多行字符串 —— 字段就这几个，写复杂了反而
容易出问题。`versionCode` / `versionName` 缺失，或 `forceUpdate` 写成无法识别的值以外的情况，
整份清单会被判为无效，客户端自动回退到 `update.json`。

`forceUpdate` 的取值宽松：`true` / `True` / `yes` / `1` / `on` 都算开，宁可多拦一次，
也不能把「强制更新」悄悄降级成普通更新。

## update.json 还要不要留

**要留，但不要手改。** 0.5.7beta 以前的客户端只会解析 JSON，删掉它们就永远收不到更新提示 ——
连"强制更新"都推不到，等于把老用户永久遗留在旧版本上。

它是从 md 生成的：

```bash
python3 scripts/update_manifest.py          # 生成 site/update.json
python3 scripts/update_manifest.py --check  # CI / 提交前校验两份是否一致
```

所以日常只改 `update.md`，改完跑一次脚本。

## 缓存：为什么加了 `_headers`

Cloudflare 会缓存静态资源。改完 `update.md` 重新部署后，边缘节点上可能还留着旧副本，
用户几分钟内拿到的还是旧清单 —— 推了强制更新却因为缓存没生效，等于没推。

纯静态站没有服务器可以写 `Cache-Control`，所以用 Pages 的 `_headers` 文件声明：

```
/update.md
  Cache-Control: no-store, must-revalidate
```

部署时 Pages 会自动把它翻译成响应头。客户端请求时也带了 `Cache-Control: no-cache`，
两边一起保证拿到的是最新内容。

## 弹窗长什么样

- 标题：`发现新版本 0.5.7beta`
- 副标题：`当前 0.5.6beta → 最新 0.5.7beta`
- 正文：md 的正文部分，渲染成 Markdown（列表、加粗、链接都支持）
- 按钮：`立即更新`（打开 `apkUrl`）+ `稍后再说`

所以正文直接抄 Release 说明就行，不用另写一份。

## 强制更新怎么开

两个开关，二选一或都用：

```yaml
# ① 对所有旧版本强制
forceUpdate: true

# ② 只淘汰过旧版本：比如只保留 0.5.0beta 之后的
minSupportedVersionCode: 5
```

打开后客户端的行为：

| 行为 | 普通更新 | 强制更新 |
|---|---|---|
| 「稍后再说」按钮 | 有 | 没有 |
| 点弹窗外面 | 关闭 | 不关 |
| 返回键 | 关闭 | 不关 |
| 弹窗盖住激活向导 | 否 | 是 |

**推之前一定先自测**：这两个字段写错、`apkUrl` 又是死链时，用户会被卡在一个点不动的
弹窗上，什么都做不了。所以推送前先在自己手机上点一次「立即更新」，确认能下载、能安装，
再改线上清单。

## 清单没有签名校验

清单是 HTTPS 直接拉取的，没有额外签名。要防中间人篡改，得再补一层签名，目前没做。

## 发版顺序（重要）

1. 打包 APK，确认 `versionCode` / `versionName` 已递增（`app/build.gradle.kts` 里硬编码）；
2. 上传到 GitHub Release，**确认 `apkUrl` 那个地址真能下载**；
3. 改 `site/update.md`（版本号、`apkUrl`、正文更新要点）；
4. 跑 `python3 scripts/update_manifest.py` 重新生成 `update.json`；
5. 部署 `site/` 目录到 Cloudflare Pages；
6. 浏览器打开 `https://m7a.pages.dev/update.md` 确认是新版本 —— 部署是秒级的，
   但**这一步一定要做**，否则可能是在给用户推一个 404。

顺序反了会出现「更新提示指向 404」——所有已经装了旧版的用户点进去都是死链。

## 怎么部署这个静态站

三种方式，选一种即可：

| 方式 | 操作 | 适用 |
|---|---|---|
| 拖拽上传 | Cloudflare Dashboard → Workers & Pages → 项目 → Create deployment → 上传整个 `site/` 目录 | 偶尔发版 |
| 连 Git 仓库 | 项目设置里绑定仓库，Build output directory 填 `site`，之后 push 自动部署 | 想省事 |
| wrangler CLI | `npx wrangler pages deploy site --project-name m7a` | 本地一条命令 |

注意上传的是 **`site/` 里面的文件**，不是 `site` 这个文件夹本身 ——
`index.html` 必须落在站点根目录。
