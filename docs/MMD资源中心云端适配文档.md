# MMD 资源中心云端适配文档

## 1. 适配目标

Mod 资源中心新增 `MMD` 分类。云端提供项目列表和 ZIP 下载接口后，玩家可以下载、更新、离线查看和删除 MMD 项目。

客户端已兼容云端尚未上线的情况：`GET /api/v1/mmd/projects` 返回 `404` 时，仅将 MMD 列表视为空，不影响皮肤和音色同步。

## 2. 鉴权与通用响应

所有请求使用现有 API Key：

```http
Authorization: Bearer <api-key>
```

JSON 接口采用现有响应结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

- HTTP 成功状态必须为 `200`。
- `code` 存在时必须为 `0`。
- API 根地址必须使用 HTTPS；仅本机回环地址允许 HTTP。
- 下载地址只能是同源绝对 URL，或相对 URL。

## 3. MMD 项目列表

### 请求

```http
GET /api/v1/mmd/projects
Authorization: Bearer <api-key>
Accept: application/json
```

### 响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "projects": [
      {
        "id": "senbonzakura",
        "name": "千本樱",
        "type": "mmd",
        "format": "zip",
        "version": 1,
        "file_size": 8451276,
        "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "download_url": "/api/v1/mmd/projects/senbonzakura/download",
        "thumbnail_url": "",
        "is_public": 1,
        "is_owner": 0,
        "is_global": 1,
        "can_toggle": 0,
        "created_at": "2026-09-12T12:00:00Z",
        "updated_at": "2026-09-12T12:00:00Z"
      }
    ]
  }
}
```

### 字段约定

| 字段 | 必需 | 说明 |
|---|---:|---|
| `id` | 是 | 稳定且唯一的资源标识，1–96 字符，只允许 Unicode 字母、数字、`.`、`_`、`-` |
| `name` | 建议 | 用户可见名称；缺失时显示 `id` |
| `type` | 建议 | 固定为 `mmd`；缺失也兼容，其他值会被忽略 |
| `format` | 建议 | 当前固定为 `zip`；缺失按 ZIP 处理 |
| `version` | 建议 | 递增版本号，供后续更新判断 |
| `file_size` | 建议 | ZIP 字节数，用于界面显示 |
| `sha256` | 强烈建议 | ZIP 的小写十六进制 SHA-256；客户端下载后校验 |
| `download_url` | 否 | 可返回同源绝对地址、`/api/v1/...` 或相对地址；缺失时客户端使用标准下载路由 |
| `thumbnail_url` | 否 | 预留封面地址，当前 Mod 暂不显示 |
| `is_public` | 是 | 是否公开 |
| `is_owner` | 是 | 当前账号是否为所有者 |
| `is_global` | 是 | 是否为平台资源 |
| `can_toggle` | 否 | 与现有资源协议保持一致，当前 MMD 暂不使用 |
| `created_at` | 建议 | ISO-8601 时间字符串 |
| `updated_at` | 建议 | ISO-8601 时间字符串 |

布尔字段兼容 `0/1`、`true/false` 和 `null`；建议云端统一返回 `0/1`。

## 4. MMD 项目下载

### 请求

当 `download_url` 缺失时，客户端请求：

```http
GET /api/v1/mmd/projects/{id}/download
Authorization: Bearer <api-key>
Accept: application/zip
```

`id` 应按 URL path segment 安全编码。云端必须校验当前账号对资源的可见权限。

### 成功响应

```http
HTTP/1.1 200 OK
Content-Type: application/zip
Content-Length: 8451276
ETag: "资源版本或哈希"
```

建议同时返回：

```http
Content-Disposition: attachment; filename="senbonzakura.zip"
Cache-Control: private, max-age=300
X-Content-Type-Options: nosniff
```

### 错误状态

| HTTP | 含义 |
|---:|---|
| `401` | API Key 无效 |
| `403` | 当前账号无权查看或下载 |
| `404` | 资源不存在 |
| `413` | 资源过大 |
| `429` | 请求过于频繁 |
| `5xx` | 云端暂不可用 |

不要通过 `302` 跳转到第三方对象存储。当前客户端禁止重定向和跨源下载；如使用对象存储，请由同源接口代理文件流。

## 5. ZIP 内容规范

ZIP 根目录直接放置项目文件，不允许额外包一层文件夹：

```text
manifest.json
motion.vmd
camera.vmd
music.wav
```

允许扩展名：

- `.vmd`
- `.json`
- `.wav`
- `.ogg`
- `.mp3`

当前实际音频播放以 `.wav` 支持最稳定，云端审核应优先要求 WAV。

至少需要一个包含人物骨骼动作轨的 VMD。镜头和音乐均可选。

### `manifest.json` 示例

```json
{
  "name": "千本樱",
  "motions": ["motion.vmd"],
  "camera": "camera.vmd",
  "audio": "music.wav",
  "scale": 0.1,
  "loop": "once"
}
```

字段说明：

- `name`：项目显示名称。
- `motions`：人物动作 VMD 数组；单动作也兼容旧字段 `motion`。
- `camera`：镜头 VMD。
- `audio`：音乐文件。
- `scale`：模型动作位移缩放，默认 `0.1`，必须大于 0。
- `loop`：`once`、`project` 或 `independent`。

清单引用只能是 ZIP 根目录中的直接文件名，不允许子目录、绝对路径或 `..`。

## 6. 客户端安全限制

云端上传和审核应提前执行相同限制：

- ZIP 最大下载体积：256 MiB。
- 最多 128 个文件。
- 单文件解压后最大 192 MiB。
- 总解压体积最大 512 MiB。
- 单文件压缩比不得超过 100 倍。
- 拒绝绝对路径、Windows 盘符、`../`、反斜杠绕过及子目录。
- 拒绝未允许的扩展名。
- 拒绝损坏 VMD，以及没有人物动作轨的项目。
- `sha256` 存在时必须与下载内容一致。

## 7. 本地缓存与账号隔离

客户端按 API Key 的不可逆摘要隔离资源：

```text
config/verity_extended/resource-cache/<account-fingerprint>/mmd/
├─ archives/
│  └─ <id>.zip
└─ installed/
   └─ <id>/
      ├─ manifest.json
      ├─ motion.vmd
      └─ ...
