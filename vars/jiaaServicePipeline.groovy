def call(Map config = [:]) {
    // 1. 파라미터 및 환경 변수 설정
    def buildType = config.get('type', 'gradle') // 기본값: gradle
    def appName = config.get('appName', env.JOB_BASE_NAME)
    
    pipeline {
        agent any
        
        environment {
            // ECR 레지스트리 주소
            ECR_REGISTRY = '541673202749.dkr.ecr.ap-northeast-2.amazonaws.com'
            IMAGE_NAME = "jiaa/${appName}"
            // AWS S3 (Electron용)
            S3_BUCKET = 'jio-client-releases' 
        }

        stages {
            // -----------------------------------------------------------
            // [Track A] Java/Spring Boot (Gradle)
            // -----------------------------------------------------------
            stage('Unit Test (Gradle)') {
                when { expression { return buildType == 'gradle' } }
                tools { jdk 'JDK21_corretto' }
                steps {
                    sh 'chmod +x gradlew'
                    sh './gradlew test --no-daemon'
                }
                post {
                    always { junit 'build/test-results/test/*.xml' }
                }
            }

            stage('Build JAR (Gradle)') {
                when { expression { return buildType == 'gradle' } }
                tools { jdk 'JDK21_corretto' }
                steps {
                    sh './gradlew bootJar --no-daemon -x test'
                }
            }

            // -----------------------------------------------------------
            // [Track B] Electron (Client)
            // -----------------------------------------------------------
            stage('Client Build (Electron)') {
                when { expression { return buildType == 'electron' } }
                tools { nodejs 'NodeJS_20' } // Jenkins에 NodeJS_20 툴 설정 필요
                steps {
                    sh 'npm install'
                    sh 'npm run make' // Electron 빌드
                    // 빌드 결과물을 S3로 업로드 (Jenkins에 AWS CLI 설정 필요)
                    /*
                    sh """
                        aws s3 cp dist/make/ s3://${S3_BUCKET}/${appName}/${env.BUILD_NUMBER}/ --recursive
                    """
                    */
                }
            }

            // -----------------------------------------------------------
            // [Common] Docker Image Build & Push (Kaniko)
            // Electron을 제외한 모든 서버(Gradle 결과물 포함)는 도커 이미지로 구워서 ECR로 전송
            // -----------------------------------------------------------
            stage('Docker Build & Push') {
                when { expression { return buildType != 'electron' } }
                agent {
                    kubernetes {
                        yaml libraryResource('pod-templates/kaniko-pod.yaml') 
                    }
                }
                steps {
                    script {
                        container('kaniko') {
                            // Gradle 빌드인 경우, 이미 빌드된 JAR를 사용해야 하므로 소스만 체크아웃하면 안 됨.
                            // 하지만 Kaniko는 독립된 Pod라서 Workspace 공유가 까다로움.
                            // 전략: 
                            // 1. (Docker/Go/Py/Node) -> Dockerfile Multi-stage build로 소스부터 빌드
                            // 2. (Gradle) -> Dockerfile에서 COPY build/libs/*.jar ... 하려면 
                            //    여기서 JAR를 Kaniko 컨텍스트로 넘겨야 함.
                            //    
                            //    *가장 쉬운 방법*: Spring Boot도 그냥 Dockerfile 내부에서 Build 수행 (Multi-stage)
                            //    그러면 앞선 'Build JAR' 스테이지는 테스트 용도로만 쓰고,
                            //    여기서 처음부터 다시 굽는 방식이 깔끔함 (Jar 아티팩트 공유 불필요)
                            
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
