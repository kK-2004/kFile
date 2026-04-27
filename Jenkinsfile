pipeline {
    agent any

    environment {
        OSS_AK     = credentials('aliyun-oss-ak')
        OSS_SK     = credentials('aliyun-oss-sk')
        DB_USER    = credentials('kfile-db-username')
        DB_PASS    = credentials('kfile-db-password')
    }

    triggers {
        githubPush()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Frontend') {
            steps {
                dir('frontend') {
                    sh 'npm install'
                    sh 'npm run build'
                }
            }
        }

        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                sh """#!/bin/bash
                    export OSS_AK='${OSS_AK}'
                    export OSS_SK='${OSS_SK}'
                    export SPRING_DATASOURCE_USERNAME='${DB_USER}'
                    export SPRING_DATASOURCE_PASSWORD='${DB_PASS}'
                    chmod +x deploy.sh
                    ./deploy.sh
                """
            }
        }
    }

    post {
        success {
            echo '部署成功'
        }
        failure {
            echo '部署失败，请检查日志'
        }
        always {
            cleanWs()
        }
    }
}
