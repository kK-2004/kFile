## MODIFIED Requirements

### Requirement: SDK 独立构建与依赖约束

官方 Java SDK SHALL 位于仓库 `sdk/java/` 目录，作为独立 Maven 工程（`com.kk:content-center-sdk`）构建（`mvn -f sdk/java/pom.xml`），MUST NOT 加入服务端 reactor、MUST NOT 依赖 Spring；运行依赖仅 Jackson，HTTP 使用 JDK `java.net.http.HttpClient`，字节码目标 Java 17。SDK 根目录 SHALL 同时容纳 `java/`、`go/`、`python/` 三个语言子目录，根 `sdk/README.md` SHALL 提供语言入口与共享开放 API 契约导航。

#### Scenario: 独立构建成功

- **WHEN** 在仓库根目录执行 `mvn -f sdk/java/pom.xml clean verify`
- **THEN** Java SDK 构建与测试通过，产出可被其他应用以 `com.kk:content-center-sdk` 坐标引入的 jar；服务端既有构建（根 pom）不受影响

#### Scenario: 多语言目录边界清晰

- **WHEN** 查看 `sdk/` 目录
- **THEN** Java 工程文件位于 `sdk/java/`，Go 工程位于 `sdk/go/`，Python uv 工程位于 `sdk/python/`，根 README 指向三个语言 SDK，且 Java 构建不依赖 Go 或 Python 工具链

#### Scenario: 依赖保持轻量

- **WHEN** 查看 Java SDK 的依赖树
- **THEN** 编译期依赖不含 Spring 及重框架，仅 Jackson 与 JDK
