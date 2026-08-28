# AGENTS.md

本文件约束 AI 助手与协作者在本仓库中的行为。

## SDK 发布约束（必须遵守）

修改 `sdk/` 下任一 SDK 且需要发布时，**必须先做版本更新**，禁止沿用旧版本号重复发布：

| SDK | 版本所在位置 | 发布前必做 |
|-----|------------|-----------|
| sdk-java | `sdk/java/pom.xml` 的 `<version>` | 改为新的正式版本号（不允许 `-SNAPSHOT`） |
| sdk-go | 无版本文件，版本即发布时的 Git tag `sdk/go/vX.Y.Z` | 发布时在 `SDK Release` 工作流的 `version` 输入一个未使用过的新 SemVer |
| sdk-py | `sdk/python/pyproject.toml` 的 `project.version` | 同步改为新版本号；若发布时填写了 `version` 输入，两者必须一致 |

版本号遵循 SemVer（`X.Y.Z`）：不兼容改动升 X，向后兼容的功能新增升 Y，缺陷修复升 Z。
仅注释、文档（README 等）变化不需要升版本，也不应触发发布。

## 发布入口

- SDK 发布统一走 GitHub Actions 的 **`SDK Release`** 工作流：仅手动触发，
  勾选要发布的语言（可多选），Go 必填 `version`。
- `Deploy to Server` 工作流只负责应用部署，与 SDK 发布无关，不要把两者混在一起。

## 兜底校验

`SDK Release` 工作流内置重复版本拦截（GitHub Packages 查 `.pom` 是否已存在、
Git tag 是否已存在、PyPI 版本查询），未升版本会被直接拒绝。
但正确做法是在提交代码时就改好版本号，而不是依赖工作流报错兜底。
