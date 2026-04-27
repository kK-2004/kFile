pipeline {
    agent any

    environment {
        APP_NAME                      = 'KFile-v2'
        OSS_AK                        = credentials('aliyun-oss-ak')
        OSS_SK                        = credentials('aliyun-oss-sk')
        SPRING_DATASOURCE_USERNAME     = credentials('kfile-db-username')
        SPRING_DATASOURCE_PASSWORD     = credentials('kfile-db-password')
    }

    triggers {
        githubPush()
    }

    stages {
        stage('Deploy') {
            when {
//                 branch 'main'        // 如果是普通流水线（非多分支），改用下面这行：
                expression { env.GIT_BRANCH == 'origin/main' }
            }
            steps {
                sh '''#!/usr/bin/env bash
                    set -e
                    chmod +x deploy.sh
                    ./deploy.sh
                '''
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
            node('') {
                cleanWs()
            }
        }
    }
}