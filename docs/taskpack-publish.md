# 上架任务包

作者给了 `.m7atask`，要让它出现在 App 的在线商店里。

## 1. 放文件

```
site/taskpacks/<id>.m7atask
```

文件名用包内 `task.json` 的 `id`，脚本按 `url` 最后一段找本地文件。

## 2. 改索引

`site/taskpacks/index.json` 的 `packs` 数组加一条：

```json
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
  "minAppVersionCode": 11
}
```

`sha256` 和 `size` 留空，脚本会算好写回去。`id` 必须与包内一致，否则脚本报错。

`urlFallback` 指向 GitHub raw，主源拉不动时用。

## 3. 签名

```bash
python scripts/sign_taskpack_index.py
```

补 `sha256` / `size`，核对 `id`，对 `index.json` 原始字节做 ECDSA 签名，输出 `index.json.sig`。

私钥路径读 `local.properties` 的 `maa.taskpackKey`。

客户端内置公钥验签，签名不对整份索引都不用，没有跳过选项。

## 4. 部署

```bash
python scripts/deploy_site.py
```

上传后会自动回读线上 `index.json` 与本地比对。

## 更新已有包

替换包文件，清空该条的 `sha256` 和 `size`，重跑签名脚本，再部署。
忘了重签名会报「sha256 不符」。

## 下架

从 `packs` 删掉那一条，重跑签名，部署。包文件留着无影响。

---

私钥 `taskpack-signing-key.pem` 丢了只能换密钥并发布新版 App，因为公钥硬编码在 `M7aApi.TASKPACK_INDEX_PUBKEY`。单独备份，不要入库。
