// vars/Logistics_microservicePipeline.groovy
def call(Map config) {
    pipeline {
        agent { label config.agentLabel }

        environment {
            GIT_COMMIT = ''
        }

        stages {

            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        env.GIT_COMMIT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    }
                }
            }

            stage('Build') {
                steps {
                    echo "Building ${config.project}/${config.component} for branch: ${env.BRANCH_NAME}"
                    sh 'mvn clean package -DskipTests'
                }
            }

            stage('Code & Security Scans') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME ==~ /feature.*/ } // feature branches (feature_*, feature/1, etc.)
                        expression { env.BRANCH_NAME == 'master' || env.BRANCH_NAME == 'release/dev' || env.BRANCH_NAME == 'release/qa' }
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

            stage('Quality Gate') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME ==~ /feature.*/ }
                        expression { env.BRANCH_NAME == 'master' || env.BRANCH_NAME == 'release/dev' || env.BRANCH_NAME == 'release/qa' }
                    }
                }
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Build & Push Docker Image') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME == 'master' }
                        expression { env.BRANCH_NAME == 'release/dev' }
                        expression { env.BRANCH_NAME == 'release/qa' }
                    }
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-region', variable: 'AWS_REGION'),
                        string(credentialsId: 'ecr-repo', variable: 'ECR_REPO')
                    ]) {
                        script {
                            echo "Building and pushing Docker image to ECR..."
                            sh '''
                                aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REPO
                                docker build -t $ECR_REPO:latest .
                                docker push $ECR_REPO:latest
                            '''
                        }
                    }
                }
            }

            stage('ECR Image Scan') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME == 'master' }
                        expression { env.BRANCH_NAME == 'release/dev' }
                        expression { env.BRANCH_NAME == 'release/qa' }
                    }
                }
                steps {
                    withCredentials([string(credentialsId: 'aws-region', variable: 'AWS_REGION')]) {
                        script {
                            echo "Starting ECR image scan for 'latest'..."
                            sh '''
                                aws ecr start-image-scan \
                                    --repository-name ${ECR_REPO} \
                                    --image-id imageTag=latest \
                                    --region $AWS_REGION || true
                            '''
                        }
                    }
                }
            }
        }

        post {
            always { echo "Build completed at: ${new Date()}" }

            success { notifyTeams('SUCCESS') }
            failure { notifyTeams('FAILURE') }
            unstable { notifyTeams('UNSTABLE') }
            aborted { notifyTeams('ABORTED') }
        }
    }
}

// Helper function to send Teams notifications
def notifyTeams(String status) {
    withCredentials([string(credentialsId: 'teams-webhook', variable: 'WEBHOOK_URL')]) {
        def gitAuthorName = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
        def gitAuthorEmail = sh(script: "git log -1 --pretty=format:'%ae'", returnStdout: true).trim()

        office365ConnectorSend(
            message: "*Build ${status}* for branch `${env.BRANCH_NAME}`\n" +
                     "Commit: `${env.GIT_COMMIT}`\n" +
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

def statusColor(String status) {
    switch(status) {
        case 'SUCCESS': return '#00FF00'
        case 'FAILURE': return '#FF0000'
        case 'UNSTABLE': return '#FFA500'
        case 'ABORTED': return '#808080'
        default: return '#000000'
    }
}
