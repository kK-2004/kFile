## ==================== 第一阶段：Maven 构建 ====================
FROM crpi-g3stl1c9yrlh5x39.cn-beijing.personal.cr.aliyuncs.com/kk09/maven:latest AS builder

# Maven 配置（阿里云镜像）
RUN mkdir -p /root/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?>' > /root/.m2/settings.xml && \
    echo '<settings>' >> /root/.m2/settings.xml && \
    echo '  <mirrors>' >> /root/.m2/settings.xml && \
    echo '    <mirror>' >> /root/.m2/settings.xml && \
    echo '      <id>aliyun</id>' >> /root/.m2/settings.xml && \
    echo '      <mirrorOf>central</mirrorOf>' >> /root/.m2/settings.xml && \
    echo '      <url>https://maven.aliyun.com/repository/public</url>' >> /root/.m2/settings.xml && \
    echo '    </mirror>' >> /root/.m2/settings.xml && \
    echo '  </mirrors>' >> /root/.m2/settings.xml && \
    echo '</settings>' >> /root/.m2/settings.xml

WORKDIR /build

# 可传入构建参数
ARG MAVEN_PROFILE=prod
ARG SKIP_TESTS=true
ARG BUILD_NUMBER

# JDK21 + Lombok 相关参数
ENV MAVEN_OPTS="-Xmx2048m -Xms1024m \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens=java.base/java.nio=ALL-UNNAMED"

# 1) 复制 POM（利用缓存）
COPY pom.xml ./
RUN mvn dependency:go-offline -B || true

# 2) 复制源码和配置
COPY src ./src

# 3) 构建打包
RUN mvn -B -q -T 1C clean package \
    -DskipTests=${SKIP_TESTS} \
    -P${MAVEN_PROFILE} \
    -Dmaven.compiler.forceJavacCompilerUse=true \
    -Dmaven.compiler.source=21 -Dmaven.compiler.target=21 \
    -Dmaven.compiler.parameters=true

# 4) 处理产物（删除 original，复制主 jar 到根）
RUN find target -name "*-original.jar" -type f -delete && \
    cp $(ls -1 target/*.jar | head -n 1) /app.jar

## ==================== 第二阶段：运行时镜像 ====================
FROM crpi-g3stl1c9yrlh5x39.cn-beijing.personal.cr.aliyuncs.com/kk09/openjdk:21-jdk

LABEL authors="kk"
ARG BUILD_NUMBER
LABEL build.number=${BUILD_NUMBER}

WORKDIR /app

# 使用阿里云源并安装必要工具（tzdata + curl）
RUN sed -ri 's|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' \
           /etc/apt/sources.list.d/debian.sources && \
    sed -ri 's|http://security.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g' \
           /etc/apt/sources.list.d/debian.sources && \
    apt-get clean && \
    apt-get update && \
    apt-get install -y --no-install-recommends tzdata curl && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    dpkg-reconfigure -f noninteractive tzdata && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 非 root 用户运行
RUN groupadd -r spring && useradd -r -g spring spring && \
    mkdir -p /app/logs /app/config && chown -R spring:spring /app

# 复制 JAR 和配置文件（如果需要外部配置）
COPY --from=builder --chown=spring:spring /app.jar /app/app.jar
# 如果配置文件不在 JAR 中，取消下面这行的注释
# COPY --from=builder --chown=spring:spring /build/src/main/resources/application-prod.yml /app/config/

USER spring:spring

# 运行参数与环境
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200" \
    PARAMS="" \
    SPRING_PROFILES_ACTIVE="prod" \
    TZ="Asia/Shanghai"

# 应用端口
EXPOSE 8086

# 健康检查（修正端口）
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8086/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom $JAVA_OPTS -jar /app/app.jar $PARAMS"]