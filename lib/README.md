# lib/

放第三方依赖 jar 的地方。**这些文件不入库**（见根目录 `.gitignore`），需要自己准备。

本模组在这里需要：

| 文件 | 版本 | 来源 |
|---|---|---|
| `geckolib-forge-1.20.1-4.8.4.jar` | 4.8.4 | GeckoLib 官方发布（Modrinth / CurseForge） |

另外两个第三方依赖不在这个目录，而是 class 目录形式，见根目录 `README.md` 的「构建」一节。

`build.gradle` 里写死了这三个路径，所以文件名和目录位置要保持一致。
