def call(Map config) {
    pipeline {
        agent { label config.agentLabel }

        stages {
            stage('Build') {
                steps {
                    sh 'mvn clean package -DskipTests'
                }
            }

            stage('Code & Security Scans') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME ==~ /feature.*/ }
                        expression { env.BRANCH_NAME == 'release/dev' }
                    }
                }
                parallel {
                    stage('SonarQube Scan') {
                        environment { scannerHome = tool 'sonar-7.2' }
                        steps {
                            withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                                withSonarQubeEnv('SonarQube-Server') {
                                    sh """${scannerHome}/bin/sonar-scanner -Dsonar.login=$SONAR_TOKEN"""
                                }
                            }
                        }
                    }

                    stage('Dependabot Scan') {
                        steps {
                            sh 'echo "Dependabot scan completed (simulated)."'
                        }
                    }
                }
            }

            stage('Quality Gate') {
                when {
                    anyOf {
                        expression { env.BRANCH_NAME ==~ /feature.*/ }
                        expression { env.BRANCH_NAME == 'release/dev' }
                    }
                }
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Build & Push Docker Image') {
                when {
                    expression { env.BRANCH_NAME == 'release/dev' }
                }
                steps {
                    script {
                        def ecrRepoName = "${config.project}/${config.component}"
                        def awsAccountId = sh(
                            script: "aws sts get-caller-identity --query Account --output text --region ap-south-1",
                            returnStdout: true
                        ).trim()
                        def ecrUri = "${awsAccountId}.dkr.ecr.ap-south-1.amazonaws.com/${ecrRepoName}"

                        def repoExists = sh(
                            script: "aws ecr describe-repositories --repository-names ${ecrRepoName} --region ap-south-1 >/dev/null 2>&1",
                            returnStatus: true
                        )
                        if (repoExists != 0) {
                            sh "aws ecr create-repository --repository-name ${ecrRepoName} --region ap-south-1"
                        }

                        sh "aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin ${ecrUri}"
                        sh """
                            docker build -t ${ecrUri}:latest .
                            docker push ${ecrUri}:latest
                        """
                    }
                }
            }

            stage('ECR Image Scan') {
                when {
                    expression { env.BRANCH_NAME == 'release/dev' }
                }
                steps {
                    script {
                        def ecrRepoName = "${config.project}/${config.component}"
                        sh """
                            aws ecr start-image-scan \
                                --repository-name ${ecrRepoName} \
                                --image-id imageTag=latest \
                                --region ap-south-1 || true
                        """
                    }
                }
            }
        }

        post {
            always {
                cleanWs() // safely cleans workspace after build
            }
            success { notifyTeams('SUCCESS') }
            failure { notifyTeams('FAILURE') }
            unstable { notifyTeams('UNSTABLE') }
            aborted { notifyTeams('ABORTED') }
        }
    }
}

def notifyTeams(String status) {
    withCredentials([string(credentialsId: 'teams-webhook', variable: 'WEBHOOK_URL')]) {
        script {
            def gitCommit = env.GIT_COMMIT ?: 'N/A'
            office365ConnectorSend(
                message: "*Build ${status}* for branch ${env.BRANCH_NAME}\n" +
                         "Commit: ${gitCommit}\n" +
                         "Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}\n" +
                         "[View Build](${env.BUILD_URL})",
                color: statusColor(status),
                status: status,
                webhookUrl: WEBHOOK_URL
            )
        }
    }
}

def statusColor(String status) {
    switch (status) {
        case 'SUCCESS': return '#00FF00'
        case 'FAILURE': return '#FF0000'
        case 'UNSTABLE': return '#FFA500'
        case 'ABORTED': return '#808080'
        default: return '#000000'
    }
}
