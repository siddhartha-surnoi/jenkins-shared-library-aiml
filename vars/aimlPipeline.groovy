def call(Map config = [:]) {

    pipeline {
        agent any

        stages {

            stage('Setup Config') {
                steps {
                    script {
                        //  Dynamically set environment variables safely
                        env.REPO = config.REPO ?: "https://github.com/SurnoiTechnology/API-Gateway-AIML-Microservice.git"
                        env.PYTHON_VERSION = config.PYTHON_VERSION ?: "3.11"
                        env.PYTHON_BIN = config.PYTHON_BIN ?: "/usr/bin/python3.11"
                        env.GIT_CREDENTIALS = config.GIT_CREDENTIALS ?: "git-access"
                        env.VENV_DIR = config.VENV_DIR ?: "${WORKSPACE}/myenv"
                        env.SONARQUBE_ENV = config.SONARQUBE_ENV ?: "SonarQube-Server"
                        env.DOCKER_IMAGE_NAME = config.DOCKER_IMAGE_NAME ?: "api-gateway"
                        env.DOCKERHUB_CREDENTIALS = config.DOCKERHUB_CREDENTIALS ?: "dockerhub-credentials"
                    }
                }
            }

            stage('Checkout Repository') {
                steps {
                    dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
                        git branch: 'master', credentialsId: "${env.GIT_CREDENTIALS}", url: "${env.REPO}"
                    }
                }
            }

            stage('Setup Environment') {
                steps {
                    dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
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
                    dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
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
                    }
                }
            }

            stage('Parallel Execution') {
                parallel {

                    stage('Run Tests & Coverage') {
                        steps {
                            dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
                                sh '''#!/bin/bash
                                set -e
                                source $VENV_DIR/bin/activate
                                echo ">>> Running tests with coverage..."
                                pytest --cov=app --cov=gateway --cov-report=xml:coverage.xml --cov-report=term || true
                                '''
                            }
                            archiveArtifacts artifacts: 'API-Gateway-AIML-Microservice/coverage.xml', allowEmptyArchive: true
                        }
                    }

                    stage('Trivy Filesystem Scan') {
                        steps {
                            dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
                                sh '''#!/bin/bash
                                set -e
                                echo ">>> Running Trivy filesystem scan..."
                                trivy fs --exit-code 0 --no-progress . | tee trivy-fs-report.txt || true
                                trivy fs --exit-code 1 --severity CRITICAL,HIGH --no-progress . | tee trivy-fs-critical.txt || true
                                '''
                            }
                            archiveArtifacts artifacts: 'API-Gateway-AIML-Microservice/trivy-fs-*.txt', allowEmptyArchive: true
                        }
                    }

                    stage('Python Dependency Audit') {
                        steps {
                            dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
                                sh '''#!/bin/bash
                                set -e
                                source $VENV_DIR/bin/activate
                                echo ">>> Auditing dependencies with pip-audit..."
                                pip-audit -r requirements.txt -f json > pip-audit.json || true
                                '''
                            }
                            archiveArtifacts artifacts: 'API-Gateway-AIML-Microservice/pip-audit.json', allowEmptyArchive: true
                        }
                    }

                    stage('Docker Build & Scan') {
                        steps {
                            dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
                                sh '''#!/bin/bash
                                set -e
                                VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                                echo ">>> Building Docker image: ${DOCKER_IMAGE_NAME}:$VERSION"
                                docker build -t ${DOCKER_IMAGE_NAME}:$VERSION . || true
                                echo ">>> Scanning Docker image..."
                                trivy image --exit-code 0 --severity HIGH,CRITICAL ${DOCKER_IMAGE_NAME}:$VERSION | tee trivy-image-scan.txt || true
                                trivy image --format json -o trivy-image-report.json ${DOCKER_IMAGE_NAME}:$VERSION || true
                                '''
                            }
                            archiveArtifacts artifacts: 'API-Gateway-AIML-Microservice/trivy-image-*', allowEmptyArchive: true
                        }
                    }
                }
            }

            stage('SonarQube Scan') {
                environment {
                    scannerHome = tool 'sonar-7.2'
                }
                steps {
                    dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
                        script {
                            withSonarQubeEnv("${env.SONARQUBE_ENV}") {
                                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                                    sh '''#!/bin/bash
                                    set -e
                                    echo ">>> Checking sonar-project.properties..."
                                    if [ ! -f sonar-project.properties ]; then
                                        echo " sonar-project.properties not found!"
                                        exit 1
                                    fi
                                    echo ">>> Adding coverage path to sonar-project.properties..."
                                    if ! grep -q "sonar.python.coverage.reportPaths" sonar-project.properties; then
                                        echo "sonar.python.coverage.reportPaths=coverage.xml" >> sonar-project.properties
                                    fi
                                    echo ">>> Running SonarQube scan..."
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

            stage('SonarQube Quality Gate Check') {
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
                        dir("${WORKSPACE}/API-Gateway-AIML-Microservice") {
                            sh '''#!/bin/bash
                            set -e
                            VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                            echo ">>> Logging into DockerHub..."
                            echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                            docker tag ${DOCKER_IMAGE_NAME}:$VERSION $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                            docker tag ${DOCKER_IMAGE_NAME}:$VERSION $DOCKER_USER/${DOCKER_IMAGE_NAME}:latest
                            echo ">>> Pushing Docker image..."
                            docker push $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                            docker push $DOCKER_USER/${DOCKER_IMAGE_NAME}:latest
                            docker logout

                            echo ">>> Running container..."
                            CONTAINER="api-gateway-$VERSION"
                            if docker ps -a | grep -q $CONTAINER; then
                                docker rm -f $CONTAINER
                            fi
                            docker run -d --name $CONTAINER -p 8000:8000 ${DOCKER_IMAGE_NAME}:$VERSION
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
                cleanWs()
            }
        }
    }
}
