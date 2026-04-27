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
        stage('Deploy') {
            when {
                branch 'main'
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
            cleanWs()
        }
    }
}
