/*
================================================================================
 PROJECT METADATA
================================================================================
Project Name     : FusionIQ AIML Platform
Pipeline Name    : aimlPipeline.groovy
Maintained By    : DevOps Team @ Surnoi Technology Pvt Ltd
Developed By     : AI/ML Development Team
Pipeline Owner   : Kanaparthi Siddhartha (DevOps Engineer)
Purpose          : Shared reusable CI/CD pipeline for all AIML microservices
================================================================================
*/

def call(Map config = [:]) {

    pipeline {
        agent any

        stages {

            stage('Initialize') {
                steps {
                    script {
                        env.REPO                  = config.REPO ?: "https://github.com/SurnoiTechnology/API-Gateway-AIML-Microservice.git"
                        env.SERVICE_NAME          = config.SERVICE_NAME ?: "api-gateway"
                        env.PYTHON_VERSION        = config.PYTHON_VERSION ?: "3.11"
                        env.PYTHON_BIN            = config.PYTHON_BIN ?: "/usr/bin/python3.11"
                        env.GIT_CREDENTIALS       = config.GIT_CREDENTIALS ?: "git-access"
                        env.VENV_DIR              = "${WORKSPACE}/myenv"
                        env.SONARQUBE_ENV         = config.SONARQUBE_ENV ?: "SonarQube-Server"
                        env.DOCKER_IMAGE_NAME     = config.DOCKER_IMAGE_NAME ?: env.SERVICE_NAME
                        env.DOCKERHUB_CREDENTIALS = config.DOCKERHUB_CREDENTIALS ?: "dockerhub-credentials"
                        env.PORT                  = config.PORT ?: getDefaultPort(env.SERVICE_NAME)

                        echo """
                        ==============================================
                        Service Name   : ${env.SERVICE_NAME}
                        Repository     : ${env.REPO}
                        Python Version : ${env.PYTHON_VERSION}
                        ==============================================
                        """
                    }
                }
            }

            stage('Checkout Repository') {
                steps {
                    dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                        git branch: 'master', credentialsId: "${env.GIT_CREDENTIALS}", url: "${env.REPO}"
                    }
                }
            }

            stage('Setup Environment') {
                steps {
                    dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                        sh '''#!/bin/bash
                        set +e
                        if [ -f setup_environment.sh ]; then
                            chmod +x setup_environment.sh
                            ./setup_environment.sh || true
                        else
                            echo "setup_environment.sh not found, skipping..."
                        fi
                        set -e
                        '''
                    }
                }
            }

            stage('Install Dependencies & Tools') {
                steps {
                    dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                        sh '''#!/bin/bash
                        set -e
                        echo "Installing Python dependencies and security tools..."
                        if [ ! -d "$VENV_DIR" ]; then
                            $PYTHON_BIN -m venv $VENV_DIR
                        fi
                        source $VENV_DIR/bin/activate
                        pip install --upgrade pip
                        pip install -r requirements.txt
                        pip install pytest pytest-cov pip-audit awscli

                        echo "Installing Trivy..."
                        if ! command -v trivy &> /dev/null; then
                            apt-get update && apt-get install -y wget apt-transport-https gnupg lsb-release
                            wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | gpg --dearmor -o /usr/share/keyrings/trivy.gpg
                            echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" | tee /etc/apt/sources.list.d/trivy.list
                            apt-get update && apt-get install -y trivy
                        fi
                        '''
                    }
                }
            }

            stage('Parallel Quality & Security Checks') {
                parallel {

                    stage('Run Unit Tests & Coverage') {
                        steps {
                            dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                                sh '''#!/bin/bash
                                set -e
                                source $VENV_DIR/bin/activate
                                echo "Running tests with coverage..."
                                pytest --cov=. --cov-report=xml:coverage.xml --cov-report=term || true
                                '''
                            }
                            archiveArtifacts artifacts: "${env.SERVICE_NAME}/coverage.xml", allowEmptyArchive: true
                        }
                    }

                    stage('Trivy Filesystem Scan') {
                        steps {
                            dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                                sh '''#!/bin/bash
                                set -e
                                echo "Running Trivy filesystem scan..."
                                trivy fs --exit-code 0 --no-progress . | tee trivy-fs-report.txt || true
                                trivy fs --exit-code 1 --severity CRITICAL,HIGH --no-progress . | tee trivy-fs-critical.txt || true
                                '''
                            }
                            archiveArtifacts artifacts: "${env.SERVICE_NAME}/trivy-fs-*.txt", allowEmptyArchive: true
                        }
                    }

                    stage('Python Dependency Audit') {
                        steps {
                            dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                                sh '''#!/bin/bash
                                set -e
                                source $VENV_DIR/bin/activate
                                echo "Auditing Python dependencies..."
                                pip-audit -r requirements.txt -f json > pip-audit.json || true
                                '''
                            }
                            archiveArtifacts artifacts: "${env.SERVICE_NAME}/pip-audit.json", allowEmptyArchive: true
                        }
                    }

                    stage('Docker Build & Scan') {
                        steps {
                            dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                                sh '''#!/bin/bash
                                set -e
                                VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                                echo "Building Docker image: ${DOCKER_IMAGE_NAME}:$VERSION"
                                docker build -t ${DOCKER_IMAGE_NAME}:$VERSION .
                                echo "Scanning Docker image with Trivy..."
                                trivy image --exit-code 0 --severity HIGH,CRITICAL ${DOCKER_IMAGE_NAME}:$VERSION | tee trivy-image-scan.txt || true
                                trivy image --format json -o trivy-image-report.json ${DOCKER_IMAGE_NAME}:$VERSION || true
                                '''
                            }
                            archiveArtifacts artifacts: "${env.SERVICE_NAME}/trivy-image-*", allowEmptyArchive: true
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                environment {
                    scannerHome = tool 'sonar-7.2'
                }
                steps {
                    dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                        withSonarQubeEnv("${env.SONARQUBE_ENV}") {
                            withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                                sh '''#!/bin/bash
                                set -e
                                echo "Starting SonarQube analysis..."
                                if [ ! -f sonar-project.properties ]; then
                                    echo "sonar-project.properties not found!"
                                    exit 1
                                fi
                                if ! grep -q "sonar.python.coverage.reportPaths" sonar-project.properties; then
                                    echo "sonar.python.coverage.reportPaths=coverage.xml" >> sonar-project.properties
                                fi
                                $scannerHome/bin/sonar-scanner \
                                    -Dsonar.host.url=$SONAR_HOST_URL \
                                    -Dsonar.login=$SONAR_TOKEN
                                '''
                            }
                        }
                    }
                }
            }

            stage('Quality Gate Check') {
                steps {
                    script {
                        timeout(time: 10, unit: 'MINUTES') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                error "SonarQube Quality Gate failed: ${qg.status}"
                            } else {
                                echo "SonarQube Quality Gate passed successfully ✅"
                            }
                        }
                    }
                }
            }

            stage('Push & Run Docker Image') {
                steps {
                    withCredentials([usernamePassword(credentialsId: "${env.DOCKERHUB_CREDENTIALS}", usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                        dir("${WORKSPACE}/${env.SERVICE_NAME}") {
                            sh '''#!/bin/bash
                            set -e
                            VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                            echo "Logging into DockerHub..."
                            echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                            docker tag ${DOCKER_IMAGE_NAME}:$VERSION $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                            docker tag ${DOCKER_IMAGE_NAME}:$VERSION $DOCKER_USER/${DOCKER_IMAGE_NAME}:latest
                            docker push $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                            docker push $DOCKER_USER/${DOCKER_IMAGE_NAME}:latest
                            docker logout

                            echo "Running Docker container..."
                            CONTAINER="${SERVICE_NAME}-$VERSION"
                            if docker ps -a | grep -q $CONTAINER; then
                                docker rm -f $CONTAINER
                            fi
                            docker run -d --name $CONTAINER -p ${PORT}:${PORT} ${DOCKER_IMAGE_NAME}:$VERSION
                            docker ps -a
                            '''
                        }
                    }
                }
            }
        }

        post {
            always {
                echo "Cleaning up workspace..."
                cleanWs()
            }
        }
    }
}

// Helper function for dynamic port assignment
def getDefaultPort(serviceName) {
    switch(serviceName) {
        case "api-gateway":   return "8000"
        case "aiml-testcase": return "8001"
        case "jobtestcase":   return "8002"
        case "feed-aiml":     return "8003"
        default:
            return input(message: "Enter port number for new service:",
                         parameters: [string(defaultValue: "8000", description: 'Custom service port')])
    }
}
