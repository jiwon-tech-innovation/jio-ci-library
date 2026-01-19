// vars/jiaaServicePipeline.groovy
def call(Map config = [:]) {
    pipeline {
        agent any
        
        // 1. 필요한 도구 정의
        tools {
            jdk 'JDK21_corretto'
        }
        
        environment {
            ECR_REGISTRY = '541673202749.dkr.ecr.ap-northeast-2.amazonaws.com'
            // config.appName이 없으면 자동으로 저장소 이름을 사용
            SERVICE_NAME = "${config.appName ?: env.JOB_BASE_NAME}"
            IMAGE_NAME = "jiaa/${SERVICE_NAME}"
        }

        stages {
            // 2. 단위 테스트 (폴더 이동할 필요 없음)
            stage('Unit Test') {
                steps {
                    sh "chmod +x gradlew"
                    sh "./gradlew test --no-daemon"
                }
                post {
                    always { junit 'build/test-results/test/*.xml' }
                }
            }

            // 3. 빌드
            stage('Build JAR') {
                steps {
                    sh "./gradlew bootJar --no-daemon -x test"
                }
            }

            // 4. Kaniko 빌드 & 배포 (K8s Pod 동적 생성)
            stage('Docker Build & Push') {
                agent {
                    kubernetes {
                        yaml libraryResource('pod-templates/kaniko-pod.yaml') 
                    }
                }
                steps {
                    script {
                        container('kaniko') {
                            // Git Clone 필요 (새 Pod니까)
                            checkout scm
                            
                            sh """
                                /kaniko/executor \\
                                --context=dir://${env.WORKSPACE} \\
                                --dockerfile=${env.WORKSPACE}/Dockerfile \\
                                --destination=${ECR_REGISTRY}/${IMAGE_NAME}:${env.BUILD_NUMBER} \\
                                --destination=${ECR_REGISTRY}/${IMAGE_NAME}:latest \\
                                --force
                            """
                        }
                    }
                }
            }
        }
    }
}
