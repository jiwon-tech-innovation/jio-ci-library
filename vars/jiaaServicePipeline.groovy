def call(Map config = [:]) {
    // 1. 파라미터 및 환경 변수 설정
    def buildType = config.get('type', 'gradle') // 기본값: gradle
    
    // appName 자동 감지 로직 개선 (JOB_NAME: Folder/Repo/Branch)
    def appName = config.get('appName')
    if (!appName) {
        def tokens = env.JOB_NAME.tokenize('/')
        appName = tokens.size() > 1 ? tokens[tokens.size() - 2] : env.JOB_BASE_NAME
    }
    
    pipeline {
        agent any
        
        environment {
            // ECR 레지스트리 주소
            ECR_REGISTRY = '541673202749.dkr.ecr.ap-northeast-2.amazonaws.com'
            // 서비스별 ECR 레포지토리 경로 매핑
            // jiaa-server-ai, jiaa-server-data: 접두사 없음
            // jiaa-server-core, jiaa-auth: jiaa/ 접두사 있음
            IMAGE_NAME = getImageName(appName)
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
                agent {
                    kubernetes {
                        yaml libraryResource('pod-templates/nodejs-pod.yaml') 
                    }
                }
                steps {
                    container('nodejs') {
                         withCredentials([usernamePassword(credentialsId: 'github-token', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                             sh 'npm install'
                             sh 'npm run electron:build' // Electron 빌드
                         }
                    }
                    /*
                    // S3 업로드 등은 추후 구현
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

            // -----------------------------------------------------------
            // [GitOps] Update Manifest (ArgoCD)
            // 빌드된 이미지 태그(BUILD_NUMBER)를 GitOps 레포지토리에 반영
            // -----------------------------------------------------------
            stage('GitOps Update') {
                when { 
                    allOf { 
                        expression { return buildType != 'electron' }
                        branch 'main' // 메인 브랜치에서만 배포
                    } 
                }
                agent {
                    kubernetes {
                        // git과 sed가 필요함. 기본 jnlp 슬레이브 또는 nodejs 사용
                        yaml libraryResource('pod-templates/nodejs-pod.yaml')
                    }
                }
                steps {
                    container('nodejs') {
                        script {
                            withCredentials([usernamePassword(credentialsId: 'github-token', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                                sh """
                                    # Git 설정
                                    git config --global user.email "jenkins@jio.com"
                                    git config --global user.name "Jenkins Bot"
                                    
                                    # GitOps 레포 클론
                                    git clone https://${GH_USER}:${GH_TOKEN}@github.com/jiwon-tech-innovation/jio-gitops.git
                                    
                                    # 태그 업데이트
                                    cd jio-gitops/apps/${appName}/overlays/prod
                                    
                                    # kustomization.yaml에서 newTag 업데이트 (Linux sed)
                                    sed -i 's/newTag: .*/newTag: "${env.BUILD_NUMBER}"/' kustomization.yaml
                                    
                                    # 변경 사항 커밋 & 푸시
                                    git add kustomization.yaml
                                    git commit -m "deploy: update ${appName} to build ${env.BUILD_NUMBER}"
                                    git push origin main
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 서비스별 ECR 레포지토리 경로 매핑
 * GitHub Repo: jio-* → ECR Repo: jiaa-*
 */
def getImageName(String appName) {
    def repoMap = [
        // GitHub repo name → ECR repo name
        'jio-server-ai'    : 'jiaa-server-ai',
        'jio-server-data'  : 'jiaa-server-data',
        'jio-server-core'  : 'jiaa/jiaa-server-core',
        'jio-auth'         : 'jiaa/jiaa-server-auth',
        // Legacy (jiaa-* 형태도 지원)
        'jiaa-server-ai'   : 'jiaa-server-ai',
        'jiaa-server-data' : 'jiaa-server-data',
        'jiaa-server-core' : 'jiaa/jiaa-server-core',
        'jiaa-auth'        : 'jiaa/jiaa-server-auth'
    ]
    return repoMap.get(appName, appName) // 매핑 없으면 appName 그대로 사용
}
