/*
================================================================================
 PROJECT INFORMATION
================================================================================
Project Name    : FusionIQ
Maintained By   : DevOps Team @ Surnoi Technology Pvt Ltd
Developed By    : AI/ML Development Team mainted by DevOps Team
Pipeline Owner  : Kanaparthi Siddhartha (DevOps Engineer)
Pipeline Type   : Shared Jenkins Library for Multi-Microservice Build & Deploy

================================================================================
 SUPPORTED MICROSERVICES
================================================================================
SERVICE_NAME      | GitHub Repo                         | Entrypoint File       | Docker Image | Default Port
------------------ | ------------------------------------ | --------------------- | ------------- | --------------
api-gateway        | API-Gateway-AIML-Microservice        | gateway.py            | api-gateway   | 8000
aiml-testcase      | AIML-testcase                       | Integration.py        | aiml-testcase | 8100
jobtestcase        | jobtestcase                         | integration.py        | jobtestcase   | 8200
feed-aiml          | Feed-AIML-Microservice               | app_main.py           | feed-aiml     | 8300

================================================================================
 DESCRIPTION
================================================================================
This Jenkinsfile automates the build, test, and Docker packaging process for
multiple AIML microservices. It provides:
  - Dynamic selection of microservice name (SERVICE_NAME)
  - Dynamic Entrypoint & Port selection for new services
  - Version tagging from pyproject.toml
  - Optional local service run for validation
  - Reusable structure for adding new microservices quickly

To add a new microservice:
  1️ Add its entry in the "SUPPORTED MICROSERVICES" section.
  2️ Define its repo URL, entrypoint, Docker image name, and port.
  3️ Set SERVICE_NAME when triggering the Jenkins pipeline.

================================================================================
 EXAMPLE USAGE
================================================================================
When triggering the pipeline:
  - SERVICE_NAME = "feed-aiml"
  - PORT = "8001"
  -  DOCKER_IMAGE_NAME: 'feed-aiml',
  - ENTRYPOINT = "app_main.py"
  - VERSION = extracted automatically from pyproject.toml

================================================================================
*/



