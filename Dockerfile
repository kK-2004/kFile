## ==================== Stage 1: Maven build ====================
FROM crpi-g3stl1c9yrlh5x39.cn-beijing.personal.cr.aliyuncs.com/kk09/maven:latest AS builder

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

ARG MAVEN_PROFILE=prod
ARG SKIP_TESTS=true
ARG BUILD_NUMBER

ENV MAVEN_OPTS="-Xmx2048m -Xms1024m \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens=java.base/java.nio=ALL-UNNAMED"

COPY pom.xml ./
RUN mvn dependency:go-offline -B || true

COPY src ./src
RUN mvn -B -q -T 1C clean package \
    -DskipTests=${SKIP_TESTS} \
    -P${MAVEN_PROFILE} \
    -Dmaven.compiler.forceJavacCompilerUse=true \
    -Dmaven.compiler.source=21 -Dmaven.compiler.target=21 \
    -Dmaven.compiler.parameters=true

RUN find target -name "*-original.jar" -type f -delete && \
    cp $(ls -1 target/*.jar | head -n 1) /app.jar

## ==================== Stage 2: Runtime ====================
FROM crpi-g3stl1c9yrlh5x39.cn-beijing.personal.cr.aliyuncs.com/kk09/openjdk:21-jdk

LABEL authors="kk"
ARG BUILD_NUMBER
LABEL build.number=${BUILD_NUMBER}

WORKDIR /app

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

RUN groupadd -r spring && useradd -r -g spring spring && \
    mkdir -p /app/logs /app/config && chown -R spring:spring /app

COPY --from=builder --chown=spring:spring /app.jar /app/app.jar

USER spring:spring

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200" \
    PARAMS="" \
    SPRING_PROFILES_ACTIVE="prod" \
    TZ="Asia/Shanghai"

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom $JAVA_OPTS -jar /app/app.jar $PARAMS"]