```

缓存索引键为：

```text
mmd:<id>
```

本地手动资源仍位于：

```text
config/verity_extended/mmd/
```

客户端同时扫描手动目录和当前账号的云端安装目录。清除账号缓存不会删除手动资源。

## 8. 云端数据与权限建议

建议资源表至少包含：

```text
id
owner_user_id
name
type = mmd
format = zip
version
object_key
file_size
sha256
is_public
is_global
created_at
updated_at
```

列表查询规则：

```text
owner_user_id = 当前用户
OR is_public = 1
OR is_global = 1
```

下载接口必须再次执行同样的权限判断，不能只依赖列表隐藏。

建议上传流程：

1. 接收 ZIP 到临时存储。
2. 执行路径、数量、体积、压缩比和扩展名校验。
3. 解析全部 VMD，确认至少一个人物动作轨。
4. 校验 `manifest.json` 引用。
5. 计算 SHA-256 和文件大小。
6. 持久化对象和数据库记录。
7. 审核通过后才允许公开。

## 9. 上线检查清单

- 部署 `GET /api/v1/mmd/projects`。
- 部署 `GET /api/v1/mmd/projects/{id}/download`。
- 确保响应沿用 `code/message/data` 格式。
- 确保私有资源只对所有者可见。
- 确保下载不重定向到跨源地址。
- 确保列表 `sha256` 与实际 ZIP 一致。
- 使用无 MMD、公开 MMD、私有 MMD、平台 MMD 四类账号测试列表。
- 测试损坏包、超大包、Zip Slip、无动作轨和哈希错误。
- 旧 Mod 不会请求该端点；新 Mod 遇到端点 `404` 时仍可使用皮肤和音色。