def call(Map config = [:]) {

    pipeline {
        agent any

        environment {
            REPO                  = config.REPO ?: "https://github.com/SurnoiTechnology/API-Gateway-AIML-Microservice.git"
            SERVICE_NAME          = config.SERVICE_NAME ?: "api-gateway"
            PYTHON_VERSION        = config.PYTHON_VERSION ?: "3.11"
            PYTHON_BIN            = config.PYTHON_BIN ?: "/usr/bin/python3.11"
            GIT_CREDENTIALS       = config.GIT_CREDENTIALS ?: "git-access"
            VENV_DIR              = config.VENV_DIR ?: "${WORKSPACE}/myenv"
            SONARQUBE_ENV         = config.SONARQUBE_ENV ?: "SonarQube-Server"
            DOCKER_IMAGE_NAME     = config.DOCKER_IMAGE_NAME ?: SERVICE_NAME
            DOCKERHUB_CREDENTIALS = config.DOCKERHUB_CREDENTIALS ?: "dockerhub-credentials"
            PORT                  = config.PORT ?: getDefaultPort(SERVICE_NAME)
            ENTRYPOINT            = config.ENTRYPOINT ?: getDefaultEntrypoint(SERVICE_NAME)
        }

        stages {

            stage('Checkout Repository') {
                steps {
                    echo " Cloning repository: ${REPO}"
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        git branch: 'master', credentialsId: "${env.GIT_CREDENTIALS}", url: "${env.REPO}"
                    }
                }
            }

            stage('Setup Environment') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        sh '''#!/bin/bash
                        set +e
                        if [ -f setup_environment.sh ]; then
                            chmod +x setup_environment.sh
                            ./setup_environment.sh || true
                        else
                            echo " setup_environment.sh not found, skipping..."
                        fi
                        set -e
                        '''
                    }
                }
            }

            stage('Install Python Dependencies') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        script {
                            sh '''#!/bin/bash
                            set -e
                            if [ ! -d "$VENV_DIR" ]; then
                                $PYTHON_BIN -m venv $VENV_DIR
                            fi
                            source $VENV_DIR/bin/activate
                            pip install --upgrade pip
                            pip install -r requirements.txt
                            pip install pytest pytest-cov pip-audit checkov awscli
                            '''

                            // Extra model for AIML-testcase
                            if (SERVICE_NAME == "aiml-testcase") {
                                sh '''#!/bin/bash
                                set -e
                                source $VENV_DIR/bin/activate
                                python -m spacy download en_core_web_md
                                '''
                            }
                        }
                    }
                }
            }

            stage('Run Microservice Locally (Optional)') {
                when {
                    expression { return params.RUN_LOCALLY == true }
                }
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        sh """#!/bin/bash
                        set -e
                        echo " Running ${SERVICE_NAME} locally on port ${PORT}"
                        source ${VENV_DIR}/bin/activate
                        nohup ${PYTHON_BIN} ${ENTRYPOINT} --port ${PORT} > ${SERVICE_NAME}.log 2>&1 &
                        sleep 5
                        echo " ${SERVICE_NAME} started on port ${PORT}"
                        """
                    }
                }
            }

            stage('Parallel Quality & Security Checks') {
                parallel {

                    stage('Run Tests & Coverage') {
                        steps {
                            dir("${WORKSPACE}/${SERVICE_NAME}") {
                                sh '''#!/bin/bash
                                set -e
                                source $VENV_DIR/bin/activate
                                pytest --cov=. --cov-report=xml:coverage.xml --cov-report=term || true
                                '''
                            }
                            archiveArtifacts artifacts: "${SERVICE_NAME}/coverage.xml", allowEmptyArchive: true
                        }
                    }

                    stage('Trivy Filesystem Scan') {
                        steps {
                            dir("${WORKSPACE}/${SERVICE_NAME}") {
                                sh '''#!/bin/bash
                                set -e
                                trivy fs --exit-code 0 --no-progress . | tee trivy-fs-report.txt || true
                                trivy fs --exit-code 1 --severity CRITICAL,HIGH --no-progress . | tee trivy-fs-critical.txt || true
                                '''
                            }
                            archiveArtifacts artifacts: "${SERVICE_NAME}/trivy-fs-*.txt", allowEmptyArchive: true
                        }
                    }

                    stage('Python Dependency Audit') {
                        steps {
                            dir("${WORKSPACE}/${SERVICE_NAME}") {
                                sh '''#!/bin/bash
                                set -e
                                source $VENV_DIR/bin/activate
                                pip-audit -r requirements.txt -f json > pip-audit.json || true
                                '''
                            }
                            archiveArtifacts artifacts: "${SERVICE_NAME}/pip-audit.json", allowEmptyArchive: true
                        }
                    }

                    stage('Docker Build & Scan') {
                        steps {
                            dir("${WORKSPACE}/${SERVICE_NAME}") {
                                script {
                                    sh '''#!/bin/bash
                                    set -e
                                    VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                                    echo " Building Docker image: ${DOCKER_IMAGE_NAME}:$VERSION"
                                    docker build -t ${DOCKER_IMAGE_NAME}:$VERSION .
                                    echo " Scanning Docker image..."
                                    trivy image --exit-code 0 --severity HIGH,CRITICAL ${DOCKER_IMAGE_NAME}:$VERSION | tee trivy-image-scan.txt || true
                                    trivy image --format json -o trivy-image-report.json ${DOCKER_IMAGE_NAME}:$VERSION || true
                                    '''
                                }
                            }
                            archiveArtifacts artifacts: "${SERVICE_NAME}/trivy-image-*", allowEmptyArchive: true
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                environment {
                    scannerHome = tool 'sonar-7.2'
                }
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        script {
                            withSonarQubeEnv("${env.SONARQUBE_ENV}") {
                                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                                    sh '''#!/bin/bash
                                    set -e
                                    echo " Running SonarQube analysis..."
                                    if [ ! -f sonar-project.properties ]; then
                                        echo " sonar-project.properties not found!"
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
            }

            stage('Quality Gate Check') {
                steps {
                    script {
                        timeout(time: 10, unit: 'MINUTES') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                error " SonarQube Quality Gate failed: ${qg.status}"
                            } else {
                                echo " SonarQube Quality Gate passed: ${qg.status}"
                            }
                        }
                    }
                }
            }

            stage('Push & Run Docker Image') {
                steps {
                    withCredentials([usernamePassword(credentialsId: "${env.DOCKERHUB_CREDENTIALS}", usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                        dir("${WORKSPACE}/${SERVICE_NAME}") {
                            sh '''#!/bin/bash
                            set -e
                            VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                            echo " Logging into DockerHub..."
                            echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                            docker tag ${DOCKER_IMAGE_NAME}:$VERSION $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                            docker tag ${DOCKER_IMAGE_NAME}:$VERSION $DOCKER_USER/${DOCKER_IMAGE_NAME}:latest
                            docker push $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                            docker push $DOCKER_USER/${DOCKER_IMAGE_NAME}:latest
                            docker logout

                            echo " Running container on port ${PORT}..."
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
                echo " Cleaning workspace..."
               // cleanWs()
            }
        }
    }
}

// Default port mapping for known services
def getDefaultPort(serviceName) {
    switch(serviceName) {
        case "api-gateway":   return "8000"
        case "aiml-testcase": return "8001"
        case "jobtestcase":   return "8002"
        case "feed-aiml":     return "8003"
        default:
            return input(message: "Enter port number for new service:", parameters: [string(defaultValue: "8000", description: 'Custom service port')])
    }
}

// Default entrypoint mapping for known services
def getDefaultEntrypoint(serviceName) {
    switch(serviceName) {
        case "api-gateway":   return "gateway.py"
        case "aiml-testcase": return "Integration.py"
        case "jobtestcase":   return "integration.py"
        case "feed-aiml":     return "app_main.py"
        default:
            return input(message: "Enter entrypoint file for new service:", parameters: [string(defaultValue: "main.py", description: 'Entrypoint Python file')])
    }
}
