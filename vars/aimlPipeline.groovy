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
 FEATURES
================================================================================
✔ Dynamic microservice onboarding (SERVICE_NAME, REPO, ENTRYPOINT, PORT)
✔ Automatic Python environment setup (venv)
✔ Quality checks (pytest, coverage, pip-audit, Checkov)
✔ SonarQube integration with quality gate enforcement
✔ Trivy Docker image scanning
✔ Docker build & push to GitHub Packages
✔ Optional local microservice run
✔ Auto-detects 'main' or 'master' branch
================================================================================
*/

def call(Map config = [:]) {

    // ================================================================
    //  DYNAMIC CONFIGURATION VALUES
    // ================================================================
    def REPO                  = config.REPO ?: "https://github.com/SurnoiTechnology/API-Gateway-AIML-Microservice.git"
    def SERVICE_NAME          = config.SERVICE_NAME ?: "api-gateway"
    def BRANCH                = config.BRANCH ?: detectBranch(REPO)
    def PYTHON_VERSION        = config.PYTHON_VERSION ?: "3.11"
    def PYTHON_BIN            = config.PYTHON_BIN ?: "/usr/bin/python3.11"
    def GIT_CREDENTIALS       = config.GIT_CREDENTIALS ?: "git-access"
    def VENV_DIR              = config.VENV_DIR ?: "${env.WORKSPACE}/venv"
    def SONARQUBE_ENV         = config.SONARQUBE_ENV ?: "SonarQube-Server"
    def DOCKER_IMAGE_NAME     = config.DOCKER_IMAGE_NAME ?: SERVICE_NAME
    def DOCKERHUB_CREDENTIALS = config.DOCKERHUB_CREDENTIALS ?: "dockerhub-credentials"
    def PORT                  = config.PORT ?: getDefaultPort(SERVICE_NAME)
    def ENTRYPOINT            = config.ENTRYPOINT ?: getDefaultEntrypoint(SERVICE_NAME)

    pipeline {
        agent any

        environment {
            REPO = "${REPO}"
            SERVICE_NAME = "${SERVICE_NAME}"
            BRANCH = "${BRANCH}"
            PYTHON_VERSION = "${PYTHON_VERSION}"
            PYTHON_BIN = "${PYTHON_BIN}"
            GIT_CREDENTIALS = "${GIT_CREDENTIALS}"
            VENV_DIR = "${VENV_DIR}"
            SONARQUBE_ENV = "${SONARQUBE_ENV}"
            DOCKER_IMAGE_NAME = "${DOCKER_IMAGE_NAME}"
            DOCKERHUB_CREDENTIALS = "${DOCKERHUB_CREDENTIALS}"
            PORT = "${PORT}"
            ENTRYPOINT = "${ENTRYPOINT}"
        }

        stages {

            // ================================================================
            stage(' Clone Repository') {
                steps {
                    echo "Cloning branch '${BRANCH}' from repository: ${REPO}"
                    git branch: "${BRANCH}", url: "${REPO}", credentialsId: "${GIT_CREDENTIALS}"
                }
            }

            // ================================================================
            stage(' Setup Python Environment') {
                steps {
                    sh '''#!/bin/bash
                        set -e
                        echo "Setting up Python ${PYTHON_VERSION} environment..."
                        ${PYTHON_BIN} -m venv ${VENV_DIR}
                        source ${VENV_DIR}/bin/activate
                        pip install --upgrade pip
                        pip install -r requirements.txt
                        pip install pytest pytest-cov pip-audit checkov awscli trivy
                    '''
                    script {
                        if (SERVICE_NAME == "aiml-testcase") {
                            sh '''
                                source ${VENV_DIR}/bin/activate
                                python -m spacy download en_core_web_md
                            '''
                        }
                    }
                }
            }

            // ================================================================
            stage(' Tests & Security Scans (Parallel)') {
                parallel {

                    stage(' Unit Tests') {
                        steps {
                            sh '''#!/bin/bash
                                source ${VENV_DIR}/bin/activate
                                pytest --cov=. --cov-report=xml:coverage.xml || true
                            '''
                        }
                    }

                    stage(' Python Dependency Audit') {
                        steps {
                            sh '''#!/bin/bash
                                source ${VENV_DIR}/bin/activate
                                pip-audit -r requirements.txt -f json > pip-audit.json || true
                            '''
                        }
                    }

                    stage(' Checkov Infrastructure Scan') {
                        steps {
                            sh '''#!/bin/bash
                                checkov -d . --output json > checkov-report.json || true
                            '''
                        }
                    }

                    stage(' Trivy Docker Image Scan') {
                        steps {
                            script {
                                def version = getVersionFromPyProject() ?: "latest"
                                sh '''#!/bin/bash
                                    echo "🔍 Running Trivy scan for Docker image: ${DOCKER_IMAGE_NAME}:${version}"
                                    docker build -t ${DOCKER_IMAGE_NAME}:${version} .
                                    trivy image --exit-code 0 --no-progress ${DOCKER_IMAGE_NAME}:${version} > trivy-docker.txt || true
                                    echo " Trivy scan completed — report saved to trivy-docker.txt"
                                '''
                            }
                        }
                    }
                }
            }

            // ================================================================
            stage(' SonarQube Analysis') {
                environment { scannerHome = tool 'sonar-7.2' }
                steps {
                    withSonarQubeEnv("${SONARQUBE_ENV}") {
                        withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                            sh '''#!/bin/bash
                                echo "Running SonarQube analysis..."
                                if [ ! -f sonar-project.properties ]; then
                                    echo "sonar.projectKey=${SERVICE_NAME}" > sonar-project.properties
                                    echo "sonar.python.coverage.reportPaths=coverage.xml" >> sonar-project.properties
                                fi
                                ${scannerHome}/bin/sonar-scanner \
                                    -Dsonar.host.url=$SONAR_HOST_URL \
                                    -Dsonar.login=$SONAR_TOKEN
                            '''
                        }
                    }
                }
            }

            // ================================================================
            stage(' Quality Gate') {
                steps {
                    script {
                        timeout(time: 10, unit: 'MINUTES') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                error " SonarQube Quality Gate failed: ${qg.status}"
                            } else {
                                echo " SonarQube Quality Gate passed."
                            }
                        }
                    }
                }
            }

            // ================================================================
            stage(' Build Docker Image') {
                steps {
                    script {
                        def version = getVersionFromPyProject() ?: "latest"
                        echo " Building Docker image: ${DOCKER_IMAGE_NAME}:${version}"
                        sh '''
                            docker build -t ${DOCKER_IMAGE_NAME}:${version} .
                            docker tag ${DOCKER_IMAGE_NAME}:${version} ${DOCKER_IMAGE_NAME}:latest
                        '''
                    }
                }
            }

            // ================================================================
            stage(' Push Docker Image to GitHub Packages') {
                steps {
                    withCredentials([usernamePassword(credentialsId: "${DOCKERHUB_CREDENTIALS}", usernameVariable: 'USERNAME', passwordVariable: 'TOKEN')]) {
                        sh '''#!/bin/bash
                            echo " Logging into GitHub Container Registry..."
                            echo $TOKEN | docker login ghcr.io -u $USERNAME --password-stdin

                            IMAGE_NAME="ghcr.io/$USERNAME/${DOCKER_IMAGE_NAME}"
                            VERSION=$(python3 -c "import tomllib; print(tomllib.load(open('pyproject.toml','rb'))['project']['version'])" 2>/dev/null || echo latest)

                            echo " Pushing image $IMAGE_NAME:$VERSION"
                            docker tag ${DOCKER_IMAGE_NAME}:${VERSION} $IMAGE_NAME:$VERSION
                            docker push $IMAGE_NAME:$VERSION

                            echo " Pushing image $IMAGE_NAME:latest"
                            docker tag ${DOCKER_IMAGE_NAME}:${VERSION} $IMAGE_NAME:latest
                            docker push $IMAGE_NAME:latest
                        '''
                    }
                }
            }

            // ================================================================
            stage(' Run Microservice Locally (Optional)') {
                when {
                    expression { return config.RUN_LOCAL ?: false }
                }
                steps {
                    sh '''#!/bin/bash
                        echo " Starting ${SERVICE_NAME} on port ${PORT} using ${ENTRYPOINT}"
                        nohup ${PYTHON_BIN} ${ENTRYPOINT} > ${SERVICE_NAME}.log 2>&1 &
                        echo "${SERVICE_NAME} started — logs: ${SERVICE_NAME}.log"
                    '''
                }
            }
        }

        post {
            always {
                echo " Cleaning up temporary files..."
            }
            success {
                echo "  Pipeline successfully completed for ${SERVICE_NAME}"
            }
            failure {
                echo "  Pipeline failed for ${SERVICE_NAME}"
            }
        }
    }
}

