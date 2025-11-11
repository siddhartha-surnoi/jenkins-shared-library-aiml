// vars/Logistics_microservicePipeline.groovy
def call(Map config) {
    pipeline {
        agent { label config.agentLabel }

        environment {
            AWS_REGION = config.awsRegion ?: 'us-east-2'
        }

        stages {

            // ================================================
            // Checkout Stage
            // ================================================
            stage('Checkout') {
                steps {
                    checkout scm

                    script {
                        echo "Jenkins Git Info:"
                        echo "Branch: ${env.BRANCH_NAME}"
                        echo "Commit: ${env.GIT_COMMIT}"
                        echo "Git Branch: ${env.GIT_BRANCH}"

                        env.GIT_AUTHOR_NAME = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
                        env.GIT_AUTHOR_EMAIL = sh(script: "git log -1 --pretty=format:'%ae'", returnStdout: true).trim()
                    }
                }
            }

            // ================================================
            // Build Stage
            // ================================================
            stage('Build') {
                steps {
                    echo "Building ${config.project}/${config.component} for branch: ${env.BRANCH_NAME}"
                    sh 'mvn clean package -DskipTests'
                }
            }

            // ================================================
            // Code & Security Scans (Parallel)
            // ================================================
            stage('Code & Security Scans') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME ==~ /feature.*/ } 
                        expression { env.BRANCH_NAME in ['master', 'release/dev', 'release/qa'] }
                    }
                }
                parallel {
                    stage('SonarQube Scan') {
                        environment { scannerHome = tool 'sonar-7.2' }
                        steps {
                            echo "Running SonarQube analysis..."
                            withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                                withSonarQubeEnv('SonarQube-Server') {
                                    sh '''
                                        ${scannerHome}/bin/sonar-scanner \
                                        -Dsonar.login=$SONAR_TOKEN
                                    '''
                                }
                            }
                        }
                    }

                    stage('Dependabot Scan') {
                        steps {
                            echo "Running Dependabot scan simulation..."
                            sh '''
                                echo "Fetching Dependabot alerts for repository..."
                                echo "Dependabot scan completed (simulated)."
                            '''
                        }
                    }
                }
            }

            // ================================================
            // Quality Gate
            // ================================================
            stage('Quality Gate') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME ==~ /feature.*/ }
                        expression { env.BRANCH_NAME in ['master', 'release/dev', 'release/qa'] }
                    }
                }
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            // ================================================
            // Docker Build & Push (auto-create ECR)
            // ================================================
            stage('Build & Push Docker Image') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME == 'master' }
                        expression { env.BRANCH_NAME == 'release/dev' }
                        expression { env.BRANCH_NAME == 'release/qa' }
                    }
                }
                steps {
                    withAWS(credentials: 'aws-credentials', region: "${env.AWS_REGION}") {
                        script {
                            def ecrRepoName = "${config.project}/${config.component}"
                            def awsAccountId = sh(script: "aws sts get-caller-identity --query Account --output text", returnStdout: true).trim()
                            def ecrUri = "${awsAccountId}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${ecrRepoName}"

                            echo " Checking ECR repo existence..."
                            def repoExists = sh(script: "aws ecr describe-repositories --repository-names ${ecrRepoName} --region ${env.AWS_REGION} >/dev/null 2>&1", returnStatus: true)

                            if (repoExists != 0) {
                                echo " Creating ECR repository: ${ecrRepoName}"
                                sh "aws ecr create-repository --repository-name ${ecrRepoName} --region ${env.AWS_REGION}"
                            } else {
                                echo " ECR repository already exists: ${ecrRepoName}"
                            }

                            echo " Logging into ECR..."
                            sh "aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin ${ecrUri}"

                            echo " Building and pushing Docker image to ECR..."
                            sh """
                                docker build -t ${ecrUri}:latest .
                                docker push ${ecrUri}:latest
                            """
                        }
                    }
                }
            }

            // ================================================
            // ECR Image Scan
            // ================================================
            stage('ECR Image Scan') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME == 'master' }
                        expression { env.BRANCH_NAME == 'release/dev' }
                        expression { env.BRANCH_NAME == 'release/qa' }
                    }
                }
                steps {
                    withAWS(credentials: 'aws-credentials', region: "${env.AWS_REGION}") {
                        script {
                            def ecrRepoName = "${config.project}/${config.component}"
                            def awsAccountId = sh(script: "aws sts get-caller-identity --query Account --output text", returnStdout: true).trim()
                            def ecrUri = "${awsAccountId}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${ecrRepoName}"

                            echo " Starting ECR image scan for 'latest'..."
                            sh """
                                aws ecr start-image-scan \
                                    --repository-name ${ecrRepoName} \
                                    --image-id imageTag=latest \
                                    --region ${env.AWS_REGION} || true
                            """
                        }
                    }
                }
            }
        }

        // ================================================
        // Post Actions (Teams Notifications)
        // ================================================
        post {
            always { echo "Build completed at: ${new Date()}" }

            success { notifyTeams('SUCCESS') }
            failure { notifyTeams('FAILURE') }
            unstable { notifyTeams('UNSTABLE') }
            aborted { notifyTeams('ABORTED') }
        }
    }
}

// ====================== Helper: Teams Notifications ======================
def notifyTeams(String status) {
    withCredentials([string(credentialsId: 'teams-webhook', variable: 'WEBHOOK_URL')]) {
        script {
            def gitCommit = env.GIT_COMMIT ?: 'N/A'
            def gitAuthorName = env.GIT_AUTHOR_NAME ?: 'N/A'
            def gitAuthorEmail = env.GIT_AUTHOR_EMAIL ?: 'N/A'

            office365ConnectorSend(
                message: "*Build ${status}* for branch `${env.BRANCH_NAME}`\n" +
                         "Commit: `${gitCommit}`\n" +
                         "Author: `${gitAuthorName}`\n" +
                         "Email: `${gitAuthorEmail}`\n" +
                         "Job: `${env.JOB_NAME}` #${env.BUILD_NUMBER}\n" +
                         "[View Build](${env.BUILD_URL})",
                color: statusColor(status),
                status: status,
                webhookUrl: WEBHOOK_URL
            )
        }
    }
}

// ====================== Helper: Status Color ======================
def statusColor(String status) {
    switch(status) {
        case 'SUCCESS': return '#00FF00'
        case 'FAILURE': return '#FF0000'
        case 'UNSTABLE': return '#FFA500'
        case 'ABORTED': return '#808080'
        default: return '#000000'
    }
}
