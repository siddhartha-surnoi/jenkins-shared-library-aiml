/*
================================================================================
 PROJECT INFORMATION
================================================================================
Project Name    : FusionIQ
Maintained By   : DevOps Team @ Surnoi Technology Pvt Ltd
Developed By    : AI/ML Development Team (maintained by DevOps)
Pipeline Owner  : Kanaparthi Siddhartha (DevOps Engineer)
Pipeline Type   : Shared Jenkins Library for Multi-Microservice Build & Deploy
File Location   : vars/aimlPipeline.groovy
================================================================================
 DESCRIPTION
================================================================================
This shared library pipeline automates the build, test, and Docker packaging
process for multiple AIML microservices. It provides:
  ✔ Dynamic selection of microservice name (SERVICE_NAME)
  ✔ Dynamic Entrypoint & Port configuration
  ✔ Version tagging from pyproject.toml (fallback → latest)
  ✔ SonarQube Quality Gate validation
  ✔ Trivy, Checkov, and pip-audit security scanning
  ✔ Optional local run for manual testing
  ✔ Easy onboarding of new microservices

================================================================================
 SUPPORTED MICROSERVICES
================================================================================
SERVICE_NAME      | GitHub Repo                         | Entrypoint File       | Docker Image | Default Port
----------------- | ------------------------------------ | --------------------- | ------------- | --------------
api-gateway        | API-Gateway-AIML-Microservice        | gateway.py            | api-gateway   | 8000
aiml-testcase      | AIML-testcase                       | Integration.py        | aiml-testcase | 8100
jobtestcase        | jobtestcase                         | integration.py        | jobtestcase   | 8200
feed-aiml          | Feed-AIML-Microservice               | app_main.py           | feed-aiml     | 8300

================================================================================
 HOW TO ADD A NEW MICROSERVICE
================================================================================
1. Add its entry in the above table (name, repo, entrypoint, port).
2. In Jenkinsfile → update SERVICE_NAME, REPO, ENTRYPOINT, DOCKER_IMAGE_NAME, PORT.
3. Trigger pipeline. If port not defined, you’ll be prompted.
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
            VENV_DIR              = config.VENV_DIR ?: "${WORKSPACE}/venv"
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
                        sh '''
                            if [ -f setup_environment.sh ]; then
                                chmod +x setup_environment.sh
                                ./setup_environment.sh
                            else
                                echo " setup_environment.sh not found — skipping custom setup..."
                            fi
                        '''
                    }
                }
            }

            stage('Install Python Dependencies') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        sh '''
                            if [ ! -d "$VENV_DIR" ]; then
                                ${PYTHON_BIN} -m venv $VENV_DIR
                            fi
                            source $VENV_DIR/bin/activate
                            pip install --upgrade pip
                            pip install -r requirements.txt
                            pip install pytest pytest-cov pip-audit checkov awscli trivy
                        '''

                        // Additional models if AIML specific
                        script {
                            if (SERVICE_NAME == "aiml-testcase") {
                                sh '''
                                    source $VENV_DIR/bin/activate
                                    python -m spacy download en_core_web_md
                                '''
                            }
                        }
                    }
                }
            }

            stage('Run Locally (Optional)') {
                when { expression { return params.RUN_LOCALLY == true } }
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        sh """
                            echo " Running ${SERVICE_NAME} locally on port ${PORT}"
                            source ${VENV_DIR}/bin/activate
                            nohup ${PYTHON_BIN} ${ENTRYPOINT} --port ${PORT} > ${SERVICE_NAME}.log 2>&1 &
                            sleep 5
                            echo " ${SERVICE_NAME} started on port ${PORT}"
                        """
                    }
                }
            }

            stage('Quality & Security Checks (Parallel)') {
                parallel {

                    stage('Unit Tests & Coverage') {
                        steps {
                            dir("${WORKSPACE}/${SERVICE_NAME}") {
                                sh '''
                                    source $VENV_DIR/bin/activate
                                    pytest --cov=. --cov-report=xml:coverage.xml --cov-report=term
                                '''
                            }
                            archiveArtifacts artifacts: "${SERVICE_NAME}/coverage.xml", allowEmptyArchive: true
                        }
                    }

                    stage('Filesystem Scan (Trivy)') {
                        steps {
                            dir("${WORKSPACE}/${SERVICE_NAME}") {
                                sh '''
                                    trivy fs --exit-code 0 --no-progress . > trivy-fs-report.txt
                                    trivy fs --exit-code 1 --severity CRITICAL,HIGH --no-progress . > trivy-fs-critical.txt || true
                                '''
                            }
                            archiveArtifacts artifacts: "${SERVICE_NAME}/trivy-fs-*.txt", allowEmptyArchive: true
                        }
                    }

                    stage('Python Dependency Audit') {
                        steps {
                            dir("${WORKSPACE}/${SERVICE_NAME}") {
                                sh '''
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
                                sh '''
                                    VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                                    docker build -t ${DOCKER_IMAGE_NAME}:$VERSION .
                                    trivy image --severity HIGH,CRITICAL ${DOCKER_IMAGE_NAME}:$VERSION > trivy-image-scan.txt || true
                                '''
                            }
                            archiveArtifacts artifacts: "${SERVICE_NAME}/trivy-image-scan.txt", allowEmptyArchive: true
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                environment { scannerHome = tool 'sonar-7.2' }
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        withSonarQubeEnv("${env.SONARQUBE_ENV}") {
                            withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                                sh '''
                                    echo " Running SonarQube analysis..."
                                    if [ ! -f sonar-project.properties ]; then
                                        echo "sonar-project.properties not found — creating minimal file"
                                        echo "sonar.projectKey=${SERVICE_NAME}" > sonar-project.properties
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
                                error "❌ SonarQube Quality Gate failed: ${qg.status}"
                            } else {
                                echo "✅ SonarQube Quality Gate passed: ${qg.status}"
                            }
                        }
                    }
                }
            }

            stage('Push Docker Image & Run') {
                steps {
                    withCredentials([usernamePassword(credentialsId: "${env.DOCKERHUB_CREDENTIALS}", usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                        dir("${WORKSPACE}/${SERVICE_NAME}") {
                            sh '''
                                VERSION=$(grep -Po '(?<=version = ")[^"]*' pyproject.toml || echo "latest")
                                echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                                docker tag ${DOCKER_IMAGE_NAME}:$VERSION $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                                docker push $DOCKER_USER/${DOCKER_IMAGE_NAME}:$VERSION
                                docker logout

                                echo " Deploying container on port ${PORT}..."
                                docker rm -f ${SERVICE_NAME} || true
                                docker run -d --name ${SERVICE_NAME} -p ${PORT}:${PORT} ${DOCKER_IMAGE_NAME}:$VERSION
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
                //cleanWs()
            }
        }
    }
}

// Default port mapping
def getDefaultPort(serviceName) {
    switch(serviceName) {
        case "api-gateway":   return "8000"
        case "aiml-testcase": return "8100"
        case "jobtestcase":   return "8200"
        case "feed-aiml":     return "8300"
        default:
            return input(message: "Enter port number for new service:", parameters: [string(defaultValue: "8500", description: 'Custom port')])
    }
}

// Default entrypoint mapping
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
