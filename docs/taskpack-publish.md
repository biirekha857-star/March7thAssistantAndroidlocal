# 上架任务包

作者给你一个 `.m7atask`（或一份 `task.json`），你想让所有用户在 App 里直接搜到、一键安装。

整个流程是四步：**放进站点 → 改索引 → 签名 → 部署**。

> 少做任何一步都会失败，而且失败得很安静：
> 漏签名 = 客户端直接拒绝整个索引（连原有的包一起看不到）；
> 漏部署 = 本地改得再对，线上还是旧的。

---

## 1. 把包放进站点

```
site/taskpacks/<id>.m7atask
```

文件名建议直接用包的 `id`（就是 `task.json` 里的 `id` 字段），比如
`xiaoming.daily.m7atask`。脚本要靠 `url` 的最后一段找本地文件，名字对不上会报错。

## 2. 在索引里加一条

`site/taskpacks/index.json`：

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-11-05",
  "packs": [
    {
      "id": "xiaoming.daily",
      "name": "每日速刷",
      "author": "小明",
      "version": "1.0.0",
      "description": "清体力 + 领取奖励，约 6 分钟",
      "stepCount": 42,
      "url": "https://m7a.pages.dev/taskpacks/xiaoming.daily.m7atask",
      "urlFallback": "https://raw.githubusercontent.com/biirekha857-star/March7thAssistantAndroidlocal/main/site/taskpacks/xiaoming.daily.m7atask",
      "sha256": "",
      "size": 0,
      "minAppVersionCode": 12
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 必须和包内 `task.json` 的 `id` **完全一致**，否则脚本直接失败 |
| `name` | ✅ | 卡片标题 |
| `url` | ✅ | 完整地址，`https://m7a.pages.dev/taskpacks/<文件名>` |
| `urlFallback` | 建议 | GitHub raw 地址。Pages 被墙时自动切过去 |
| `sha256` / `size` | — | **留空**，脚本会算好写回去 |
| `stepCount` | 建议 | 卡片上显示「42 步」 |
| `minAppVersionCode` | — | 包用到新语法时填，老版本 App 会直接隐藏 |
| `author` / `version` / `description` | 建议 | 卡片副标题 |

## 3. 签名

```bash
python scripts/sign_taskpack_index.py
```

它会：

1. 按每条 `url` 的最后一段找到本地文件，算 `sha256` 和 `size` 写回索引
2. 打开包里的 `task.json`，核对 `id` 和索引是否一致
3. 对 `index.json` 的**原始字节**做 ECDSA 签名，写出 `index.json.sig`

私钥默认读 `../m7a-keystore/taskpack-signing-key.pem`（仓库外，不入库）。
换路径用 `--key`。

> **为什么必须签名**：索引里写的是「让用户的手机点哪里」。
> 客户端内置公钥验签，没有跳过开关 —— 签名不对就整份索引不用，
> 哪怕 Pages 被入侵、仓库被提 PR 也一样。

## 4. 部署

```bash
npx wrangler pages deploy site
```

或者 Cloudflare Dashboard → Pages → Create deployment → 拖 `site/` 进去。

---

## 更新已有包

改完包重新上传，**删掉索引里的 `sha256` 和 `size`**（或随便改一下 `version`），
再跑一次签名脚本 —— 它会重算指纹。忘了重签名的话，客户端会因为 hash 对不上拒绝安装，
报错是「sha256 不符」，不会告诉你「忘了跑脚本」。

## 下架

从 `packs` 数组里删掉那一条 → 重跑签名 → 部署。
包文件可以留着（不影响，只是没人引用）。

---

## 自查清单

- [ ] 包文件在 `site/taskpacks/`，且文件名 = `url` 最后一段
- [ ] 索引里的 `id` 和包里 `task.json` 的 `id` 一致
- [ ] 跑过 `sign_taskpack_index.py`，`index.json.sig` 有更新
- [ ] 部署了
- [ ] 在手机上「设置 → 任务包 → 在线」刷新，能看到新包
- [ ] 装一次，确认能跑

## 私钥

`taskpack-signing-key.pem` 丢了就只能**换密钥 + 发新版 App**（公钥硬编码在
`M7aApi.TASKPACK_INDEX_PUBKEY`）。备份它，别放进仓库。
