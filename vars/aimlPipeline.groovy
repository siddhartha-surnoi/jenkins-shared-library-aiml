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

    // ================================================================
    // ⚙️ DYNAMIC CONFIGURATION VALUES (Evaluated Before Pipeline Starts)
    // ================================================================
    def REPO                  = config.REPO ?: "https://github.com/SurnoiTechnology/API-Gateway-AIML-Microservice.git"
    def SERVICE_NAME          = config.SERVICE_NAME ?: "api-gateway"
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

            stage('📥 Clone Repository') {
                steps {
                    git branch: 'main', url: "${REPO}", credentialsId: "${GIT_CREDENTIALS}"
                }
            }

            stage('🐍 Setup Python Environment') {
                steps {
                    sh '''#!/bin/bash
                        set -e
                        ${PYTHON_BIN} -m venv ${VENV_DIR}
                        source ${VENV_DIR}/bin/activate
                        pip install --upgrade pip
                        pip install -r requirements.txt
                        pip install pytest pytest-cov pip-audit checkov awscli
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

            stage('🧪 Run Tests & Code Quality') {
                steps {
                    sh '''#!/bin/bash
                        source ${VENV_DIR}/bin/activate
                        pytest --maxfail=1 --disable-warnings -q
                        pip-audit || true
                        checkov -d . || true
                    '''
                }
            }

            stage('🐳 Build Docker Image') {
                steps {
                    script {
                        def version = getVersionFromPyProject() ?: "latest"
                        sh '''
                            echo "📦 Building Docker image: ${DOCKER_IMAGE_NAME}:${version}"
                            docker build -t ${DOCKER_IMAGE_NAME}:${version} .
                            docker tag ${DOCKER_IMAGE_NAME}:${version} ${DOCKER_IMAGE_NAME}:latest
                        '''
                    }
                }
            }

            stage('📤 Push Docker Image to GitHub Packages') {
                steps {
                    withCredentials([usernamePassword(credentialsId: "${DOCKERHUB_CREDENTIALS}", usernameVariable: 'USERNAME', passwordVariable: 'TOKEN')]) {
                        sh '''
                            echo "🔐 Logging into GitHub Container Registry..."
                            echo $TOKEN | docker login ghcr.io -u $USERNAME --password-stdin

                            IMAGE_NAME="ghcr.io/$USERNAME/${DOCKER_IMAGE_NAME}"
                            VERSION=$(python3 -c "import tomllib; print(tomllib.load(open('pyproject.toml','rb'))['project']['version'])" 2>/dev/null || echo latest)

                            echo "🚀 Pushing image $IMAGE_NAME:$VERSION"
                            docker tag ${DOCKER_IMAGE_NAME}:${VERSION} $IMAGE_NAME:$VERSION
                            docker push $IMAGE_NAME:$VERSION

                            echo "🚀 Pushing image $IMAGE_NAME:latest"
                            docker tag ${DOCKER_IMAGE_NAME}:${VERSION} $IMAGE_NAME:latest
                            docker push $IMAGE_NAME:latest
                        '''
                    }
                }
            }

            stage('▶️ Run Microservice Locally (Optional)') {
                when {
                    expression { return config.RUN_LOCAL ?: false }
                }
                steps {
                    sh '''#!/bin/bash
                        echo "🚀 Starting ${SERVICE_NAME} on port ${PORT} using ${ENTRYPOINT}"
                        nohup ${PYTHON_BIN} ${ENTRYPOINT} > ${SERVICE_NAME}.log 2>&1 &
                        echo "${SERVICE_NAME} started — logs: ${SERVICE_NAME}.log"
                    '''
                }
            }
        }

        post {
            always {
                echo "✅ Pipeline completed for ${SERVICE_NAME}"
            }
            failure {
                echo "❌ Pipeline failed for ${SERVICE_NAME}"
            }
        }
    }
}

// =============================================================
// 🔧 Helper Functions
// =============================================================

def getVersionFromPyProject() {
    try {
        return sh(script: "python3 -c \"import tomllib; print(tomllib.load(open('pyproject.toml','rb'))['project']['version'])\"", returnStdout: true).trim()
    } catch (err) {
        echo "⚠️ Unable to read version from pyproject.toml, defaulting to 'latest'"
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
        "aiml-testcase" : "8001",
        "jobtestcase"   : "8002",
        "feed-aiml"     : "8003"
    ]
    return ports.get(service, "8000")
}
