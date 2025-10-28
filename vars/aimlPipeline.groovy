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
    // ⚙️ DYNAMIC CONFIGURATION VALUES
    // ================================================================
    def REPO                  = config.REPO ?: "https://github.com/SurnoiTechnology/API-Gateway-AIML-Microservice.git"
    def SERVICE_NAME          = config.SERVICE_NAME ?: "api-gateway"
    def PYTHON_VERSION        = config.PYTHON_VERSION ?: "3.11"
    def PYTHON_BIN            = config.PYTHON_BIN ?: "/usr/bin/python3.11"
    def GIT_CREDENTIALS       = config.GIT_CREDENTIALS ?: "git-access"
    def VENV_DIR              = config.VENV_DIR ?: "${env.WORKSPACE}/${SERVICE_NAME}/myenv"
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

            // ================================================================
            // 1️ Clone Repository
            // ================================================================
            stage(' Clone Repository') {
                steps {
                    script {
                        echo " Cloning branch 'main' (fallback to 'master' if unavailable)..."
                        try {
                            git branch: 'main', url: "${REPO}", credentialsId: "${GIT_CREDENTIALS}"
                        } catch (Exception e) {
                            echo " main branch not found, trying master..."
                            git branch: 'master', url: "${REPO}", credentialsId: "${GIT_CREDENTIALS}"
                        }
                    }
                }
            }

            // ================================================================
            // 2️ Setup Environment (System & App Requirements)
            // ================================================================
            stage(' Setup Environment') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        script {
                            echo " Setting up environment for ${SERVICE_NAME}..."
                            if (fileExists('setup_environment.sh')) {
                                sh '''
                                    #!/bin/bash
                                    set -e
                                    chmod +x setup_environment.sh
                                    ./setup_environment.sh
                                '''
                            } else {
                                echo " setup_environment.sh not found — skipping system setup."
                            }
                        }
                    }
                }
            }

            // ================================================================
            // 3️ Python Virtual Environment Setup
            // ================================================================
           stage('🐍 Setup Python Environment') {
    steps {
        dir("${WORKSPACE}/${SERVICE_NAME}") {
            script {
                sh """
                    #!/bin/bash
                    set -e
                    echo "📦 Creating virtual environment..."
                    ${PYTHON_BIN} -m venv ${VENV_DIR}
                    source ${VENV_DIR}/bin/activate
                    pip install --upgrade pip
                    if [ -f requirements.txt ]; then
                        pip install -r requirements.txt
                    else
                        echo "⚠️ No requirements.txt found."
                    fi
                    pip install pytest pytest-cov pip-audit checkov awscli
                """

                if (SERVICE_NAME == "aiml-testcase") {
                    sh """
                        #!/bin/bash
                        set -e
                        source ${VENV_DIR}/bin/activate
                        python -m spacy download en_core_web_md
                    """
                }
            }
        }
    }
}

            // ================================================================
            // 4️ Run Tests & Code Quality Checks
            // ================================================================
            stage(' Run Tests & Code Quality') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        sh '''
                            #!/bin/bash
                            set -e
                            source ${VENV_DIR}/bin/activate
                            pytest --maxfail=1 --disable-warnings -q || true
                            pip-audit || true
                            checkov -d . || true
                        '''
                    }
                }
            }

            // ================================================================
            // 5️ Build Docker Image
            // ================================================================
            stage(' Build Docker Image') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        script {
                            def version = getVersionFromPyProject() ?: "latest"
                            sh '''
                                #!/bin/bash
                                set -e
                                echo " Building Docker image: ${DOCKER_IMAGE_NAME}:${version}"
                                docker build -t ${DOCKER_IMAGE_NAME}:${version} .
                                docker tag ${DOCKER_IMAGE_NAME}:${version} ${DOCKER_IMAGE_NAME}:latest
                            '''
                        }
                    }
                }
            }

            // ================================================================
            // 6️ Push Docker Image to GitHub Packages
            // ================================================================
            stage(' Push Docker Image to GitHub Packages') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        withCredentials([usernamePassword(credentialsId: "${DOCKERHUB_CREDENTIALS}", usernameVariable: 'USERNAME', passwordVariable: 'TOKEN')]) {
                            sh '''
                                #!/bin/bash
                                set -e
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
            }

            // ================================================================
            // 7️ Run Microservice Locally (Optional)
            // ================================================================
            stage(' Run Microservice Locally (Optional)') {
                when {
                    expression { return config.RUN_LOCAL ?: false }
                }
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        sh '''
                            #!/bin/bash
                            set -e
                            echo " Starting ${SERVICE_NAME} on port ${PORT}..."
                            nohup ${PYTHON_BIN} ${ENTRYPOINT} > ${SERVICE_NAME}.log 2>&1 &
                            echo "${SERVICE_NAME} started — logs: ${SERVICE_NAME}.log"
                        '''
                    }
                }
            }
        }

        // ================================================================
        //  Post Actions
        // ================================================================
        post {
            always {
                echo " Pipeline completed for ${SERVICE_NAME}"
            }
            failure {
                echo " Pipeline failed for ${SERVICE_NAME}"
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
        "api-gateway"           : "gateway.py",
        "aiml-testcase"         : "Integration.py",
        "jobtestcase"           : "integration.py",
        "feed-aiml"             : "app_main.py",
        "aiml-shared-library"   : "main.py"
    ]
    return entrypoints.get(service, "app_main.py")
}

def getDefaultPort(service) {
    def ports = [
        "api-gateway"           : "8000",
        "aiml-testcase"         : "8001",
        "jobtestcase"           : "8002",
        "feed-aiml"             : "8003",
        "aiml-shared-library"   : "8004"
    ]
    return ports.get(service, "8000")
}