// =============================================================
//  Helper Functions
// =============================================================

def getVersionFromPyProject() {
    try {
        return sh(script: "python3 -c \"import tomllib; print(tomllib.load(open('pyproject.toml','rb'))['project']['version'])\"", returnStdout: true).trim()
    } catch (err) {
        echo " Unable to read version from pyproject.toml, defaulting to 'latest'"
        return "latest"
    }
}

def getDefaultEntrypoint(service) {
    def entrypoints = [
        "api-gateway"   : "gateway.py",
        "aiml-testcase" : "Integration.py",
        "jobtestcase"   : "integration.py",
        "feed-aiml"     : "app_main.py"
    ]
    return entrypoints.get(service, "app_main.py")
}

def getDefaultPort(service) {
    def ports = [
        "api-gateway"   : "8000",
        "aiml-testcase" : "8100",
        "jobtestcase"   : "8200",
        "feed-aiml"     : "8300"
    ]
    return ports.get(service, "8000")
}

def detectBranch(repoUrl) {
    try {
        def result = sh(script: "git ls-remote --heads ${repoUrl} | grep -E 'refs/heads/(main|master)' | awk -F/ '{print \$3}' | head -n1", returnStdout: true).trim()
        return result ?: "main"
    } catch (err) {
        echo " Unable to detect branch automatically. Defaulting to 'main'"
        return "main"
    }
}
