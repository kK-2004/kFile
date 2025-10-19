FROM crpi-g3stl1c9yrlh5x39.cn-beijing.personal.cr.aliyuncs.com/kk09/openjdk:21-jdk
LABEL authors="kk"

# App config and jar
COPY src/main/resources/application-docker.yml /app/application-docker.yml
COPY target/*.jar /app.jar

# 1. 使用阿里云 Debian 源，加速安装
RUN sed -ri 's|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' \
           /etc/apt/sources.list.d/debian.sources && \
    sed -ri 's|http://security.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g' \
           /etc/apt/sources.list.d/debian.sources && \
    # 2. 安装时区并设置为 Asia/Shanghai
    apt-get clean && \
    apt-get update && \
    apt-get install -y --no-install-recommends tzdata && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    dpkg-reconfigure -f noninteractive tzdata && \
    # 3. 清理缓存，减小镜像体积
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS="" \
    PARAMS=""

EXPOSE 8086

ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom $JAVA_OPTS -Dspring.config.additional-location=/app/application-docker.yml -jar /app.jar $PARAMS"]

